package com.dushnyj.tgproxy;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.core.content.FileProvider;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class QrCodePayloadDecoderInstrumentedTest {
    @Test
    public void generatedRelayQrRoundTripsThroughContentUri() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File exportDir = new File(context.getCacheDir(), "exports");
        assertTrue(exportDir.exists() || exportDir.mkdirs());
        File imageFile = new File(exportDir, "relay-qr-round-trip.png");
        String payload = "tgproxy://import?data=b64_dGVzdC1yZWxheQ";

        Bitmap bitmap = QrCodeBitmap.create(payload, 512);
        try (FileOutputStream output = new FileOutputStream(imageFile)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            bitmap.recycle();
        }

        try {
            Uri uri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".provider",
                    imageFile);
            assertEquals(payload, QrCodePayloadDecoder.decode(context.getContentResolver(), uri));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            imageFile.delete();
        }
    }
}
