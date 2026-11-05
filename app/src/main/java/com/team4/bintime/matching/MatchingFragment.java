package com.team4.bintime.matching;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.team4.bintime.MainActivity;
import com.team4.bintime.R;
import com.team4.bintime.data.MockMatchRepository;
import com.team4.bintime.model.MatchCandidate;
import com.team4.bintime.model.MatchFilter;
import com.team4.bintime.common.ChipViews;
import com.team4.bintime.common.StateView;

import java.util.Collections;
import java.util.List;

/**
 * 매칭 메인(매칭 찾기) 화면.
 * 상태: 스위치 OFF → 공강 없음 → 조건에 맞는 후보 없음 → 카드 리스트 순으로 판정해 상황별 안내를 보여준다.
 */
public class MatchingFragment extends Fragment {

    private MatchFilter filter = MatchFilter.defaults();
    private boolean matchingOn = true;

    private final MatchingAdapter adapter = new MatchingAdapter();
    private ActivityResultLauncher<Intent> filterLauncher;

    private View statusCard, matchContent;
    private TextView statusTitle, statusDesc, count, tabPlaceholder;
    private StateView stateView;
    private TextView[] tabs;
    private MaterialSwitch switchMatching;
    private LinearLayout appliedFilters;
    private RecyclerView recycler;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            filter = (MatchFilter) savedInstanceState.getSerializable(FilterActivity.EXTRA_FILTER);
        }
        filterLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null) {
                filter = (MatchFilter) result.getData().getSerializableExtra(FilterActivity.EXTRA_FILTER);
                render();
            }
        });
    }

    // 화면 회전·다크모드 전환으로 Fragment가 다시 만들어져도 적용한 필터 유지 (스위치는 View가 스스로 복원)
    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable(FilterActivity.EXTRA_FILTER, filter);
    }

    // 프로필 상세에서 매칭 신청 후 돌아오면 카드 버튼도 '신청 완료'로 갱신
    @Override
    public void onResume() {
        super.onResume();
        if (getView() != null) render();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_matching, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        statusCard = v.findViewById(R.id.statusCard);
        statusTitle = v.findViewById(R.id.statusTitle);
        statusDesc = v.findViewById(R.id.statusDesc);
        switchMatching = v.findViewById(R.id.switchMatching);
        appliedFilters = v.findViewById(R.id.appliedFilters);
        count = v.findViewById(R.id.count);
        recycler = v.findViewById(R.id.recycler);
        stateView = v.findViewById(R.id.stateView);
        matchContent = v.findViewById(R.id.matchContent);
        tabPlaceholder = v.findViewById(R.id.tabPlaceholder);

        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setAdapter(adapter);

        tabs = new TextView[]{v.findViewById(R.id.tabFind), v.findViewById(R.id.tabSent),
                v.findViewById(R.id.tabReceived), v.findViewById(R.id.tabDone)};
        for (TextView tab : tabs) tab.setOnClickListener(t -> selectTopTab((TextView) t));
        selectTopTab(tabs[0]);

        v.findViewById(R.id.btnBell).setOnClickListener(b -> toast("알림 화면은 D 담당 화면과 연결 예정"));
        v.findViewById(R.id.btnFilter).setOnClickListener(b -> openFilter());
        v.findViewById(R.id.btnChangeSlot).setOnClickListener(b -> goTimetable());
        switchMatching.setOnCheckedChangeListener((b, checked) -> {
            matchingOn = checked;
            render();
        });
        // 데모용: 타이틀 길게 누르면 '공강 없음' 상태 토글 (시간표 연동 전 시연용)
        v.findViewById(R.id.title).setOnLongClickListener(t -> {
            MockMatchRepository.hasFreeSlot = !MockMatchRepository.hasFreeSlot;
            toast(MockMatchRepository.hasFreeSlot ? "데모: 공강 있음" : "데모: 공강 없음");
            render();
            return true;
        });
        render();
    }

    /** 상단 탭: 매칭 찾기만 이 화면, 나머지는 요청 화면(D 담당) 자리. */
    private void selectTopTab(TextView selected) {
        for (TextView tab : tabs) tab.setSelected(tab == selected);
        boolean find = selected == tabs[0];
        matchContent.setVisibility(find ? View.VISIBLE : View.GONE);
        tabPlaceholder.setVisibility(find ? View.GONE : View.VISIBLE);
        tabPlaceholder.setText(selected.getText() + " 화면\n(D 담당 · 통합 예정)");
    }

    private void render() {
        // 상태 카드
        boolean hasSlot = MockMatchRepository.hasFreeSlot;
        if (switchMatching.isChecked() != matchingOn) switchMatching.setChecked(matchingOn);
        statusCard.setBackgroundResource(matchingOn ? R.drawable.bg_status_on : R.drawable.bg_status_off);
        if (!matchingOn) {
            statusTitle.setText("매칭이 꺼져있어요");
            statusDesc.setText("공강시간에 함께할 공강메이트를 찾아보세요");
        } else if (!hasSlot) {
            statusTitle.setText("매칭 할 공강이 없어요");
            statusDesc.setText("시간표에서 공강시간을 선택해주세요");
        } else {
            statusTitle.setText(MockMatchRepository.MY_SLOT_LABEL + " 공강 매칭중");
            statusDesc.setText("가장 가까운 공강으로 매칭 중이에요");
        }

        // 적용된 필터
        appliedFilters.removeAllViews();
        addAppliedChip(MatchFilter.formatDuration(filter.minOverlapMinutes) + " 이상");
        addAppliedChip(filter.purpose);

        // 결과 / 상황별 안내
        List<MatchCandidate> results = matchingOn && hasSlot ? MockMatchRepository.search(filter) : Collections.emptyList();
        adapter.submit(results, filter.tags);
        count.setText(String.valueOf(results.size()));
        recycler.setVisibility(results.isEmpty() ? View.GONE : View.VISIBLE);

        if (!matchingOn) {
            stateView.showEmpty("매칭 스위치가 꺼져있어요", "매칭 스위치를 켜고\n공강 메이트를 찾아보세요.");
        } else if (!hasSlot) {
            stateView.showEmpty("매칭 할 공강시간이 없어요", "내 시간표에서 공강시간을\n선택해주세요")
                    .primary("시간표에서 공강시간 선택하기", b -> goTimetable());
        } else if (results.isEmpty()) {
            stateView.showEmpty("조건에 맞는 친구가 없어요", "공강 시간이나 목적을 바꿔\n새로운 친구를 찾아보세요.")
                    .secondary("필터 초기화", b -> {
                        filter = MatchFilter.defaults();
                        render();
                    })
                    .primary("필터 변경", b -> openFilter());
        } else {
            stateView.hide();
        }
    }

    private void addAppliedChip(String label) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(ChipViews.dp(requireContext(), 8));
        appliedFilters.addView(ChipViews.filter(requireContext(), label, false), lp);
    }

    private void openFilter() {
        filterLauncher.launch(new Intent(requireContext(), FilterActivity.class)
                .putExtra(FilterActivity.EXTRA_FILTER, filter));
    }

    private void goTimetable() {
        ((MainActivity) requireActivity()).selectTab(R.id.nav_timetable);
    }

    private void toast(String msg) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
