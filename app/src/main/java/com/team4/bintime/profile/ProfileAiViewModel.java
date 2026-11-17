package com.team4.bintime.profile;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.team4.bintime.data.model.Course;
import com.team4.bintime.data.model.Timetable;
import com.team4.bintime.data.repository.AsyncTimetableRepository;
import com.team4.bintime.data.repository.TimetableFailure;
import com.team4.bintime.data.repository.TimetableRepositories;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * BT-029 'AI로 작성하기': 내 시간표의 수업 이름을 한 번 읽고 → generateProfile 을 부른다.
 * 화면이 회전해도 요청이 이어지고, 새 화면이 결과(LiveData)를 받는다. 결과는 {@link Result#consume()} 로 한 번만 처리한다.
 * 수업 이름 외의 시간표 정보(시간·교수·강의실)는 보내지 않는다.
 */
public class ProfileAiViewModel extends ViewModel {

    public static final class Result {
        /** 성공 시 초안, 실패 시 null */
        public final AiProfileDraft draft;
        /** 실패 시 사용자 문구 */
        public final String message;
        /** 이번 요청에 실제로 보낸 수업 이름(안내 카드 문구용) */
        public final List<String> courseNames;
        private boolean consumed;

        private Result(AiProfileDraft draft, String message, List<String> courseNames) {
            this.draft = draft;
            this.message = message;
            this.courseNames = Collections.unmodifiableList(new ArrayList<>(courseNames));
        }

        public boolean isSuccess() {
            return draft != null;
        }

        /** 처음 부르면 true, 이미 처리한 결과면 false. */
        public boolean consume() {
            if (consumed) return false;
            consumed = true;
            return true;
        }
    }

    private final MutableLiveData<Result> result = new MutableLiveData<>();
    private boolean running;
    private AsyncTimetableRepository.Registration timetableRegistration;

    public LiveData<Result> getResult() {
        return result;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * 요청을 시작한다. 이미 진행 중이면 무시한다(중복 클릭 방지). 메인 스레드에서 호출한다.
     *
     * @param appContext Application Context (Activity 를 붙잡지 않도록)
     */
    public void generate(Context appContext, ProfileRepository repository, String department, int grade,
                         List<String> preferredActivities, String draftBio) {
        if (running) return;
        // 학과·학년이 없으면 서버까지 가지 않고 바로 안내한다(시간표도 읽지 않음).
        String invalid = AiProfileMapper.validate(new AiProfileRequest(department, grade,
                preferredActivities, Collections.emptyList(), draftBio));
        if (invalid != null) {
            result.setValue(new Result(null, invalid, Collections.emptyList()));
            return;
        }
        running = true;
        List<String> preferred = new ArrayList<>(preferredActivities);
        loadCourseNames(appContext, courseNames -> {
            AiProfileRequest request = new AiProfileRequest(department, grade, preferred, courseNames, draftBio);
            repository.generateAiProfile(request, new ProfileRepository.Callback<AiProfileDraft>() {
                @Override
                public void onSuccess(AiProfileDraft draft) {
                    finish(new Result(draft, null, request.courseNames));
                }

                @Override
                public void onError(String message) {
                    finish(new Result(null, message, request.courseNames));
                }
            });
        });
    }

    private void finish(Result value) {
        running = false;
        result.setValue(value);
    }

    private interface CourseNamesCallback {
        void onNames(List<String> names);
    }

    /** 현재 시간표를 한 번만 읽고 구독을 바로 끊는다. 시간표가 없거나 못 읽으면 과목 없이 진행한다. */
    private void loadCourseNames(Context appContext, CourseNamesCallback callback) {
        AsyncTimetableRepository repo = TimetableRepositories.get(appContext);
        final boolean[] delivered = {false};
        timetableRegistration = repo.observeTimetable(new AsyncTimetableRepository.Listener<Timetable>() {
            @Override
            public void onData(Timetable timetable) {
                if (delivered[0]) return;
                delivered[0] = true;
                stopTimetable();
                callback.onNames(courseNamesOf(timetable));
            }

            @Override
            public void onError(TimetableFailure failure) {
                if (delivered[0]) return;
                delivered[0] = true;
                stopTimetable();
                callback.onNames(Collections.emptyList());
            }
        });
        // 등록 중에 바로 값이 온 경우(Mock) 등록 핸들이 늦게 들어오므로 여기서 한 번 더 끊는다.
        if (delivered[0]) stopTimetable();
    }

    static List<String> courseNamesOf(Timetable timetable) {
        List<String> names = new ArrayList<>();
        if (timetable == null || timetable.getCourses() == null) return names;
        for (Course course : timetable.getCourses()) {
            if (course != null && course.getName() != null) names.add(course.getName());
        }
        return names;
    }

    private void stopTimetable() {
        if (timetableRegistration != null) {
            timetableRegistration.remove();
            timetableRegistration = null;
        }
    }

    @Override
    protected void onCleared() {
        stopTimetable();
    }
}
