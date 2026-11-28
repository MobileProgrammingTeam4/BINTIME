package com.team4.bintime.report;

import com.team4.bintime.profile.BlockRepository;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * BT-051 Mock 신고 저장소(google-services.json 없는 빌드·테스트). 서버와 같은 중복 정책(신고자 1명 기준 대상+매칭당 1건)을
 * 앱 프로세스 동안만 흉내 낸다. 실제 접수가 아니므로 Mock 성공을 운영 접수로 보지 않는다. 콜백은 바로 온다.
 */
public final class MockReportRepository implements ReportRepository {

    private static MockReportRepository shared;

    public static synchronized MockReportRepository shared(BlockRepository blocks) {
        if (shared == null) shared = new MockReportRepository(blocks);
        return shared;
    }

    private final BlockRepository blocks;
    private final Set<String> submitted = new HashSet<>();

    public MockReportRepository(BlockRepository blocks) {
        this.blocks = blocks;
    }

    @Override
    public synchronized void submit(ReportForm form, Callback callback) {
        if (form.validate() != null) {
            callback.onError("신고 내용을 확인해 주세요.");
            return;
        }
        boolean already = !submitted.add(Arrays.asList(form.reportedUid, form.matchId).toString());
        if (!form.alsoBlock) {
            callback.onSuccess(new Result(already, false));
            return;
        }
        blocks.block(form.reportedUid, new BlockRepository.Callback<Void>() {
            @Override public void onSuccess(Void result) { callback.onSuccess(new Result(already, true)); }
            @Override public void onError(String message) { callback.onSuccess(new Result(already, false)); }
        });
    }

    /** 테스트용: 접수 기록을 비운다. */
    public synchronized void resetForTest() {
        submitted.clear();
    }
}
