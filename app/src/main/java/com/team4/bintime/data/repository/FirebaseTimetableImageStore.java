package com.team4.bintime.data.repository;

import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.UploadTask;

/**
 * [BT-025 P2] Cloud Storage 와 직접 닿는 얇은 연결층. 순서·취소·정리 판단은 {@link FirebaseTimetableImageRepository}가
 * 하고, 여기는 putBytes·delete 호출과 오류 코드 변환({@link TimetableImageStorageErrors})만 한다.
 * Task 리스너는 메인 스레드에서 불린다. 경로·바이트를 로그에 남기지 않는다.
 */
final class FirebaseTimetableImageStore implements FirebaseTimetableImageRepository.Store {

    /**
     * 업로드 재시도 상한. #101(BT-030) 프로필 저장의 {@code FirebaseProfileRepository.STORAGE_RETRY_MILLIS}(20초,
     * package-private 이라 가져올 수 없음)와 같은 값이다. 기본 10분 동안 재시도하면 오프라인에서 업로드 화면이 묶이므로
     * 20초 뒤 {@code ERROR_RETRY_LIMIT_EXCEEDED} → 네트워크 안내로 끝낸다.
     * {@link FirebaseStorage#getInstance()}는 앱 전역 하나라 이 설정은 프로필 업로드에도 같은 값으로 적용된다.
     * 삭제 등 다른 작업의 재시도 시간은 바꾸지 않는다.
     */
    static final long UPLOAD_RETRY_MILLIS = 20_000L;

    private final FirebaseStorage storage;

    FirebaseTimetableImageStore(FirebaseStorage storage) {
        this.storage = storage;
        storage.setMaxUploadRetryTimeMillis(UPLOAD_RETRY_MILLIS);
    }

    @Override
    public Handle put(String path, byte[] jpeg, String contentType, PutListener listener) {
        StorageMetadata metadata = new StorageMetadata.Builder().setContentType(contentType).build();
        UploadTask task = storage.getReference(path).putBytes(jpeg, metadata);
        task.addOnProgressListener(snapshot ->
                        listener.onProgress(snapshot.getBytesTransferred(), snapshot.getTotalByteCount()))
                .addOnSuccessListener(snapshot -> listener.onSuccess())
                .addOnFailureListener(e -> listener.onError(TimetableImageStorageErrors.fromException(e)));
        return task::cancel;
    }

    @Override
    public void delete(String path, DeleteListener listener) {
        storage.getReference(path).delete().addOnCompleteListener(task -> {
            Exception e = task.getException();
            if (task.isSuccessful() || TimetableImageStorageErrors.isNotFound(e)) {
                listener.onDone(null);
            } else {
                listener.onDone(TimetableImageStorageErrors.fromException(e));
            }
        });
    }
}
