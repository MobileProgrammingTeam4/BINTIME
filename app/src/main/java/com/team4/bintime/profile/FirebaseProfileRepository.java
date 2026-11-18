package com.team4.bintime.profile;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageException;
import com.google.firebase.storage.StorageMetadata;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Firestore·Cloud Storage 프로필 저장소 (BT-014).
 * 모든 쓰기는 FirebaseAuth 의 현재 UID 기준이며, 다른 UID 의 프로필 저장 요청은 거부한다.
 * 서버 측 접근 제한은 firestore.rules / storage.rules 가 담당한다.
 */
public final class FirebaseProfileRepository implements ProfileRepository {

    /** 압축 후 업로드하므로 넉넉한 상한. Storage Rules 의 5MB 제한보다 작다. */
    private static final long MAX_PHOTO_BYTES = 2L * 1024 * 1024;

    private static FirebaseProfileRepository instance;

    public static synchronized FirebaseProfileRepository get() {
        if (instance == null) instance = new FirebaseProfileRepository();
        return instance;
    }

    private final FirebaseAuth auth = FirebaseAuth.getInstance();
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final FirebaseStorage storage = FirebaseStorage.getInstance();
    /** AI 추천은 BT-041 등 후속 이슈 범위라 기존 목업 데이터를 그대로 쓴다. */
    private final MockProfileRepository suggestions = new MockProfileRepository();

    /** 다른 사용자가 같은 경로에 사진을 바꿨을 때 반영되기까지의 최대 시간. */
    static final long PHOTO_CACHE_TTL_MILLIS = 5L * 60 * 1000;
    /** 사진 캐시 용량: 앱 최대 힙의 1/16 (512px 사진 1장 ≈ 1MB). */
    private static final long PHOTO_CACHE_BYTES = Runtime.getRuntime().maxMemory() / 16;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 싱글턴 저장소와 수명이 같다(앱 프로세스 동안 유지). */
    private final ExecutorService photoDecoder = Executors.newSingleThreadExecutor();
    private final PhotoLoader<Bitmap> photoLoader = new PhotoLoader<>(
            (path, cb) -> storage.getReference(path).getBytes(MAX_PHOTO_BYTES).addOnCompleteListener(task -> {
                if (task.isSuccessful() && task.getResult() != null) cb.onSuccess(task.getResult());
                else cb.onError(message(task.getException()));
            }),
            new PhotoLoader.Decoder<Bitmap>() {
                @Override public Bitmap decode(byte[] bytes) {
                    return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                }

                @Override public long weight(Bitmap value) {
                    return value.getByteCount();
                }
            },
            new PhotoMemoryCache<>(PHOTO_CACHE_BYTES, PHOTO_CACHE_TTL_MILLIS, SystemClock::elapsedRealtime),
            photoDecoder,
            mainHandler::post);

    /**
     * BT-030: 저장 중 연결이 끊기면 사진 업로드가 기본 10분 동안 재시도해 화면이 묶인다.
     * 이 시간이 지나면 ERROR_RETRY_LIMIT_EXCEEDED → "네트워크 연결을 확인해 주세요"로 끝낸다.
     */
    static final long STORAGE_RETRY_MILLIS = 20_000L;

    private FirebaseProfileRepository() {
        storage.setMaxUploadRetryTimeMillis(STORAGE_RETRY_MILLIS);
        storage.setMaxDownloadRetryTimeMillis(STORAGE_RETRY_MILLIS);
    }

