package com.dushnyj.tgproxy;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.io.InputStream;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Bounded decoder for QR images received through a temporary Android content URI grant. */
final class QrCodePayloadDecoder {
    private static final int MAX_SOURCE_SIDE = 8192;
    private static final int MAX_DECODE_SIDE = 2048;
    private static final long MAX_PIXELS = 32L * 1024L * 1024L;

    private QrCodePayloadDecoder() {}

    static String decode(ContentResolver resolver, Uri uri) throws Exception {
        if (resolver == null || uri == null || !"content".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("content URI required");
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IllegalArgumentException("image unavailable");
            BitmapFactory.decodeStream(input, null, bounds);
        }
        int width = bounds.outWidth;
        int height = bounds.outHeight;
        if (width <= 0 || height <= 0 || width > MAX_SOURCE_SIDE || height > MAX_SOURCE_SIDE
                || (long) width * height > MAX_PIXELS) {
            throw new IllegalArgumentException("invalid or oversized image");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        int sample = 1;
        while (width / sample > MAX_DECODE_SIDE || height / sample > MAX_DECODE_SIDE) sample *= 2;
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IllegalArgumentException("image unavailable");
            bitmap = BitmapFactory.decodeStream(input, null, options);
        }
        if (bitmap == null) throw new IllegalArgumentException("invalid image");
        try {
            int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
            bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0,
                    bitmap.getWidth(), bitmap.getHeight());
            BinaryBitmap binary = new BinaryBitmap(new HybridBinarizer(
                    new RGBLuminanceSource(bitmap.getWidth(), bitmap.getHeight(), pixels)));
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.POSSIBLE_FORMATS,
                    Collections.singletonList(com.google.zxing.BarcodeFormat.QR_CODE));
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            Result result = new MultiFormatReader().decode(binary, hints);
            return result == null || result.getText() == null ? "" : result.getText().trim();
        } finally {
            bitmap.recycle();
        }
    }
}
