package com.team4.bintime.profile;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 갤러리에서 고른 사진을 업로드용 JPEG 로 줄인다 (BT-014).
 * 긴 변 {@link #MAX_SIDE}px 로 줄이고 다시 인코딩하므로 위치 등 EXIF 메타데이터는 남지 않는다.
 * 메타데이터를 버리기 전에 EXIF 방향(회전·반전)은 픽셀에 반영한다. API 28 이상은 ImageDecoder 가,
 * API 26·27 은 {@link #applyExifOrientation} 이 처리한다 (#79 리뷰 반영).
 * 디코딩이 무거우므로 UI 스레드가 아닌 곳에서 호출한다.
 */
final class ProfilePhotoCompressor {

    static final int MAX_SIDE = 512;
    private static final int JPEG_QUALITY = 85;

    private ProfilePhotoCompressor() {}

    static byte[] compress(ContentResolver resolver, Uri uri) throws IOException {
        Bitmap bitmap = decode(resolver, uri);
        if (bitmap == null) throw new IOException("decode failed");
        Bitmap scaled = scaleDown(bitmap);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
        if (scaled != bitmap) scaled.recycle();
        bitmap.recycle();
        return out.toByteArray();
    }

    private static Bitmap decode(ContentResolver resolver, Uri uri) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder 는 EXIF 회전을 반영한다.
            ImageDecoder.Source source = ImageDecoder.createSource(resolver, uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                int width = info.getSize().getWidth();
                int height = info.getSize().getHeight();
                int sample = sampleSize(width, height);
                if (sample > 1) decoder.setTargetSize(width / sample, height / sample);
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        Bitmap bitmap;
        try (InputStream in = resolver.openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(in, null, options);
        }
        if (bitmap == null) return null;
        return applyExifOrientation(bitmap, readExifOrientation(resolver, uri));
    }

    /** EXIF 방향 값. 읽을 수 없으면 NORMAL 로 본다(사진 자체는 그대로 쓸 수 있다). */
    private static int readExifOrientation(ContentResolver resolver, Uri uri) {
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) return ExifInterface.ORIENTATION_NORMAL;
            return new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException e) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /** API 26·27 경로: EXIF 방향을 픽셀에 반영한다. 보정이 필요 없으면 원본을 그대로 돌려준다. */
    private static Bitmap applyExifOrientation(Bitmap bitmap, int orientation) {
        int degrees = rotationDegrees(orientation);
        boolean mirror = isMirrored(orientation);
        if (degrees == 0 && !mirror) return bitmap;
        Matrix matrix = new Matrix();
        matrix.setRotate(degrees);
        if (mirror) matrix.postScale(-1, 1);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) bitmap.recycle();
        return rotated;
    }

    /**
     * EXIF 방향 → 시계 방향 회전 각도. 반전이 있는 값은 이 각도로 회전한 뒤 좌우 반전한다.
     * (FLIP_VERTICAL = 180° + 좌우 반전, TRANSPOSE = 90° + 좌우 반전, TRANSVERSE = 270° + 좌우 반전)
     */
    static int rotationDegrees(int orientation) {
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
            case ExifInterface.ORIENTATION_TRANSPOSE:
                return 90;
            case ExifInterface.ORIENTATION_ROTATE_180:
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                return 180;
            case ExifInterface.ORIENTATION_ROTATE_270:
            case ExifInterface.ORIENTATION_TRANSVERSE:
                return 270;
            default:
                return 0;
        }
    }

    /** 회전 후 좌우 반전이 필요한 EXIF 방향인지. */
    static boolean isMirrored(int orientation) {
        return orientation == ExifInterface.ORIENTATION_FLIP_HORIZONTAL
                || orientation == ExifInterface.ORIENTATION_FLIP_VERTICAL
                || orientation == ExifInterface.ORIENTATION_TRANSPOSE
                || orientation == ExifInterface.ORIENTATION_TRANSVERSE;
    }

    /** 긴 변이 MAX_SIDE 의 2배 미만이 될 때까지 2의 거듭제곱으로 줄인다. */
    static int sampleSize(int width, int height) {
        int sample = 1;
        int longSide = Math.max(width, height);
        while (longSide / (sample * 2) >= MAX_SIDE) sample *= 2;
        return sample;
    }

    private static Bitmap scaleDown(Bitmap bitmap) {
        int longSide = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (longSide <= MAX_SIDE) return bitmap;
        float ratio = (float) MAX_SIDE / longSide;
        return Bitmap.createScaledBitmap(bitmap,
                Math.round(bitmap.getWidth() * ratio), Math.round(bitmap.getHeight() * ratio), true);
    }
}
