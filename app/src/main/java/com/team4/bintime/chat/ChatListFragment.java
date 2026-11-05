package com.team4.bintime.chat;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.team4.bintime.R;
import com.team4.bintime.common.StateView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 채팅 탭: 전체 / 매칭됨 / 매칭종료. */
public class ChatListFragment extends Fragment {

    private static final String STATE_FILTER = "chatFilter";

    private final MockChatRepository mock = MockChatRepository.shared();
    private final ChatRepository repository = mock;

    private ChatRoom.Filter filter = ChatRoom.Filter.ALL;
    private List<ChatRoom> rooms = Collections.emptyList();
    private boolean loaded;
    private ChatRepository.Registration registration;
    private ChatListAdapter adapter;
    private RecyclerView list;
    private StateView stateView;
    private TextView tabAll, tabMatched, tabEnded;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_chat_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (savedInstanceState != null) {
            filter = ChatRoom.Filter.values()[savedInstanceState.getInt(STATE_FILTER)];
        }
        list = view.findViewById(R.id.chatRoomList);
        stateView = view.findViewById(R.id.chatListState);
        adapter = new ChatListAdapter(room ->
                startActivity(ChatRoomActivity.intent(requireContext(), room.matchId)));
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);

        tabAll = view.findViewById(R.id.chatTabAll);
        tabMatched = view.findViewById(R.id.chatTabMatched);
        tabEnded = view.findViewById(R.id.chatTabEnded);
        tabAll.setOnClickListener(v -> selectFilter(ChatRoom.Filter.ALL));
        tabMatched.setOnClickListener(v -> selectFilter(ChatRoom.Filter.MATCHED));
        tabEnded.setOnClickListener(v -> selectFilter(ChatRoom.Filter.ENDED));

        // 데모: 제목을 길게 누르면 정상 → 빈 상태 → 오류 화면 전환 (BT-021 실연동 시 제거)
        view.findViewById(R.id.chatListTitle).setOnLongClickListener(v -> {
            MockChatRepository.DemoState state = mock.cycleDemoState();
            Toast.makeText(requireContext(), getString(R.string.chat_demo_state, state.name()),
                    Toast.LENGTH_SHORT).show();
            subscribe();
            return true;
        });

        updateTabs();
        subscribe();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_FILTER, filter.ordinal());
    }

    @Override
    public void onDestroyView() {
        unsubscribe();
        list = null;
        adapter = null;
        stateView = null;
        super.onDestroyView();
    }

    private void subscribe() {
        unsubscribe();
        loaded = false;
        list.setVisibility(View.GONE);
        stateView.showLoading();
        registration = repository.observeRooms(new ChatRepository.Listener<List<ChatRoom>>() {
            @Override
            public void onData(List<ChatRoom> data) {
                rooms = data;
                loaded = true;
                render();
            }

            @Override
            public void onError() {
                loaded = false;
                list.setVisibility(View.GONE);
                stateView.showError(getString(R.string.chat_list_error))
                        .primary(getString(R.string.chat_retry), v -> {
                            mock.resetDemoState();
                            subscribe();
                        });
            }
        });
    }

    private void unsubscribe() {
        if (registration != null) registration.remove();
        registration = null;
    }

    private void selectFilter(ChatRoom.Filter selected) {
        filter = selected;
        updateTabs();
        // 로딩·오류 중에는 그 상태를 유지하고, 데이터가 오면 선택한 탭으로 표시한다.
        if (loaded) render();
    }

    private void updateTabs() {
        tabAll.setSelected(filter == ChatRoom.Filter.ALL);
        tabMatched.setSelected(filter == ChatRoom.Filter.MATCHED);
        tabEnded.setSelected(filter == ChatRoom.Filter.ENDED);
    }

    private void render() {
        List<ChatRoom> filtered = new ArrayList<>();
        for (ChatRoom room : rooms) {
            if (filter.matches(room)) filtered.add(room);
        }
        adapter.submit(filtered);
        if (filtered.isEmpty()) {
            list.setVisibility(View.GONE);
            showEmpty();
        } else {
            stateView.hide();
            list.setVisibility(View.VISIBLE);
        }
    }

    private void showEmpty() {
        if (filter == ChatRoom.Filter.MATCHED) {
            stateView.showEmpty(getString(R.string.chat_empty_matched_title), getString(R.string.chat_empty_matched_message));
        } else if (filter == ChatRoom.Filter.ENDED) {
            stateView.showEmpty(getString(R.string.chat_empty_ended_title), getString(R.string.chat_empty_ended_message));
        } else {
            stateView.showEmpty(getString(R.string.chat_empty_all_title), getString(R.string.chat_empty_all_message));
        }
    }
}
