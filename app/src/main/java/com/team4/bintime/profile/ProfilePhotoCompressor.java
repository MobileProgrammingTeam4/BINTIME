package com.team4.bintime.profile;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 갤러리에서 고른 사진을 업로드용 JPEG 로 줄인다 (BT-014).
 * 긴 변 {@link #MAX_SIDE}px 로 줄이고 다시 인코딩하므로 위치 등 EXIF 메타데이터는 남지 않는다.
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
        try (InputStream in = resolver.openInputStream(uri)) {
            return BitmapFactory.decodeStream(in, null, options);
        }
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
