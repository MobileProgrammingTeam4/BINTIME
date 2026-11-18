package com.team4.bintime.data.recognition;

/** 테스트 전용: 다른 패키지 테스트가 Mock 인식 저장소의 테스트용(package-private) 기능을 쓰게 한다. */
public final class MockTimetableRecognitionRepositoryAccess {

    private volatile String uid;
    private final MockTimetableRecognitionRepository repository = new MockTimetableRecognitionRepository(() -> uid);

    public MockTimetableRecognitionRepositoryAccess(String uid) {
        this.uid = uid;
    }

    public TimetableRecognitionRepository repository() {
        return repository;
    }

    public void failNext(RecognitionFailure failure) {
        repository.failNext(failure);
    }

    public void respondNextWith(Object response) {
        repository.respondNextWith(response);
    }

    public int calls() {
        return repository.calls();
    }
}
