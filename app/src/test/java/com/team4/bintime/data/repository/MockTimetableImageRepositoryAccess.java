package com.team4.bintime.data.repository;

import java.util.List;

/** 테스트 전용: 다른 패키지 테스트가 Mock 이미지 저장소의 테스트용(package-private) 기능을 쓰게 한다. */
public final class MockTimetableImageRepositoryAccess {

    private volatile String uid;
    private final MockTimetableImageRepository repository = new MockTimetableImageRepository(() -> uid);

    public MockTimetableImageRepositoryAccess(String uid) {
        this.uid = uid;
    }

    public TimetableImageRepository repository() {
        return repository;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public void failNextUploadAt(int index, TimetableImageFailure.Code code) {
        repository.failNextUploadAt(index, code);
    }

    public List<String> storedPaths() {
        return repository.storedPaths();
    }
}
