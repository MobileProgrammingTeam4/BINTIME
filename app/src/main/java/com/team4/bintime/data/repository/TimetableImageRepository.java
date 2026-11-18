package com.team4.bintime.data.repository;

import java.util.List;

/**
 * [BT-025] 시간표 이미지 업로드 저장소 계약 (콜백 + Registration).
 * <ul>
 *   <li>UID는 인자로 받지 않는다. 구현체가 현재 로그인 UID({@code AuthRepositories.get(context).getCurrentUid()})로
 *       {@link TimetableImagePaths#imagePath} 경로를 만든다.</li>
 *   <li>입력은 이미 압축·검증된 JPEG 바이트(각 {@link TimetableImagePaths#MAX_UPLOAD_BYTES} 미만)다.
 *       원본 URI·파일명은 저장소로 넘기지 않는다.</li>
 *   <li>Firebase 구현(P2)의 콜백은 메인 스레드, Mock은 호출한 스레드에서 즉시 부른다.</li>
 *   <li>이미지 바이트·경로·URI를 로그에 남기지 않는다.</li>
 * </ul>
 * 구현체: {@link MockTimetableImageRepository}(P1), {@link FirebaseTimetableImageRepository}(P2, 실제 Storage).
 */
public interface TimetableImageRepository {

    interface Registration {
        /**
         * 진행 중인 업로드 취소. 여러 번 불러도 안전하다. 취소 뒤에는 콜백을 부르지 않으며, 그 묶음에서 이미
         * 올라간 이미지는 구현체가 지운다(최선 시도).
         */
        void cancel();
    }

    interface UploadListener {
        /** index번째 이미지 진행률. total은 그 이미지 바이트 수. */
        void onProgress(int index, long sentBytes, long totalBytes);

        /** 모든 이미지 업로드 완료. paths는 index 순서(= analyzeTimetable imagePaths 순서). */
        void onSuccess(List<String> paths);

        /**
         * 실패. 이미 올라간 이미지는 구현체가 지우려고 시도한다(최선 시도). 실패 index는
         * {@link TimetableImageFailure#getIndex()}.
         */
        void onError(TimetableImageFailure failure);
    }

    interface Callback<T> {
        void onSuccess(T result);

        void onError(TimetableImageFailure failure);
    }

    /**
     * batchId 묶음으로 jpegs를 순서대로 올린다. jpegs는 1..{@link TimetableImagePaths#MAX_IMAGES}장.
     * 잘못된 batchId·개수·크기면 업로드 없이 onError.
     */
    Registration uploadBatch(String batchId, List<byte[]> jpegs, UploadListener listener);

    /**
     * 업로드한 이미지를 지운다. 본인 접두사가 아닌 경로는 건너뛴다. 이미 없으면 성공으로 본다(멱등).
     * 검수를 마치거나 취소했을 때, 선택을 바꿨을 때 부른다.
     */
    void deleteImages(List<String> paths, Callback<Void> callback);

    /**
     * [BT-025 P2] batchId 묶음의 이미지를 지운다(최선 시도). 목록 조회 없이 알려진 경로
     * {@link TimetableImagePaths#imagePath}(uid, batchId, 0..{@link TimetableImagePaths#MAX_IMAGES}-1)를 지우며,
     * 없는 파일은 성공으로 본다(멱등). 잘못된 batchId면 아무것도 지우지 않고 onError.
     * <p>
     * 분석이 끝난 뒤 이 메서드를 부를지는 E 의 답변(서버가 분석 후 이미지를 지우는지)을 기다린다. P2 에서는 호출을
     * 연결하지 않았다(docs/BT-025.md).
     */
    void deleteBatch(String batchId, Callback<Void> callback);
}
