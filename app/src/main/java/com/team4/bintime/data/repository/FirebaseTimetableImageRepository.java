package com.team4.bintime.data.repository;

import android.content.Context;
import android.util.Log;

import com.google.firebase.storage.FirebaseStorage;
import com.team4.bintime.auth.AuthRepositories;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * [BT-025 P2] 실제 Cloud Storage 시간표 이미지 저장소.
 * <ul>
 *   <li>UID는 호출마다 {@code AuthRepositories.get(context).getCurrentUid()}로 얻는다. 없으면 업로드·삭제를 시작하지
 *       않고 {@code NOT_SIGNED_IN}.</li>
 *   <li>경로는 {@link TimetableImagePaths#imagePath}로만 만든다: {@code timetableImages/{uid}/{batchId}/{i}.jpg}.
 *       Storage Rules(#107)는 같은 경로 덮어쓰기(update)를 허용하지 않으므로, 앱은 분석마다 새 batchId 를 쓴다
 *       ({@code TimetableImageUploader.start}).</li>
 *   <li>이미지는 순서대로 하나씩 {@code putBytes}(contentType {@code image/jpeg})로 올린다. 진행률은 이미지별로 알린다.</li>
 *   <li>실패: 실패를 먼저 알린 뒤, 이번 묶음에서 시도한 0..i번 경로를 최선 시도로 지운다(실패한 i번도 서버에 남았을 수
 *       있어 포함, 없는 파일은 성공). 정리 실패는 사용자에게 알리지 않고 로그(오류 코드만)만 남긴다.</li>
 *   <li>취소({@link Registration#cancel}): 진행 중 업로드를 취소하고, 이미 올라간 이미지를 지운다. 취소 뒤에는 콜백을
 *       부르지 않는다. 취소 직전에 끝나 버린 업로드는 결과가 도착하면 지운다.</li>
 *   <li>콜백·상태는 메인 스레드에서만 다룬다(Firebase Task 리스너가 메인 스레드에서 불림).</li>
 * </ul>
 * 남은 이미지(오프라인이라 정리 실패, 프로세스 종료)는 버킷 Lifecycle(관리자, docs/BT-025.md)에 맡긴다.
 */
public final class FirebaseTimetableImageRepository implements TimetableImageRepository {

    private static final String TAG = "TimetableImages";

    /** Storage 호출. 단위 테스트는 가짜 구현을 넣는다. */
    interface Store {
        interface Handle {
            void cancel();
        }

        interface PutListener {
            void onProgress(long sentBytes, long totalBytes);

            void onSuccess();

            void onError(TimetableImageFailure.Code code);
        }

        interface DeleteListener {
            /** @param failure 성공(없는 파일 포함)이면 null */
            void onDone(TimetableImageFailure.Code failure);
        }

        Handle put(String path, byte[] jpeg, String contentType, PutListener listener);

        void delete(String path, DeleteListener listener);
    }

    /** 사용자에게 보이지 않는 정리 실패 기록. 경로·UID는 넘기지 않는다. */
    interface CleanupLog {
        void deleteFailed(TimetableImageFailure.Code code);
    }

    private static FirebaseTimetableImageRepository instance;

    private final Store store;
    private final Supplier<String> currentUid;
    private final CleanupLog cleanupLog;

    FirebaseTimetableImageRepository(Store store, Supplier<String> currentUid, CleanupLog cleanupLog) {
        if (store == null || currentUid == null || cleanupLog == null) throw new IllegalArgumentException("null");
        this.store = store;
        this.currentUid = currentUid;
        this.cleanupLog = cleanupLog;
    }

    public static synchronized FirebaseTimetableImageRepository get(Context context) {
        if (instance == null) {
            Context app = context.getApplicationContext();
            instance = new FirebaseTimetableImageRepository(
                    new FirebaseTimetableImageStore(FirebaseStorage.getInstance()),
                    () -> AuthRepositories.get(app).getCurrentUid(),
                    code -> Log.w(TAG, "image cleanup failed: " + code));
        }
        return instance;
    }

    @Override
    public Registration uploadBatch(String batchId, List<byte[]> jpegs, UploadListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener is null");
        String uid = uid();
        if (uid == null) {
            listener.onError(TimetableImageFailure.of(TimetableImageFailure.Code.NOT_SIGNED_IN, "no uid"));
            return () -> { };
        }
        TimetableImageFailure invalid = MockTimetableImageRepository.validateBatch(batchId, jpegs);
        if (invalid != null) {
            listener.onError(invalid);
            return () -> { };
        }
        Batch batch = new Batch(uid, batchId, new ArrayList<>(jpegs), listener);
        batch.next(0);
        return batch;
    }

    @Override
    public void deleteImages(List<String> paths, Callback<Void> callback) {
        String uid = uid();
        if (uid == null) {
            notSignedIn(callback);
            return;
        }
        List<String> owned = new ArrayList<>();
        if (paths != null) {
            for (String path : paths) {
                if (TimetableImagePaths.isOwnedBy(uid, path) && !owned.contains(path)) owned.add(path);
            }
        }
        deleteAll(owned, callback);
    }

    @Override
    public void deleteBatch(String batchId, Callback<Void> callback) {
        String uid = uid();
        if (uid == null) {
            notSignedIn(callback);
            return;
        }
        if (!TimetableImagePaths.isValidBatchId(batchId)) {
            if (callback != null) {
                callback.onError(TimetableImageFailure.of(TimetableImageFailure.Code.UNKNOWN, "invalid batchId"));
            }
            return;
        }
        deleteAll(batchPaths(uid, batchId, TimetableImagePaths.MAX_IMAGES - 1), callback);
    }

    /** 현재 로그인 UID. 없거나 경로로 쓸 수 없으면 null. */
    private String uid() {
        String uid = currentUid.get();
        return uid != null && CourseValidator.isValidDocumentId(uid) ? uid : null;
    }

    private static void notSignedIn(Callback<Void> callback) {
        if (callback != null) {
            callback.onError(TimetableImageFailure.of(TimetableImageFailure.Code.NOT_SIGNED_IN, "no uid"));
        }
    }

    /** 묶음의 0..lastIndex 경로. 목록 조회 없이 알려진 경로만 쓴다. */
    private static List<String> batchPaths(String uid, String batchId, int lastIndex) {
        List<String> paths = new ArrayList<>();
        for (int i = 0; i <= lastIndex; i++) paths.add(TimetableImagePaths.imagePath(uid, batchId, i));
        return paths;
    }

    /** 모두 지운 뒤 한 번 알린다. callback 이 없으면(정리) 실패를 로그로만 남긴다. */
    private void deleteAll(List<String> paths, Callback<Void> callback) {
        if (paths.isEmpty()) {
            if (callback != null) callback.onSuccess(null);
            return;
        }
        int[] remaining = {paths.size()};
        TimetableImageFailure.Code[] firstFailure = {null};
        for (String path : paths) {
            store.delete(path, failure -> {
                if (failure != null) {
                    if (firstFailure[0] == null) firstFailure[0] = failure;
                    if (callback == null) cleanupLog.deleteFailed(failure);
                }
                if (--remaining[0] > 0 || callback == null) return;
                if (firstFailure[0] == null) {
                    callback.onSuccess(null);
                } else {
                    callback.onError(TimetableImageFailure.of(firstFailure[0], "delete"));
                }
            });
        }
    }

    /** 한 번의 uploadBatch. 메인 스레드에서만 다룬다. */
    private final class Batch implements Registration {
        private final String uid;
        private final String batchId;
        private final List<byte[]> jpegs;
        private final UploadListener listener;
        private final List<String> uploaded = new ArrayList<>();
        /** 성공·실패·취소로 끝났다. 이후 사용자 콜백은 부르지 않는다. */
        private boolean finished;
        private int inFlightIndex = -1;
        private Store.Handle inFlight;

        Batch(String uid, String batchId, List<byte[]> jpegs, UploadListener listener) {
            this.uid = uid;
            this.batchId = batchId;
            this.jpegs = jpegs;
            this.listener = listener;
        }

        void next(int index) {
            if (finished) return;
            if (index == jpegs.size()) {
                finished = true;
                inFlight = null;
                listener.onSuccess(Collections.unmodifiableList(new ArrayList<>(uploaded)));
                return;
            }
            String path = TimetableImagePaths.imagePath(uid, batchId, index);
            byte[] jpeg = jpegs.get(index);
            listener.onProgress(index, 0, jpeg.length);
            inFlightIndex = index;
            Store.Handle handle = store.put(path, jpeg, TimetableImagePaths.CONTENT_TYPE, new Store.PutListener() {
                private boolean settled;

                @Override
                public void onProgress(long sentBytes, long totalBytes) {
                    if (finished || settled) return;
                    listener.onProgress(index, sentBytes, totalBytes);
                }

                @Override
                public void onSuccess() {
                    if (settled) return;
                    settled = true;
                    if (finished) {
                        // 취소 직전에 끝난 업로드: 남기지 않는다.
                        deleteQuietly(path);
                        return;
                    }
                    uploaded.add(path);
                    next(index + 1);
                }

                @Override
                public void onError(TimetableImageFailure.Code code) {
                    if (settled) return;
                    settled = true;
                    if (finished) return; // 취소로 끝난 업로드
                    finished = true;
                    inFlight = null;
                    listener.onError(TimetableImageFailure.atImage(code, index, "upload"));
                    for (String p : batchPaths(uid, batchId, index)) deleteQuietly(p);
                }
            });
            // 가짜 저장소처럼 put 안에서 바로 끝나면 이미 다음 이미지로 넘어갔으므로 이 핸들을 보관하지 않는다.
            if (!finished && inFlightIndex == index) inFlight = handle;
        }

        @Override
        public void cancel() {
            if (finished) return;
            finished = true;
            Store.Handle handle = inFlight;
            inFlight = null;
            if (handle != null) handle.cancel();
            for (String path : uploaded) deleteQuietly(path);
        }

        private void deleteQuietly(String path) {
            store.delete(path, failure -> {
                if (failure != null) cleanupLog.deleteFailed(failure);
            });
        }
    }
}
