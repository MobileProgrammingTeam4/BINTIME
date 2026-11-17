package com.team4.bintime.timetable;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;

import com.team4.bintime.data.repository.TimetableImageFailure.Code;
import com.team4.bintime.data.repository.TimetableImagePaths;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * [BT-025] 시간표 이미지를 업로드용 JPEG로 만든다.
 * <ul>
 *   <li>원본 형식·크기·디코딩·해상도를 {@link TimetableImageValidator}로 검사한다.</li>
 *   <li>긴 변을 {@link #MAX_SIDE}px로 줄이고 다시 인코딩한다. EXIF 방향은 픽셀에 반영하고, 위치 등 EXIF
 *       메타데이터는 남기지 않는다(프로필 사진 압축기와 같은 방식, 글자 판독을 위해 해상도만 크게).</li>
 *   <li>결과가 {@link TimetableImagePaths#MAX_UPLOAD_BYTES} 미만이 될 때까지 품질을 {@link #QUALITIES} 순서로
 *       낮춘다. 그래도 넘으면 {@link Code#COMPRESSED_TOO_LARGE}.</li>
 * </ul>
 * 디코딩이 무거우므로 UI 스레드가 아닌 곳에서 부른다. URI·파일명·이미지 내용을 로그에 남기지 않는다.
 */
final class TimetableImageCompressor {

    static final int MAX_SIDE = 2048;
    static final int[] QUALITIES = {90, 80, 70, 60, 50, 40};

    /** 준비 결과: jpeg 또는 failure 중 하나만 null이 아니다. */
    static final class Result {
        final byte[] jpeg;
        final Code failure;

        private Result(byte[] jpeg, Code failure) {
            this.jpeg = jpeg;
            this.failure = failure;
        }

        static Result ok(byte[] jpeg) {
            return new Result(jpeg, null);
        }

        static Result fail(Code failure) {
            return new Result(null, failure);
        }
    }

    interface Encoder {
        byte[] encode(int quality) throws IOException;
    }

    private TimetableImageCompressor() {
    }

    /**
     * 품질을 낮추며 limitExclusive 미만이 되는 첫 결과를 돌려준다. 모두 넘으면 null.
     * Android 의존성이 없어 단위 테스트로 검증한다.
     */
    static byte[] encodeUnderLimit(Encoder encoder, long limitExclusive) throws IOException {
        for (int quality : QUALITIES) {
            byte[] bytes = encoder.encode(quality);
            if (bytes != null && bytes.length > 0 && bytes.length < limitExclusive) return bytes;
        }
        return null;
    }

    /** 긴 변이 MAX_SIDE 의 2배 미만이 될 때까지 2의 거듭제곱으로 줄인다. */
    static int sampleSize(int width, int height) {
        int sample = 1;
        int longSide = Math.max(width, height);
        while (longSide / (sample * 2) >= MAX_SIDE) sample *= 2;
        return sample;
    }

    static Result prepare(ContentResolver resolver, Uri uri) {
        Code type = TimetableImageValidator.checkType(safeType(resolver, uri));
        if (type != null) return Result.fail(type);
        Code size = TimetableImageValidator.checkSourceSize(sourceBytes(resolver, uri));
        if (size != null) return Result.fail(size);
        Bitmap bitmap = null;
        try {
            int[] original = new int[2];
            bitmap = decode(resolver, uri, original);
            if (bitmap == null) return Result.fail(Code.CORRUPT);
            Code dimensions = TimetableImageValidator.checkDimensions(original[0], original[1]);
            if (dimensions != null) return Result.fail(dimensions);
            Bitmap scaled = scaleDown(bitmap);
            if (scaled != bitmap) {
                bitmap.recycle();
                bitmap = scaled;
            }
            Bitmap source = bitmap;
            byte[] jpeg = encodeUnderLimit(quality -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (!source.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null;
                return out.toByteArray();
            }, TimetableImagePaths.MAX_UPLOAD_BYTES);
            if (jpeg == null) return Result.fail(Code.COMPRESSED_TOO_LARGE);
            return Result.ok(jpeg);
        } catch (FileNotFoundException | SecurityException e) {
            return Result.fail(Code.READ_FAILED);
        } catch (IOException | RuntimeException e) {
            // ImageDecoder.DecodeException 등: 손상되었거나 이미지가 아니다.
            return Result.fail(Code.CORRUPT);
        } catch (OutOfMemoryError e) {
            return Result.fail(Code.SOURCE_TOO_LARGE);
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    private static String safeType(ContentResolver resolver, Uri uri) {
        try {
            return resolver.getType(uri);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 원본 바이트 수. 모르면 0. */
    private static long sourceBytes(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return Math.max(0, cursor.getLong(0));
        } catch (RuntimeException ignored) {
            // 크기는 선택 정보다. 실제 디코딩으로 판단한다.
        }
        return 0;
    }

    /** original[0..1]에 원본 너비·높이를 채운다. */
    private static Bitmap decode(ContentResolver resolver, Uri uri, int[] original) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder 는 EXIF 회전을 반영하고 HEIC 도 읽는다.
            ImageDecoder.Source source = ImageDecoder.createSource(resolver, uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                int width = info.getSize().getWidth();
                int height = info.getSize().getHeight();
                original[0] = width;
                original[1] = height;
                int sample = sampleSize(width, height);
                if (sample > 1) decoder.setTargetSize(Math.max(1, width / sample), Math.max(1, height / sample));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new FileNotFoundException();
            BitmapFactory.decodeStream(in, null, bounds);
        }
        original[0] = bounds.outWidth;
        original[1] = bounds.outHeight;
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        Bitmap bitmap;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new FileNotFoundException();
            bitmap = BitmapFactory.decodeStream(in, null, options);
        }
        if (bitmap == null) return null;
        return applyExifOrientation(bitmap, readExifOrientation(resolver, uri));
    }

    private static int readExifOrientation(ContentResolver resolver, Uri uri) {
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) return ExifInterface.ORIENTATION_NORMAL;
            return new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException | RuntimeException e) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /** API 26·27 경로: EXIF 방향(회전·반전)을 픽셀에 반영한다. */
    private static Bitmap applyExifOrientation(Bitmap bitmap, int orientation) {
        int degrees;
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
            case ExifInterface.ORIENTATION_TRANSPOSE:
                degrees = 90;
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                degrees = 180;
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
            case ExifInterface.ORIENTATION_TRANSVERSE:
                degrees = 270;
                break;
            default:
                degrees = 0;
        }
        boolean mirror = orientation == ExifInterface.ORIENTATION_FLIP_HORIZONTAL
                || orientation == ExifInterface.ORIENTATION_FLIP_VERTICAL
                || orientation == ExifInterface.ORIENTATION_TRANSPOSE
                || orientation == ExifInterface.ORIENTATION_TRANSVERSE;
        if (degrees == 0 && !mirror) return bitmap;
        Matrix matrix = new Matrix();
        matrix.setRotate(degrees);
        if (mirror) matrix.postScale(-1, 1);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) bitmap.recycle();
        return rotated;
    }

    private static Bitmap scaleDown(Bitmap bitmap) {
        int longSide = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (longSide <= MAX_SIDE) return bitmap;
        float ratio = (float) MAX_SIDE / longSide;
        return Bitmap.createScaledBitmap(bitmap,
                Math.max(1, Math.round(bitmap.getWidth() * ratio)),
                Math.max(1, Math.round(bitmap.getHeight() * ratio)), true);
    }
}
