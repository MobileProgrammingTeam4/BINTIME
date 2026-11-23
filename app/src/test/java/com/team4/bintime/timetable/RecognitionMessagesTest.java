package com.team4.bintime.timetable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.team4.bintime.R;
import com.team4.bintime.data.recognition.RecognitionFailure;
import com.team4.bintime.data.recognition.RecognitionFailure.Reason;

import org.junit.Test;

/** [BT-026 P4] 인식 실패 문구: 서버가 이미지 문제로 거부(INVALID_INPUT)하면 이미지 확인 안내, 재시도는 서버 값. */
public class RecognitionMessagesTest {

    @Test
    public void invalidInput_hasImageMessage_notGeneric() {
        assertEquals(R.string.timetable_recognition_error_invalid_input, RecognitionMessages.of(Reason.INVALID_INPUT));
        assertEquals(R.string.timetable_recognition_error_internal, RecognitionMessages.of(Reason.INTERNAL));
    }

    @Test
    public void everyReasonHasMessage_andRetryFollowsServer() {
        for (Reason reason : Reason.values()) RecognitionMessages.of(reason);
        assertFalse(RecognitionMessages.showsRetry(RecognitionFailure.fromServer("INVALID_INPUT", null, null)));
        assertTrue(RecognitionMessages.showsRetry(RecognitionFailure.fromServer("INVALID_INPUT", true, null)));
        assertFalse(RecognitionMessages.showsRetry(RecognitionFailure.fromServer("AI_NOT_IMPLEMENTED", null, null)));
        assertTrue(RecognitionMessages.offersManualInput(RecognitionFailure.of(Reason.AI_NOT_IMPLEMENTED)));
    }
}
