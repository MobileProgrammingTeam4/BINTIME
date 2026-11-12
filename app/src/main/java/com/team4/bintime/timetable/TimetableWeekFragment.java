package com.team4.bintime.timetable;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.team4.bintime.MainActivity;
import com.team4.bintime.R;
import com.team4.bintime.data.model.Timetable;
import com.team4.bintime.data.repository.AsyncTimetableRepository;
import com.team4.bintime.data.repository.FreeSlotValidator;
import com.team4.bintime.data.repository.LatestValueSaveQueue;
import com.team4.bintime.data.repository.TimetableFailure;
import com.team4.bintime.data.repository.TimetableRepositories;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;

/**
 * 주간 시간표 탭. [BT-015/016]
 * <ul>
 *   <li>화면이 보이는 동안(onStart~onStop) 시간표를 구독해 다른 화면·기기의 변경을 반영한다.</li>
 *   <li>빈 칸을 누르면 공강 선택이 바뀌고, 마지막 누름 후 약 0.8초 뒤 또는 탭을 벗어날 때(숨김·onStop)
 *       {@code saveFreeSlots}로 저장한다. 저장하지 않은 선택이 있는 동안에는 들어오는 값으로 덮어쓰지 않는다.</li>
 *   <li>저장은 {@link TimetableWeekViewModel}의 {@link LatestValueSaveQueue}가 한 번에 하나씩 보낸다. 저장 중에
 *       바뀐 선택은 현재 요청이 끝난 뒤 최신 값으로 다시 저장한다. View가 사라져도 큐는 취소되지 않는다.</li>
 *   <li>매칭 대상 공강 구간이 40개를 넘는 선택은 저장 전에 안내하고 되돌린다.</li>
 *   <li>저장이 실패하면 안내하고 화면의 선택을 그대로 둔 채 "다시 저장 필요"로 남긴다. 다음 저장 시도(칸 변경·
 *       탭 이탈) 때 최신 선택으로 재시도한다.</li>
 * </ul>
 */
public class TimetableWeekFragment extends Fragment {
    private static final long SAVE_DELAY_MS = 800;

