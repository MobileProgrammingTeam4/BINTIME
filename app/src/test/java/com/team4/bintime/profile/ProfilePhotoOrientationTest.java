package com.team4.bintime.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** #79 리뷰 반영: API 26·27 경로의 EXIF 방향(1~8) → 회전·좌우 반전 매핑. */
public class ProfilePhotoOrientationTest {

    // EXIF 표준 값 (android.media.ExifInterface.ORIENTATION_*)
    private static final int NORMAL = 1, FLIP_H = 2, ROTATE_180 = 3, FLIP_V = 4,
            TRANSPOSE = 5, ROTATE_90 = 6, TRANSVERSE = 7, ROTATE_270 = 8, UNDEFINED = 0;

    @Test
    public void rotationOnly() {
        assertEquals(90, ProfilePhotoCompressor.rotationDegrees(ROTATE_90));
        assertEquals(180, ProfilePhotoCompressor.rotationDegrees(ROTATE_180));
        assertEquals(270, ProfilePhotoCompressor.rotationDegrees(ROTATE_270));
        assertFalse(ProfilePhotoCompressor.isMirrored(ROTATE_90));
        assertFalse(ProfilePhotoCompressor.isMirrored(ROTATE_180));
        assertFalse(ProfilePhotoCompressor.isMirrored(ROTATE_270));
    }

    @Test
    public void mirroredValues() {
        assertEquals(0, ProfilePhotoCompressor.rotationDegrees(FLIP_H));
        assertEquals(180, ProfilePhotoCompressor.rotationDegrees(FLIP_V));
        assertEquals(90, ProfilePhotoCompressor.rotationDegrees(TRANSPOSE));
        assertEquals(270, ProfilePhotoCompressor.rotationDegrees(TRANSVERSE));
        assertTrue(ProfilePhotoCompressor.isMirrored(FLIP_H));
        assertTrue(ProfilePhotoCompressor.isMirrored(FLIP_V));
        assertTrue(ProfilePhotoCompressor.isMirrored(TRANSPOSE));
        assertTrue(ProfilePhotoCompressor.isMirrored(TRANSVERSE));
    }

    @Test
    public void normalOrUnknown_noChange() {
        for (int value : new int[]{NORMAL, UNDEFINED, 99}) {
            assertEquals(0, ProfilePhotoCompressor.rotationDegrees(value));
            assertFalse(ProfilePhotoCompressor.isMirrored(value));
        }
    }
}