    @Override
    public void getProfile(String uid, Callback<UserProfile> callback) {
        if (uid == null) {
            fail(callback, "로그인이 필요해요.");
            return;
        }
        db.collection(ProfileDocuments.USERS).document(uid).get().addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                fail(callback, message(task.getException()));
                return;
            }
            DocumentSnapshot snapshot = task.getResult();
            if (callback == null) return;
            callback.onSuccess(snapshot != null && snapshot.exists()
                    ? ProfileDocuments.fromDocument(uid, snapshot.getData())
                    : null);
        });
    }

    @Override
    public void getPublicProfile(String uid, Callback<UserProfile> callback) {
        if (uid == null || uid.isEmpty()) {
            fail(callback, "사용자 정보를 찾을 수 없어요.");
            return;
        }
        if (auth.getCurrentUser() == null) {
            // Rules 상 로그인 사용자만 공개 프로필을 읽을 수 있다.
            fail(callback, "로그인이 필요해요.");
            return;
        }
        db.collection(ProfileDocuments.PUBLIC_PROFILES).document(uid).get().addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                fail(callback, message(task.getException()));
                return;
            }
            DocumentSnapshot snapshot = task.getResult();
            if (callback == null) return;
            callback.onSuccess(snapshot != null && snapshot.exists()
                    ? ProfileDocuments.fromPublicDocument(uid, snapshot.getData())
                    : null);
        });
    }

    @Override
    public void saveProfile(UserProfile profile, Callback<Void> callback) {
        ProfileValidator.Error error = ProfileValidator.validate(profile);
        if (error != null) {
            fail(callback, error.getMessage());
            return;
        }
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            fail(callback, "로그인이 필요해요.");
            return;
        }
        String uid = user.getUid();
        if (profile.getUid() != null && !uid.equals(profile.getUid())) {
            // 다른 계정 프로필이 섞이지 않도록 본인 문서만 저장한다.
            fail(callback, "다른 계정의 프로필은 저장할 수 없어요.");
            return;
        }

        UserProfile toSave = profile.copy();
        toSave.setUid(uid);
        // 이메일은 화면 입력값이 아니라 인증된 계정 정보를 쓴다.
        if (user.getEmail() != null) toSave.setEmail(user.getEmail());
        toSave.setNickname(toSave.getNickname().trim());
        toSave.setDepartment(toSave.getDepartment().trim());
        if (toSave.getBio() != null) toSave.setBio(toSave.getBio().trim());

        Map<String, Object> userDoc = ProfileDocuments.toUserDocument(toSave);
        userDoc.put(ProfileDocuments.F_UPDATED_AT, FieldValue.serverTimestamp());
        Map<String, Object> publicDoc = ProfileDocuments.toPublicDocument(toSave);
        publicDoc.put(ProfileDocuments.F_UPDATED_AT, FieldValue.serverTimestamp());

        WriteBatch batch = db.batch();
        batch.set(db.collection(ProfileDocuments.USERS).document(uid), userDoc, SetOptions.merge());
        batch.set(db.collection(ProfileDocuments.PUBLIC_PROFILES).document(uid), publicDoc, SetOptions.merge());
        batch.commit().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                if (callback != null) callback.onSuccess(null);
            } else {
                fail(callback, message(task.getException()));
            }
        });
    }

    /** BT-030: 로그아웃 시 사진 캐시를 비운다(다음 계정이 이전 계정에서 받은 사진을 캐시로 보지 않게). */
    @Override
    public void clearSessionCaches() {
        photoLoader.invalidateAll();
    }

    @Override
    public void hasCompletedProfile(String uid, Callback<Boolean> callback) {
        if (uid == null) {
            if (callback != null) callback.onSuccess(false);
            return;
        }
        db.collection(ProfileDocuments.USERS).document(uid).get().addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                fail(callback, message(task.getException()));
                return;
            }
            DocumentSnapshot snapshot = task.getResult();
            if (callback != null) {
                callback.onSuccess(snapshot != null && snapshot.exists()
                        && ProfileDocuments.isCompleted(snapshot.getData()));
            }
        });
    }

    @Override
    public void uploadProfilePhoto(String uid, byte[] jpegBytes, Callback<String> callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null || uid == null || !uid.equals(user.getUid())) {
            fail(callback, "로그인이 필요해요.");
            return;
        }
        if (jpegBytes == null || jpegBytes.length == 0) {
            fail(callback, "사진을 불러오지 못했어요.");
            return;
        }
        String path = ProfileDocuments.photoPath(uid);
        StorageMetadata metadata = new StorageMetadata.Builder().setContentType("image/jpeg").build();
        storage.getReference(path).putBytes(jpegBytes, metadata).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                // 같은 경로에 덮어썼으므로 이전 사진이 캐시에서 나오지 않게 한다.
                photoLoader.invalidate(path);
                if (callback != null) callback.onSuccess(path);
            } else {
                fail(callback, message(task.getException()));
            }
        });
    }

    /**
     * 캐시 → (같은 경로 요청 합치기) → Storage 다운로드 → 백그라운드 디코딩 순서로 불러온다({@link PhotoLoader}).
     * 결과는 메인 스레드로 온다. 다른 사용자가 사진을 바꾸면 캐시 유효 시간({@link #PHOTO_CACHE_TTL_MILLIS})이
     * 지난 뒤 반영된다. 내 사진은 업로드 직후 바로 반영된다.
     */
    @Override
    public void loadProfilePhoto(String photoPath, Callback<Bitmap> callback) {
        if (photoPath == null || photoPath.isEmpty()) {
            fail(callback, "사진이 없어요.");
            return;
        }
        photoLoader.load(photoPath, callback);
    }

    @Override
    public void generateAiBio(String timetableContext, Callback<String> callback) {
        suggestions.generateAiBio(timetableContext, callback);
    }

    @Override
    public List<String> getAiRecommendedTags() {
        return suggestions.getAiRecommendedTags();
    }

    @Override
    public List<String> getDefaultTags() {
        return Arrays.asList("스터디", "카페", "산책", "밥", "전공스터디");
    }

    private static String message(Exception e) {
        if (e instanceof FirebaseNetworkException) return "네트워크 연결을 확인해 주세요.";
        if (e instanceof FirebaseFirestoreException) {
            FirebaseFirestoreException.Code code = ((FirebaseFirestoreException) e).getCode();
            if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED) return "접근 권한이 없어요.";
            if (code == FirebaseFirestoreException.Code.UNAVAILABLE) return "네트워크 연결을 확인해 주세요.";
        }
        if (e instanceof StorageException) {
            int code = ((StorageException) e).getErrorCode();
            if (code == StorageException.ERROR_NOT_AUTHORIZED) return "접근 권한이 없어요.";
            if (code == StorageException.ERROR_OBJECT_NOT_FOUND) return "사진을 찾을 수 없어요.";
            if (code == StorageException.ERROR_RETRY_LIMIT_EXCEEDED) return "네트워크 연결을 확인해 주세요.";
        }
        return "프로필을 처리하지 못했어요. 다시 시도해 주세요.";
    }

    private static <T> void fail(Callback<T> callback, String message) {
        if (callback != null) callback.onError(message);
    }
}
