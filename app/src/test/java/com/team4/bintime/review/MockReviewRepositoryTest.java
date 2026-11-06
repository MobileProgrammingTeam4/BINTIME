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

    private static final class Received implements ReviewRepository.ReceivedCallback {
        ReviewRepository.Visibility visibility;
        Review review;
        int errors;

        @Override
        public void onResult(ReviewRepository.Visibility visibility, Review review) {
            this.visibility = visibility;
            this.review = review;
        }

        @Override
        public void onError() {
            errors++;
        }
    }

    private static final class Submit implements ReviewRepository.SubmitCallback {
        int successes, errors;

        @Override
        public void onSuccess() {
            successes++;
        }

        @Override
        public void onError() {
            errors++;
        }
    }

    @Before
    public void setUp() {
        repository = new MockReviewRepository((task, delay) -> tasks.add(task), () -> 1_000L);
    }

    private Received received(String matchId) {
        Received result = new Received();
        repository.loadReceivedReview(matchId, result);
        List<Runnable> current = new ArrayList<>(tasks);
        tasks.clear();
        for (Runnable task : current) task.run();
        return result;
    }

    private boolean written(String matchId) {
        boolean[] result = new boolean[1];
        repository.loadMyReviewStatus(matchId, new ReviewRepository.StatusCallback() {
            @Override
            public void onResult(boolean myReviewWritten) {
                result[0] = myReviewWritten;
            }

            @Override
            public void onError() {
            }
        });
        return result[0];
    }

    @Test
    public void partnerReview_isLockedUntilIWriteMine() {
        assertFalse(written("match-seoyeon"));
        Received before = received("match-seoyeon");
        assertEquals(ReviewRepository.Visibility.LOCKED, before.visibility);
        assertNull(before.review);

        Submit submit = new Submit();
        repository.submitReview("match-seoyeon", "mock-seoyeon", Satisfaction.GOOD,
                EnumSet.of(ReviewKeyword.KIND_MANNER), "  감사했어요  ", submit);

        assertEquals(1, submit.successes);
        assertTrue(written("match-seoyeon"));
        Received after = received("match-seoyeon");
        assertEquals(ReviewRepository.Visibility.VISIBLE, after.visibility);
        assertEquals("mock-seoyeon", after.review.authorUid);
    }

    @Test
    public void partnerNotWrittenYet_isNotYetAfterIWrite() {
        repository.submitReview("match-junho", "mock-junho", Satisfaction.BAD,
                EnumSet.noneOf(ReviewKeyword.class), null, new Submit());
        Received result = received("match-junho");
        assertEquals(ReviewRepository.Visibility.NOT_YET, result.visibility);
        assertNull(result.review);
    }

    @Test
    public void seededReview_keepsKeywordOrderAndComment() {
        Received result = received("match-jieun");
        assertEquals(ReviewRepository.Visibility.VISIBLE, result.visibility);
        assertEquals(Arrays.asList(ReviewKeyword.ON_TIME, ReviewKeyword.QUICK_REPLY,
                ReviewKeyword.CLEAN_PARTING, ReviewKeyword.FOCUSED_STUDY), result.review.keywords);
        assertEquals("정말 재밌었던 하루였어요", result.review.comment);
    }

    @Test
    public void submit_rejectsMissingSatisfactionLongCommentAndDuplicate() {
        Submit submit = new Submit();
        repository.submitReview("match-junho", "mock-junho", null, EnumSet.noneOf(ReviewKeyword.class), null, submit);
        char[] tooLong = new char[ReviewRepository.COMMENT_MAX_LENGTH + 1];
        Arrays.fill(tooLong, '가');
        repository.submitReview("match-junho", "mock-junho", Satisfaction.GOOD,
                EnumSet.noneOf(ReviewKeyword.class), new String(tooLong), submit);
        repository.submitReview("match-jieun", "mock-jieun", Satisfaction.GOOD,
                EnumSet.noneOf(ReviewKeyword.class), null, submit);
        assertEquals(3, submit.errors);
        assertEquals(0, submit.successes);
        assertFalse(written("match-junho"));
    }
}