    private final ActivityResultLauncher<Intent> manage = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK) render();
            });

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable saveRunnable = this::flushFreeSlots;

    private AsyncTimetableRepository.Registration registration;
    /** 화면에 그리는 시간표. 구독 값으로 바뀌며, 칸을 누르면 선택만 바뀐다. */
    private TimetableDraft draft = new TimetableDraft(Collections.emptyList());
    /** 마지막으로 받은 저장소 값. 저장 실패·저장 완료 뒤 다시 그릴 때 쓴다. */
    private Timetable latest;
    /** 마지막으로 받아들인 선택(40개 상한 초과 시 되돌릴 값). */
    private long[] acceptedCells = new long[7];
    /** 저장소 값을 한 번이라도 받았다. 그 전에는 칸 선택을 받지 않는다(빈 화면으로 저장된 선택을 덮지 않도록). */
    private boolean loaded;
    /** 공강 선택 저장 큐(ViewModel 소유). needsSave()면 저장하지 않은 선택이 있다. */
    private LatestValueSaveQueue<long[], TimetableFailure> saveQueue;

    public TimetableWeekFragment() {
        super(R.layout.fragment_timetable_week);
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        saveQueue = new ViewModelProvider(this).get(TimetableWeekViewModel.class)
                .freeSlotQueue(TimetableRepositories.get(requireContext()));
        saveQueue.setListener(new LatestValueSaveQueue.Listener<TimetableFailure>() {
            @Override
            public void onAllSaved() {
                if (latest != null) applyLatest();
            }

            @Override
            public void onSaveFailed(TimetableFailure failure) {
                // 선택은 그대로 두고 다시 저장 필요 상태로 남긴다(다음 저장 시도 때 최신 선택으로 재시도).
                if (getContext() != null) {
                    Toast.makeText(getContext(), TimetableMessages.of(failure), Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    @Override
    public void onDestroy() {
        saveQueue.setListener(null); // 저장은 ViewModel 쪽에서 계속된다
        super.onDestroy();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle state) {
        view.findViewById(R.id.manage_timetable).setOnClickListener(v -> {
            flushFreeSlots();
            manage.launch(TimetableReviewActivity.forEditing(requireContext()));
        });
        view.findViewById(R.id.btnChangeSlot).setVisibility(View.GONE);
        TextView footer = view.findViewById(R.id.matching_status_footer);
        footer.setGravity(Gravity.CENTER);
        footer.setText("시간표의 빈공간을 눌러 공강시간을 선택할 수 있어요");
        render();
    }

    @Override
    public void onStart() {
        super.onStart();
        subscribe();
    }

    @Override
    public void onStop() {
        flushFreeSlots();
        if (registration != null) {
            registration.remove();
            registration = null;
        }
        super.onStop();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) flushFreeSlots();
        else render();
    }

    @Override
    public void onDestroyView() {
        flushFreeSlots(); // 예약만 된 저장을 버리지 않는다
        super.onDestroyView();
    }

    private void subscribe() {
        if (registration != null) return;
        registration = TimetableRepositories.get(requireContext()).observeTimetable(
                new AsyncTimetableRepository.Listener<Timetable>() {
                    @Override
                    public void onData(Timetable timetable) {
                        boolean first = !loaded;
                        latest = timetable;
                        loaded = true;
                        if (!saveQueue.needsSave()) applyLatest();
                        else if (first) restoreUnsaved();
                    }

                    @Override
                    public void onError(TimetableFailure failure) {
                        registration = null;
                        if (getContext() == null) return;
                        Toast.makeText(getContext(),
                                failure.getCode() == TimetableFailure.Code.NOT_SIGNED_IN
                                        ? TimetableMessages.of(failure) : TimetableMessages.LOAD_FAILED,
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void applyLatest() {
        draft = TimetableDraftMapper.toDraft(latest);
        draft.discardOccupiedFreeSlots();
        acceptedCells = draft.freeCells();
        render();
    }

    /** 화면이 다시 만들어졌는데(회전 등) 저장하지 못한 선택이 큐에 남아 있으면 그 선택으로 그린다. */
    private void restoreUnsaved() {
        draft = TimetableDraftMapper.toDraft(latest);
        draft.setFreeCells(saveQueue.latest());
        draft.discardOccupiedFreeSlots();
        acceptedCells = draft.freeCells();
        if (!Arrays.equals(acceptedCells, saveQueue.latest())) {
            saveQueue.update(acceptedCells.clone());
            handler.removeCallbacks(saveRunnable);
            handler.postDelayed(saveRunnable, SAVE_DELAY_MS);
        }
        render();
    }

    private void render() {
        View view = getView();
        if (view == null || !(getActivity() instanceof MainActivity)) return;
        MainActivity host = (MainActivity) requireActivity();
        TimetableWeekView week = view.findViewById(R.id.timetable_week);
        week.setTitle("주간 시간표");
        week.bind(draft, () -> onCellToggled(view, host));
        MaterialSwitch toggle = view.findViewById(R.id.switchMatching);
        toggle.setOnCheckedChangeListener(null);
        toggle.setChecked(host.isMatchingOn());
        toggle.setOnCheckedChangeListener((button, checked) -> {
            host.setMatchingOn(checked);
            updateStatus(view, host, draft);
        });
        updateStatus(view, host, draft);
    }

    private void onCellToggled(View view, MainActivity host) {
        if (!loaded) {
            draft.setFreeCells(acceptedCells);
            render();
            return;
        }
        long[] cells = draft.freeCells();
        if (FreeSlotValidator.checkLimit(cells) != null) {
            // 저장 전에 안내하고 이번 선택을 되돌린다.
            Toast.makeText(requireContext(), TimetableMessages.TOO_MANY_FREE_SLOTS, Toast.LENGTH_LONG).show();
            draft.setFreeCells(acceptedCells);
            render();
            return;
        }
        if (Arrays.equals(cells, acceptedCells)) return;
        acceptedCells = cells;
        saveQueue.update(cells.clone());
        handler.removeCallbacks(saveRunnable);
        handler.postDelayed(saveRunnable, SAVE_DELAY_MS);
        updateStatus(view, host, draft);
    }

    /**
     * 예약된 저장을 바로 요청한다. 저장할 변경이 없으면 아무것도 하지 않는다.
     * 이미 저장 중이면 큐가 현재 요청이 끝난 뒤 최신 선택으로 한 번 더 저장한다.
     */
    private void flushFreeSlots() {
        handler.removeCallbacks(saveRunnable);
        saveQueue.flush();
    }

    private void updateStatus(View view, MainActivity host, TimetableDraft draft) {
        boolean enabled = host.isMatchingOn();
        view.findViewById(R.id.statusCard).setBackgroundResource(
                enabled ? R.drawable.bg_status_on : R.drawable.bg_status_off);
        TextView title = view.findViewById(R.id.statusTitle);
        TextView description = view.findViewById(R.id.statusDesc);
        if (!enabled) {
            title.setText("매칭이 꺼져있어요");
            description.setText("공강시간에 함께할 공강메이트를 찾아보세요");
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        TimetableDraft.FreeWindow next = draft.nextFreeWindow(now);
        if (next == null) {
            title.setText("매칭 할 공강이 없어요");
            description.setText("시간표에서 공강시간을 선택해주세요");
            return;
        }
        String date = next.date.equals(now.toLocalDate()) ? "오늘" : next.date.format(DateTimeFormatter.ofPattern("M/d"));
        String day = TimetableDraft.DAYS[next.date.getDayOfWeek().getValue() - 1];
        title.setText(date + "(" + day + ") " + TimetableWeekView.slotRange(next.start, next.end) + " 공강 매칭중");
        description.setText("가장 가까운 공강으로 매칭이 진행돼요");
    }
}
