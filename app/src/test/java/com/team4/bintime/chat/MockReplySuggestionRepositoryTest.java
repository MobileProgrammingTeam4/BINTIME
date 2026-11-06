package com.team4.bintime.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class MockReplySuggestionRepositoryTest {

    private final List<Runnable> tasks = new ArrayList<>();
    private boolean failing;
    private MockReplySuggestionRepository repository;

    private static final class Result implements ReplySuggestionRepository.Callback {
        List<String> replies;
        int errors;

        @Override
        public void onSuccess(List<String> replies) {
            this.replies = replies;
        }

        @Override
        public void onError() {
            errors++;
        }
    }

    @Before
    public void setUp() {
        repository = new MockReplySuggestionRepository((task, delay) -> tasks.add(task), () -> failing);
    }

    private void runAll() {
        List<Runnable> current = new ArrayList<>(tasks);
        tasks.clear();
        for (Runnable task : current) task.run();
    }

    @Test
    public void returnsThreeNonBlankReplies_onlyAfterDelay() {
        Result result = new Result();
        repository.requestReplies("match-jimin", result);
        assertNull(result.replies);

        runAll();

        assertEquals(ReplySuggestionRepository.REPLY_COUNT, result.replies.size());
        for (String reply : result.replies) assertFalse(reply.trim().isEmpty());
    }

    @Test
    public void nextRequest_returnsDifferentSet() {
        Result first = new Result();
        Result second = new Result();
        repository.requestReplies("match-jimin", first);
        repository.requestReplies("match-jimin", second);
        runAll();
        assertNotEquals(first.replies, second.replies);
    }

    @Test
    public void failure_reportsError() {
        failing = true;
        Result result = new Result();
        repository.requestReplies("match-jimin", result);
        repository.requestReplies(null, result);
        runAll();
        assertEquals(2, result.errors);
        assertNull(result.replies);
    }

    @Test
    public void canceledRequest_deliversNothing() {
        Result result = new Result();
        repository.requestReplies("match-jimin", result).cancel();
        runAll();
        assertNull(result.replies);
        assertEquals(0, result.errors);
    }
}
