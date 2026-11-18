package com.team4.bintime.data.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * [BT-025] Mock 시간표 이미지 저장소. 실제 Storage에 올리지 않고 메모리에 경로·크기만 기록한다.
 * 콜백은 호출한 스레드에서 즉시(동기) 부른다. <b>Mock 업로드 성공은 실제 업로드 성공이 아니다</b>
 * (실제 연동은 P2 FirebaseTimetableImageRepository).
 * <p>
 * 경로·제한 검사는 실제 구현과 같은 규칙({@link TimetableImagePaths})을 쓴다.
 */
public final class MockTimetableImageRepository implements TimetableImageRepository {

    private final Supplier<String> currentUid;
    /** 경로 → 바이트 수. 이미지 내용은 보관하지 않는다. */
    private final Map<String, Integer> stored = new LinkedHashMap<>();
    private int failIndex = -1;
    private TimetableImageFailure.Code failCode;

    public MockTimetableImageRepository(Supplier<String> currentUid) {
        if (currentUid == null) throw new IllegalArgumentException("currentUid");
        this.currentUid = currentUid;
    }

    /** 테스트용: 다음 업로드에서 index번째 이미지를 code로 실패시킨다. */
    synchronized void failNextUploadAt(int index, TimetableImageFailure.Code code) {
        this.failIndex = index;
        this.failCode = code;
    }

    /** 테스트용: 현재 보관 중인 경로. */
    synchronized List<String> storedPaths() {
        return new ArrayList<>(stored.keySet());
    }

    @Override
    public synchronized Registration uploadBatch(String batchId, List<byte[]> jpegs, UploadListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener is null");
        String uid = currentUid.get();
        if (uid == null) {
            listener.onError(TimetableImageFailure.of(TimetableImageFailure.Code.NOT_SIGNED_IN, "no uid"));
            return () -> { };
        }
        TimetableImageFailure invalid = validateBatch(batchId, jpegs);
        if (invalid != null) {
            listener.onError(invalid);
            return () -> { };
        }
        int injectedIndex = failIndex;
        TimetableImageFailure.Code injectedCode = failCode;
        failIndex = -1;
        failCode = null;

        List<String> uploaded = new ArrayList<>();
        for (int i = 0; i < jpegs.size(); i++) {
            String path = TimetableImagePaths.imagePath(uid, batchId, i);
            long total = jpegs.get(i).length;
            listener.onProgress(i, 0, total);
            if (i == injectedIndex) {
                for (String p : uploaded) stored.remove(p);
                listener.onError(TimetableImageFailure.atImage(injectedCode, i, "injected"));
                return () -> { };
            }
            stored.put(path, jpegs.get(i).length);
            uploaded.add(path);
            listener.onProgress(i, total, total);
        }
        listener.onSuccess(Collections.unmodifiableList(uploaded));
        return () -> { };
    }

    @Override
    public synchronized void deleteImages(List<String> paths, Callback<Void> callback) {
        String uid = currentUid.get();
        if (uid == null) {
            if (callback != null) {
                callback.onError(TimetableImageFailure.of(TimetableImageFailure.Code.NOT_SIGNED_IN, "no uid"));
            }
            return;
        }
        if (paths != null) {
            for (String path : paths) {
                if (TimetableImagePaths.isOwnedBy(uid, path)) stored.remove(path);
            }
        }
        if (callback != null) callback.onSuccess(null);
    }

    /** 실제 구현도 같은 검사를 한다. 통과하면 null. */
    static TimetableImageFailure validateBatch(String batchId, List<byte[]> jpegs) {
        if (!TimetableImagePaths.isValidBatchId(batchId)) {
            return TimetableImageFailure.of(TimetableImageFailure.Code.UNKNOWN, "invalid batchId");
        }
        if (jpegs == null || jpegs.isEmpty() || jpegs.size() > TimetableImagePaths.MAX_IMAGES) {
            return TimetableImageFailure.of(TimetableImageFailure.Code.INVALID_COUNT, "count");
        }
        for (int i = 0; i < jpegs.size(); i++) {
            byte[] jpeg = jpegs.get(i);
            if (jpeg == null || jpeg.length == 0) {
                return TimetableImageFailure.atImage(TimetableImageFailure.Code.CORRUPT, i, "empty");
            }
            if (jpeg.length >= TimetableImagePaths.MAX_UPLOAD_BYTES) {
                return TimetableImageFailure.atImage(TimetableImageFailure.Code.COMPRESSED_TOO_LARGE, i, "size");
            }
        }
        return null;
    }
}
