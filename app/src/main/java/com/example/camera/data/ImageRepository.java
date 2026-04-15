package com.example.camera.data;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ImageRepository {
    private static final String TAG = "ImageRepository";
    private static final String RELATIVE_PATH = "DCIM/Camera";
    private final ContentResolver resolver;

    public ImageRepository(Context context) {
        this.resolver = context.getContentResolver();
    }

    public Uri saveJpegBytes(byte[] data, String displayName) {
        Uri uri = insertImage(displayName);
        if (uri == null) return null;

        try (OutputStream os = resolver.openOutputStream(uri)) {
            if (os == null) {
                resolver.delete(uri, null, null);
                return null;
            }
            os.write(data);
            os.flush();
            return uri;
        } catch (IOException e) {
            resolver.delete(uri, null, null);
            Log.e(TAG, "saveJpegBytes failed", e);
            return null;
        }
    }

    public Uri saveJpegBitmap(Bitmap bitmap, String displayName) {
        Uri uri = insertImage(displayName);
        if (uri == null) return null;

        try (OutputStream os = resolver.openOutputStream(uri)) {
            if (os == null) {
                resolver.delete(uri, null, null);
                return null;
            }
            boolean ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 100, os);
            os.flush();
            if (!ok) {
                resolver.delete(uri, null, null);
                return null;
            }
            return uri;
        } catch (IOException e) {
            resolver.delete(uri, null, null);
            Log.e(TAG, "saveJpegBitmap failed", e);
            return null;
        }
    }

    public String readExifBrightness(Uri imageUri) {
        if (imageUri == null) return "N/A";
        try (InputStream inputStream = resolver.openInputStream(imageUri)) {
            if (inputStream == null) return "读取失败";
            ExifInterface exif = new ExifInterface(inputStream);
            String val = exif.getAttribute(ExifInterface.TAG_BRIGHTNESS_VALUE);
            return val != null ? val : "N/A";
        } catch (IOException e) {
            return "读取失败";
        }
    }

    private Uri insertImage(String displayName) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH);
        return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
    }
}
