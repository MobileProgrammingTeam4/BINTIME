package com.team4.bintime.review;

import android.app.Activity;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.team4.bintime.R;
import com.team4.bintime.chat.PartnerSummary;
import com.team4.bintime.model.MatchFilter;
import com.team4.bintime.profile.ProfileRepositories;
import com.team4.bintime.profile.ProfileRepository;
import com.team4.bintime.profile.UserProfile;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 채팅 밖(완료된 매칭 탭·알림 목록, D 화면)에서 개별 matchId 의 후기를 여는 진입점(BT-044).
 * 서버(getMatchReview)로 내 후기·작성 가능 여부를 확인한 뒤 내가 썼으면 조회, 쓸 수 있으면 작성 화면을 연다.
 * 후기 대상이 아니면(진행 중·시작 전 종료·탈퇴 종료·상대 탈퇴) 안내만 띄운다. 자격은 앱이 판단하지 않는다.
 *
 * <pre>
 * ReviewEntry.open(activity, matchId, null);      // 상대 정보가 없으면 publicProfiles 에서 읽는다
 * ReviewEntry.open(activity, matchId, partner);   // 이미 가진 상대 카드 정보를 그대로 쓴다
 * </pre>
 */
public final class ReviewEntry {

    enum Destination { DETAIL, WRITE, NOT_AVAILABLE }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREA);
    /** 같은 약속을 연속으로 눌러 화면이 두 번 열리지 않게 한다. 메인 스레드에서만 쓴다. */
    private static final Set<String> pending = new HashSet<>();

    private ReviewEntry() {}

    public static void open(Activity activity, String matchId, @Nullable PartnerSummary partner) {
        if (matchId == null || matchId.isEmpty()) {
            toast(activity, R.string.review_missing);
            return;
        }
        if (!pending.add(matchId)) return;
        ReviewRepositories.get(activity).loadMatchReview(matchId, new ReviewRepository.MatchReviewCallback() {
            @Override
            public void onResult(MatchReview review) {
                Destination destination = decide(review);
                if (destination == Destination.NOT_AVAILABLE) {
                    pending.remove(matchId);
                    toast(activity, R.string.review_not_eligible);
                    return;
                }
                if (partner != null) {
                    pending.remove(matchId);
                    start(activity, destination, matchId, partner);
                    return;
                }
                loadPartner(activity, review, loaded -> {
                    pending.remove(matchId);
                    start(activity, destination, matchId, loaded);
                });
            }

            @Override
            public void onError(ReviewFailure failure) {
                pending.remove(matchId);
                toast(activity, failure == ReviewFailure.PERMISSION ? R.string.review_not_eligible : R.string.review_open_failed);
            }
        });
    }

    /** 내가 썼으면 조회, 서버가 작성 가능하다고 하면 작성, 아니면 열지 않는다. */
    static Destination decide(MatchReview review) {
        if (review.myReviewWritten()) return Destination.DETAIL;
        return review.canWrite ? Destination.WRITE : Destination.NOT_AVAILABLE;
    }

    /** 예: 11월 27일 (금) 10:00 ~ 11:30. 스냅샷이 없거나 틀리면 null(시간 박스 숨김). */
    @Nullable
    static String timeLabel(@Nullable String meetingDate, int startMinute, int endMinute) {
        if (meetingDate == null || startMinute < 0 || endMinute <= startMinute || endMinute > 1440) return null;
        try {
            return LocalDate.parse(meetingDate).format(DATE) + " " + clock(startMinute) + " ~ " + clock(endMinute);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String clock(int minute) {
        return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60);
    }

    private interface PartnerCallback {
        void onReady(PartnerSummary partner);
    }

    /** B 의 ProfileRepository.getPublicProfile 을 호출만 한다. 실패하면 기본 이름으로 연다. */
    private static void loadPartner(Activity activity, MatchReview review, PartnerCallback callback) {
        String fallback = activity.getString(R.string.chat_partner_fallback_name);
        String time = timeLabel(review.meetingDate, review.startMinute, review.endMinute);
        String duration = time == null ? null : activity.getString(R.string.chat_overlap_duration,
                MatchFilter.formatDuration(review.endMinute - review.startMinute));
        ProfileRepositories.get(activity).getPublicProfile(review.partnerUid, new ProfileRepository.Callback<UserProfile>() {
            @Override
            public void onSuccess(UserProfile profile) {
                String name = profile == null ? null : trimmed(profile.getNickname());
                String info = profile == null ? null : trimmed(profile.getFormattedSummary());
                boolean hasPhoto = profile != null && trimmed(profile.getPhotoPath()) != null;
                callback.onReady(new PartnerSummary(review.partnerUid, name == null ? fallback : name, info, hasPhoto, time, duration));
            }

            @Override
            public void onError(String message) {
                callback.onReady(new PartnerSummary(review.partnerUid, fallback, null, false, time, duration));
            }
        });
    }

    private static void start(Activity activity, Destination destination, String matchId, PartnerSummary partner) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        activity.startActivity(destination == Destination.DETAIL
                ? ReviewDetailActivity.intent(activity, matchId, partner)
                : ReviewWriteActivity.intent(activity, matchId, partner));
    }

    private static void toast(Activity activity, @StringRes int message) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    @Nullable
    private static String trimmed(@Nullable String value) {
        if (value == null) return null;
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}
