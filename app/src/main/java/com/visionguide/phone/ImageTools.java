package com.visionguide.phone;

import android.graphics.Bitmap;
import android.graphics.Matrix;

import androidx.camera.core.ImageProxy;

import java.nio.ByteBuffer;

final class ImageTools {
    private ImageTools() {
    }

    static Bitmap bitmapFromRgbaImage(ImageProxy image) {
        int width = image.getWidth();
        int height = image.getHeight();
        ImageProxy.PlaneProxy plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        if (pixelStride == 4 && rowStride == width * 4) {
            bitmap.copyPixelsFromBuffer(buffer);
            return rotate(bitmap, image.getImageInfo().getRotationDegrees());
        }

        int[] pixels = new int[width * height];
        byte[] row = new byte[rowStride];
        for (int y = 0; y < height; y++) {
            buffer.position(y * rowStride);
            buffer.get(row, 0, Math.min(rowStride, buffer.remaining()));
            for (int x = 0; x < width; x++) {
                int offset = x * pixelStride;
                int r = row[offset] & 0xFF;
                int g = row[offset + 1] & 0xFF;
                int b = row[offset + 2] & 0xFF;
                int a = row[offset + 3] & 0xFF;
                pixels[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return rotate(bitmap, image.getImageInfo().getRotationDegrees());
    }

    private static Bitmap rotate(Bitmap bitmap, int degrees) {
        if (degrees == 0) {
            return bitmap;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) {
            bitmap.recycle();
        }
        return rotated;
    }
}
