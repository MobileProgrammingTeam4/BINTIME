package com.team4.bintime.review;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

public class MockReviewRepositoryTest {

    private final List<Runnable> tasks = new ArrayList<>();
    private MockReviewRepository repository;

    private static final class Submit implements ReviewRepository.SubmitCallback {
        int successes;
        final List<ReviewFailure> failures = new ArrayList<>();

        @Override
        public void onSuccess() {
            successes++;
        }

        @Override
        public void onError(ReviewFailure failure) {
            failures.add(failure);
        }
    }

    @Before
    public void setUp() {
        repository = new MockReviewRepository((task, delay) -> tasks.add(task), () -> 1_000L);
    }

    private MatchReview load(String matchId) {
        MatchReview[] result = new MatchReview[1];
        repository.loadMatchReview(matchId, new ReviewRepository.MatchReviewCallback() {
            @Override
            public void onResult(MatchReview review) {
                result[0] = review;
            }

            @Override
            public void onError(ReviewFailure failure) {
            }
        });
        List<Runnable> current = new ArrayList<>(tasks);
        tasks.clear();
        for (Runnable task : current) task.run();
        return result[0];
    }

    @Test
    public void partnerReview_isLockedUntilIWriteMine() {
        MatchReview before = load("match-seoyeon");
        assertFalse(before.myReviewWritten());
        assertTrue(before.canWrite);
        assertEquals(ReviewRepository.Visibility.LOCKED, before.visibility);
        assertNull(before.received);

        Submit submit = new Submit();
        repository.submitReview("match-seoyeon", Satisfaction.GOOD, EnumSet.of(ReviewKeyword.KIND_MANNER), "  감사했어요  ", submit);

        assertEquals(1, submit.successes);
        MatchReview after = load("match-seoyeon");
        assertTrue(after.myReviewWritten());
        assertFalse(after.canWrite);
        assertEquals("감사했어요", after.mine.comment);
        assertEquals("mock-seoyeon", after.mine.targetUid);
        assertEquals(ReviewRepository.Visibility.VISIBLE, after.visibility);
        assertEquals("mock-seoyeon", after.received.authorUid);
    }

    @Test
    public void partnerNotWrittenYet_isNotYetAfterIWrite() {
        repository.submitReview("match-junho", Satisfaction.BAD, EnumSet.noneOf(ReviewKeyword.class), null, new Submit());
        MatchReview result = load("match-junho");
        assertEquals(ReviewRepository.Visibility.NOT_YET, result.visibility);
        assertNull(result.received);
        assertNull(result.mine.comment);
    }

    @Test
    public void seededReview_keepsKeywordOrderAndComment() {
        MatchReview result = load("match-jieun");
        assertEquals(ReviewRepository.Visibility.VISIBLE, result.visibility);
        assertEquals(Arrays.asList(ReviewKeyword.ON_TIME, ReviewKeyword.QUICK_REPLY,
                ReviewKeyword.CLEAN_PARTING, ReviewKeyword.FOCUSED_STUDY), result.received.keywords);
        assertEquals("정말 재밌었던 하루였어요", result.received.comment);
    }

    @Test
    public void submit_rejectsMissingSatisfactionLongCommentAndDuplicate() {
        Submit submit = new Submit();
        repository.submitReview("match-junho", null, EnumSet.noneOf(ReviewKeyword.class), null, submit);
        char[] tooLong = new char[ReviewRepository.COMMENT_MAX_LENGTH + 1];
        Arrays.fill(tooLong, '가');
        repository.submitReview("match-junho", Satisfaction.GOOD, EnumSet.noneOf(ReviewKeyword.class), new String(tooLong), submit);
        repository.submitReview("match-jieun", Satisfaction.GOOD, EnumSet.noneOf(ReviewKeyword.class), null, submit);
        assertEquals(Arrays.asList(ReviewFailure.GENERIC, ReviewFailure.GENERIC, ReviewFailure.ALREADY_REVIEWED), submit.failures);
        assertEquals(0, submit.successes);
        assertFalse(load("match-junho").myReviewWritten());
    }

    @Test
    public void partnerUid_followsMockMatchIds() {
        assertEquals("mock-jieun", MockReviewRepository.partnerOf("match-jieun"));
        assertEquals("mock-partner", MockReviewRepository.partnerOf("other"));
    }
}
