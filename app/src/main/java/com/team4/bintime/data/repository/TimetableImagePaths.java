package com.team4.bintime.data.repository;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * [BT-025] 시간표 이미지 Cloud Storage 경로·제한의 단일 출처.
 * <pre>
 * timetableImages/{uid}/{batchId}/{index}.jpg     index = 0..MAX_IMAGES-1 (선택 순서)
 * </pre>
 * <ul>
 *   <li>한 번의 "시간표 분석하기"가 묶음(batch) 하나다. batchId는 앱이 만든 UUID이며, 서버 analyzeTimetable 의
 *       {@code imagePaths} 순서 = index 순서 = 인식 결과의 {@code sourceImageIndex}다.</li>
 *   <li>서버(functions/src/ai/tasks/analyzeTimetable.ts)는 {@code timetableImages/{uid}/} 접두사와 ".." 부재만 확인한다.
 *       Storage Rules(B)는 docs/BT-025.md 「B 요청」을 따른다. 경로·제한을 바꾸면 그 문서·Rules·서버와 함께 바꾼다.</li>
 *   <li>여기 값은 서버 계약과 같아야 한다: 최대 5장(서버 MAX_IMAGES), 경로 300자 이하.</li>
 * </ul>
 */
public final class TimetableImagePaths {

    public static final String ROOT = "timetableImages";
    /** 서버 analyzeTimetable 입력 상한(MAX_IMAGES)과 같다. */
    public static final int MAX_IMAGES = 5;
    /** 업로드 JPEG 상한(미만). Storage Rules 제안의 {@code request.resource.size < 5MB}와 같다. */
    public static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;
    public static final String CONTENT_TYPE = "image/jpeg";
    /** 서버 입력 스키마의 경로 최대 길이. */
    public static final int MAX_PATH_LENGTH = 300;

    private static final Pattern BATCH_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    private TimetableImagePaths() {
    }

    public static String newBatchId() {
        return UUID.randomUUID().toString();
    }

    public static boolean isValidBatchId(String batchId) {
        return batchId != null && BATCH_ID.matcher(batchId).matches();
    }

    /** 사용자 접두사 {@code timetableImages/{uid}/}. */
    public static String userPrefix(String uid) {
        requireUid(uid);
        return ROOT + "/" + uid + "/";
    }

    /** 묶음 안 index번째 이미지 경로. 잘못된 uid·batchId·index면 IllegalArgumentException. */
    public static String imagePath(String uid, String batchId, int index) {
        if (!isValidBatchId(batchId)) throw new IllegalArgumentException("invalid batchId");
        if (index < 0 || index >= MAX_IMAGES) throw new IllegalArgumentException("index out of range");
        String path = userPrefix(uid) + batchId + "/" + index + ".jpg";
        if (path.length() > MAX_PATH_LENGTH) throw new IllegalArgumentException("path too long");
        return path;
    }

    /** 경로가 uid 본인 접두사 안에 있고 상위 이동("..")이 없는지. 서버 authorize와 같은 조건이다. */
    public static boolean isOwnedBy(String uid, String path) {
        if (uid == null || path == null || !CourseValidator.isValidDocumentId(uid)) return false;
        return path.startsWith(ROOT + "/" + uid + "/") && !path.contains("..");
    }

    private static void requireUid(String uid) {
        // UID는 Storage 경로 한 단계가 되므로 문서 ID 규칙('/' 없음, "."·".." 아님 등)으로 검사한다.
        if (!CourseValidator.isValidDocumentId(uid)) throw new IllegalArgumentException("invalid uid");
    }
}
