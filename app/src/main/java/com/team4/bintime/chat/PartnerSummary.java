package com.team4.bintime.chat;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.team4.bintime.R;
import com.team4.bintime.model.MatchFilter;

/**
 * 후기·신고 화면 상단의 상대 카드(view_partner_summary.xml) 데이터.
 * Intent 로 넘기기 위해 Bundle 로 바꿀 수 있다. 채팅 밖(프로필 등)에서도 직접 만들어 쓸 수 있다.
 */
public final class PartnerSummary {

    private static final String KEY_UID = "uid";
    private static final String KEY_NAME = "name";
    private static final String KEY_INFO = "info";
    private static final String KEY_HAS_PHOTO = "hasPhoto";
    private static final String KEY_TIME = "time";
    private static final String KEY_DURATION = "duration";

    public final String uid, name;
    /** 학과·학년·성별 요약. 없으면 null */
    @Nullable public final String info;
    public final boolean hasPhoto;
    /** 예: 오늘 14:00 ~ 15:30. 없으면 시간 박스를 숨긴다. */
    @Nullable public final String timeLabel;
    /** 예: 1시간 30분 겹침 */
    @Nullable public final String durationLabel;

    public PartnerSummary(String uid, String name, @Nullable String info, boolean hasPhoto,
                          @Nullable String timeLabel, @Nullable String durationLabel) {
        this.uid = uid;
        this.name = name;
        this.info = info;
        this.hasPhoto = hasPhoto;
        this.timeLabel = timeLabel;
        this.durationLabel = durationLabel;
    }

    /** 채팅방의 확정 시간 스냅샷으로 만든다. */
    public static PartnerSummary from(Context context, ChatRoom room, long nowMillis) {
        String time = ChatTimeFormatter.timeRange(room, nowMillis,
                context.getString(R.string.chat_today), context.getString(R.string.chat_yesterday));
        String duration = context.getString(R.string.chat_overlap_duration,
                MatchFilter.formatDuration(room.endMinute - room.startMinute));
        return new PartnerSummary(room.otherUid, room.otherName, room.otherInfo, room.otherHasPhoto,
                time, duration);
    }

    public Bundle toBundle() {
        Bundle b = new Bundle();
        b.putString(KEY_UID, uid);
        b.putString(KEY_NAME, name);
        b.putString(KEY_INFO, info);
        b.putBoolean(KEY_HAS_PHOTO, hasPhoto);
        b.putString(KEY_TIME, timeLabel);
        b.putString(KEY_DURATION, durationLabel);
        return b;
    }

    /** uid 나 이름이 없으면 null */
    @Nullable
    public static PartnerSummary fromBundle(@Nullable Bundle b) {
        if (b == null || b.getString(KEY_UID) == null || b.getString(KEY_NAME) == null) return null;
        return new PartnerSummary(b.getString(KEY_UID), b.getString(KEY_NAME), b.getString(KEY_INFO),
                b.getBoolean(KEY_HAS_PHOTO), b.getString(KEY_TIME), b.getString(KEY_DURATION));
    }

    /** view_partner_summary.xml 채우기. 사진이 없으면 채팅 목록처럼 이름을 표시한다. */
    public void bind(View card) {
        ImageView photo = card.findViewById(R.id.partnerPhoto);
        TextView initials = card.findViewById(R.id.partnerInitials);
        photo.setVisibility(hasPhoto ? View.VISIBLE : View.GONE);
        initials.setVisibility(hasPhoto ? View.GONE : View.VISIBLE);
        initials.setText(name);
        ((TextView) card.findViewById(R.id.partnerName)).setText(name);
        TextView infoView = card.findViewById(R.id.partnerInfo);
        infoView.setVisibility(info == null ? View.GONE : View.VISIBLE);
        infoView.setText(info);
        card.findViewById(R.id.partnerTimeBox).setVisibility(timeLabel == null ? View.GONE : View.VISIBLE);
        ((TextView) card.findViewById(R.id.partnerTime)).setText(timeLabel);
        ((TextView) card.findViewById(R.id.partnerDuration)).setText(durationLabel);
    }
}
