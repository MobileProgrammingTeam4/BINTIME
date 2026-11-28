package com.team4.bintime.report;

import android.content.Context;

import com.team4.bintime.auth.AuthRepositories;
import com.team4.bintime.profile.BlockRepositories;

/** 신고 저장소 선택 (BT-051). 인증 저장소와 같은 기준으로 Firebase/Mock 을 고른다. */
public final class ReportRepositories {

    private ReportRepositories() {}

    public static ReportRepository get(Context context) {
        return AuthRepositories.isFirebaseEnabled(context)
                ? FirebaseReportRepository.get()
                : MockReportRepository.shared(BlockRepositories.get(context));
    }
}
