package com.team4.bintime.timetable;

import java.util.HashMap;
import java.util.Map;

import com.team4.bintime.auth.MockAuthRepository;

/** Sprint 1 local mock. BT-015/016 and BT-025/026 cover real timetable storage, availability, and OCR. */
final class MockTimetableRepository {
    private static final Map<String, TimetableDraft> drafts = new HashMap<>();

    private MockTimetableRepository() {}

    static TimetableDraft load() {
        TimetableDraft draft = drafts.get(MockAuthRepository.shared().getCurrentUid());
        return draft == null ? null : draft.copy();
    }

    static void save(TimetableDraft draft) {
        String uid = MockAuthRepository.shared().getCurrentUid();
        if (uid != null) drafts.put(uid, draft.copy());
    }
}
