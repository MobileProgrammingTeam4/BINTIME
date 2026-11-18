package com.team4.bintime.data.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** [BT-025] Storage 경로 계약: timetableImages/{uid}/{batchId}/{index}.jpg, 서버 authorize와 같은 소유 판정. */
public class TimetableImagePathsTest {

    private static final String UID = "uid-me";
    private static final String BATCH = "0f8fad5b-d9cb-469f-a165-70867728950e";

    @Test
    public void imagePath_followsContract() {
        assertEquals("timetableImages/uid-me/" + BATCH + "/0.jpg", TimetableImagePaths.imagePath(UID, BATCH, 0));
        assertEquals("timetableImages/uid-me/" + BATCH + "/4.jpg", TimetableImagePaths.imagePath(UID, BATCH, 4));
        assertEquals("timetableImages/uid-me/", TimetableImagePaths.userPrefix(UID));
    }

    @Test
    public void limitsMatchServerContract() {
        assertEquals(5, TimetableImagePaths.MAX_IMAGES);
        assertEquals(5L * 1024 * 1024, TimetableImagePaths.MAX_UPLOAD_BYTES);
        assertEquals("image/jpeg", TimetableImagePaths.CONTENT_TYPE);
    }

    @Test
    public void imagePath_rejectsIndexOutsideBatch() {
        assertThrows(IllegalArgumentException.class, () -> TimetableImagePaths.imagePath(UID, BATCH, -1));
        assertThrows(IllegalArgumentException.class, () -> TimetableImagePaths.imagePath(UID, BATCH, 5));
    }

    @Test
    public void imagePath_rejectsUnsafeUidAndBatchId() {
        for (String uid : new String[]{null, "", " ", "a/b", ".", "..", "__x__"}) {
            assertThrows(IllegalArgumentException.class, () -> TimetableImagePaths.imagePath(uid, BATCH, 0));
        }
        for (String batch : new String[]{null, "", "..", "a/b", "a.b", "a b", repeat('a', 65)}) {
            assertThrows(IllegalArgumentException.class, () -> TimetableImagePaths.imagePath(UID, batch, 0));
        }
    }

    @Test
    public void newBatchId_isValidAndUnique() {
        String a = TimetableImagePaths.newBatchId();
        String b = TimetableImagePaths.newBatchId();
        assertTrue(TimetableImagePaths.isValidBatchId(a));
        assertNotEquals(a, b);
    }

    @Test
    public void isOwnedBy_onlyCallerPrefixWithoutParentTraversal() {
        assertTrue(TimetableImagePaths.isOwnedBy(UID, TimetableImagePaths.imagePath(UID, BATCH, 1)));
        assertFalse(TimetableImagePaths.isOwnedBy(UID, "timetableImages/uid-other/" + BATCH + "/0.jpg"));
        assertFalse(TimetableImagePaths.isOwnedBy(UID, "timetableImages/uid-me/../uid-other/a.jpg"));
        // 접두사가 같은 다른 UID(uid-me2)는 본인 경로가 아니다.
        assertFalse(TimetableImagePaths.isOwnedBy(UID, "timetableImages/uid-me2/" + BATCH + "/0.jpg"));
        assertFalse(TimetableImagePaths.isOwnedBy(UID, "profileImages/uid-me/profile.jpg"));
        assertFalse(TimetableImagePaths.isOwnedBy(null, "timetableImages/uid-me/a.jpg"));
        assertFalse(TimetableImagePaths.isOwnedBy(UID, null));
    }

    private static String repeat(char c, int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) s.append(c);
        return s.toString();
    }
}
