package com.team4.bintime.profile;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.team4.bintime.R;
import com.team4.bintime.common.StateView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 차단한 사용자 관리 (BT-046, Figma 117:1906). 마이 → 차단한 사용자 관리에서 연다.
 * 목록은 본인 차단 기록 + 상대 공개 프로필. 해제는 서버(unblockUser)가 하고, 성공하면 줄을 지운다.
 * Figma 에 해제 확인창이 없어 바로 해제한다. 해제 중에는 그 줄의 버튼을 막아 중복 요청을 보내지 않는다.
 */
public class BlockedUsersActivity extends AppCompatActivity {

    private BlockRepository repository;
    private LinearLayout list;
    private View header;
    private TextView count;
    private StateView state;
    private final List<BlockedUser> users = new ArrayList<>();
    /** 해제 요청 중인 UID. 회전 등으로 화면이 다시 만들어지면 서버 결과를 다시 읽어 맞춘다. */
    private final Set<String> pending = new HashSet<>();
    /** 늦게 온 목록 결과가 최신 결과를 덮지 않게 한다. */
    private int loadGeneration;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_blocked_users);
        repository = BlockRepositories.get(this);
        list = findViewById(R.id.blockedList);
        header = findViewById(R.id.blockedHeader);
        count = findViewById(R.id.blockedCount);
        state = findViewById(R.id.blockedState);
        findViewById(R.id.blockedBack).setOnClickListener(v -> finish());
        load();
    }

    private boolean gone() {
        return isFinishing() || isDestroyed();
    }

    private void load() {
        int generation = ++loadGeneration;
        header.setVisibility(View.GONE);
        list.removeAllViews();
        state.showLoading();
        repository.getBlockedUsers(new BlockRepository.Callback<List<BlockedUser>>() {
            @Override
            public void onSuccess(List<BlockedUser> result) {
                if (gone() || generation != loadGeneration) return;
                users.clear();
                users.addAll(result);
                render();
            }

            @Override
            public void onError(String message) {
                if (gone() || generation != loadGeneration) return;
                header.setVisibility(View.GONE);
                list.removeAllViews();
                state.showError(getString(R.string.block_load_failed))
                        .primary(getString(R.string.block_retry), v -> load());
            }
        });
    }

    private void render() {
        header.setVisibility(View.VISIBLE);
        count.setText(getString(R.string.block_count, users.size()));
        list.removeAllViews();
        if (users.isEmpty()) {
            state.showEmpty(getString(R.string.block_empty_title), getString(R.string.block_empty_message));
            return;
        }
        state.hide();
        for (BlockedUser user : users) list.addView(row(user));
    }

    private View row(BlockedUser user) {
        View row = getLayoutInflater().inflate(R.layout.item_blocked_user, list, false);
        String name = user.nickname() != null ? user.nickname() : getString(R.string.block_unknown_user);
        ((TextView) row.findViewById(R.id.blockedName)).setText(name);
        TextView info = row.findViewById(R.id.blockedInfo);
        String summary = user.summary();
        info.setText(summary);
        info.setVisibility(summary.isEmpty() ? View.GONE : View.VISIBLE);
        TextView unblock = row.findViewById(R.id.blockedUnblock);
        unblock.setContentDescription(getString(R.string.block_unblock_description, name));
        boolean busy = pending.contains(user.uid);
        unblock.setEnabled(!busy);
        unblock.setAlpha(busy ? 0.4f : 1f);
        unblock.setOnClickListener(v -> unblock(user));
        return row;
    }

    private void unblock(BlockedUser user) {
        if (!pending.add(user.uid)) return;
        render();
        repository.unblock(user.uid, new BlockRepository.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                pending.remove(user.uid);
                if (gone()) return;
                users.remove(user);
                render();
                Toast.makeText(BlockedUsersActivity.this, R.string.block_unblocked, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                pending.remove(user.uid);
                if (gone()) return;
                render();
                Toast.makeText(BlockedUsersActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
