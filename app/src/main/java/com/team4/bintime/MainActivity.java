package com.team4.bintime;

import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.team4.bintime.matching.MatchingFragment;
import com.team4.bintime.request.MockRequestRepository;
import com.team4.bintime.notification.NotificationListFragment;

/**
 * 공통 화면 이동 담당: 하단 탭 4개를 Fragment show/hide 로 전환한다 (탭 상태 유지).
 * 각 탭 화면은 담당자가 PlaceholderFragment 자리를 자기 Fragment 로 바꾸면 된다 → createTab() 만 수정.
 */
public class MainActivity extends AppCompatActivity {

    private static final int[] TAB_IDS = {R.id.nav_matching, R.id.nav_chat, R.id.nav_timetable, R.id.nav_my};

    private BottomNavigationView bottomNav;
    private MockRequestRepository requests = new MockRequestRepository();
    private boolean popupShown;
    public MockRequestRepository requests() { return requests; }
    public boolean claimRequestPopup() {
        if (popupShown) return false;
        popupShown = true;
        return true;
    }
    public void openNotifications() {
        FragmentManager fm = getSupportFragmentManager();
        if (fm.isStateSaved() || fm.findFragmentByTag("notifications") != null) return;
        FragmentTransaction tx = fm.beginTransaction().setReorderingAllowed(true);
        for (Fragment f : fm.getFragments()) if (f.isVisible()) tx.hide(f);
        tx.add(R.id.container, new NotificationListFragment(), "notifications")
                .addToBackStack("notifications").commit();
    }
    @Override protected void onSaveInstanceState(@NonNull Bundle out) {
        out.putSerializable("sharedRequests", requests);
        out.putBoolean("requestPopupShown", popupShown);
        super.onSaveInstanceState(out);
    }

    // 뒤로가기 규칙: 매칭 외 탭에서는 매칭 탭으로 돌아오고, 매칭 탭에서는 기본 동작(앱 종료)
    private final OnBackPressedCallback backToMatching = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            if (getSupportFragmentManager().getBackStackEntryCount() > 0) getSupportFragmentManager().popBackStack();
            else selectTab(R.id.nav_matching);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            MockRequestRepository restored = (MockRequestRepository) savedInstanceState.getSerializable("sharedRequests");
            if (restored != null) requests = restored;
            popupShown = savedInstanceState.getBoolean("requestPopupShown");
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bottomNav = findViewById(R.id.bottomNav);
        bottomNav.setItemActiveIndicatorEnabled(false);
        bottomNav.setOnItemSelectedListener(item -> {
            getSupportFragmentManager().popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
            showTab(item.getItemId());
            backToMatching.setEnabled(item.getItemId() != R.id.nav_matching);
            return true;
        });
        getOnBackPressedDispatcher().addCallback(this, backToMatching);
        if (savedInstanceState == null) showTab(R.id.nav_matching);
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        // 회전 후 복원된 탭 기준으로 뒤로가기 규칙을 다시 맞춘다 (복원 시엔 선택 리스너가 안 불림)
        backToMatching.setEnabled(bottomNav.getSelectedItemId() != R.id.nav_matching);
    }

    /** 다른 화면에서 탭 이동이 필요할 때 사용 (예: 매칭 → 시간표). */
    public void selectTab(@IdRes int tabId) {
        bottomNav.setSelectedItemId(tabId);
    }

    private void showTab(@IdRes int tabId) {
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction().setReorderingAllowed(true);
        for (int id : TAB_IDS) {
            String tag = "tab_" + id;
            Fragment f = fm.findFragmentByTag(tag);
            if (id == tabId) {
                if (f == null) tx.add(R.id.container, createTab(id), tag);
                else tx.show(f);
            } else if (f != null) {
                tx.hide(f);
            }
        }
        tx.commit();
    }

    private Fragment createTab(@IdRes int tabId) {
        if (tabId == R.id.nav_chat) return PlaceholderFragment.newInstance("채팅", "E");
        if (tabId == R.id.nav_timetable) return PlaceholderFragment.newInstance("시간표", "C");
        if (tabId == R.id.nav_my) return PlaceholderFragment.newInstance("마이페이지", "B");
        return new MatchingFragment();
    }
}
