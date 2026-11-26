package com.team4.bintime.timetable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.team4.bintime.data.repository.MockTimetableImageRepositoryAccess;
import com.team4.bintime.data.repository.TimetableImageFailure;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

/** [BT-048] 검수 화면 원본 보기용 사본의 수명: 업로드할 때 만들고, 검수 종료·선택 변경·다시 분석 때 지운다. */
public class TimetableImageUploaderLocalCopyTest {

    private static final class FakeCopy implements TimetableImageUploader.LocalCopy {
        final List<String> saved = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        boolean fail;

        @Override
        public List<String> save(String batchId, List<byte[]> jpegs) {
            if (fail) throw new IllegalStateException("disk full");
            saved.add(batchId);
            List<String> paths = new ArrayList<>();
            for (int i = 0; i < jpegs.size(); i++) paths.add("/cache/" + batchId + "/" + i + ".jpg");
            return paths;
        }

        @Override
        public void delete(String batchId) {
            deleted.add(batchId);
        }
    }

    private final Queue<Runnable> background = new ArrayDeque<>();
    private final Executor direct = Runnable::run;
    private final MockTimetableImageRepositoryAccess repo = new MockTimetableImageRepositoryAccess("uid-me");
    private final List<TimetableUploadState> states = new ArrayList<>();
    private final FakeCopy copy = new FakeCopy();
    private final TimetableImageUploader uploader =
            new TimetableImageUploader(repo.repository(), background::add, direct, states::add, copy);

    private static TimetableImageUploader.Preparer allOk() {
        return index -> TimetableImageCompressor.Result.ok(new byte[100 * (index + 1)]);
    }

    private void runBackground() {
        while (!background.isEmpty()) background.poll().run();
    }

    @Test
    public void upload_savesLocalCopyInOrder_andReviewFinishDeletesItOnce() {
        uploader.start(2, allOk());
        runBackground();

        assertEquals(1, copy.saved.size());
        List<String> local = uploader.getLocalPaths();
        assertEquals(2, local.size());
        assertTrue(local.get(1).endsWith("/1.jpg"));

        uploader.deleteUploadedBatch();
        assertEquals(copy.saved, copy.deleted);
        assertTrue(uploader.getLocalPaths().isEmpty());

        uploader.deleteUploadedBatch();
        uploader.discard();
        assertEquals(1, copy.deleted.size());
    }

    @Test
    public void discard_deletesLocalCopy() {
        uploader.start(1, allOk());
        runBackground();
        uploader.discard();
        assertEquals(copy.saved, copy.deleted);
        assertTrue(uploader.getLocalPaths().isEmpty());
    }

    @Test
    public void retryWithNewBatch_deletesPreviousCopy() {
        uploader.start(1, allOk());
        runBackground();
        String first = copy.saved.get(0);
        uploader.start(1, allOk());
        assertEquals(Collections.singletonList(first), copy.deleted);
        runBackground();
        assertEquals(2, copy.saved.size());
        assertEquals(1, uploader.getLocalPaths().size());
    }

    @Test
    public void selectionChangedWhilePreparing_staleCopyIsDeleted() {
        uploader.start(1, allOk());
        uploader.discard();
        runBackground();
        assertEquals(copy.saved, copy.deleted);
        assertTrue(uploader.getLocalPaths().isEmpty());
    }

    @Test
    public void copyFailure_hidesOriginalsButUploadContinues() {
        copy.fail = true;
        uploader.start(1, allOk());
        runBackground();
        assertEquals(TimetableUploadState.Phase.UPLOADED, states.get(states.size() - 1).phase);
        assertTrue(uploader.getLocalPaths().isEmpty());
    }

    @Test
    public void invalidImages_doNotCreateCopy() {
        uploader.start(2, index -> index == 0
                ? TimetableImageCompressor.Result.ok(new byte[10])
                : TimetableImageCompressor.Result.fail(TimetableImageFailure.Code.CORRUPT));
        runBackground();
        assertTrue(copy.saved.isEmpty());
        assertTrue(uploader.getLocalPaths().isEmpty());
    }
}
