package com.veloranova.editor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import androidx.annotation.OptIn;
import androidx.media3.common.OverlaySettings;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.StaticOverlaySettings;

@OptIn(markerClass = UnstableApi.class)
final class MotionCaption {
    final String text;
    final int from;
    final int to;
    private final Bitmap label;

    MotionCaption(String text, int from, int to) {
        this.text = text;
        this.from = from;
        this.to = to;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(44);
        float width = paint.measureText(text);
        if (width > 824) {
            paint.setTextSize(44 * 824 / width);
        }
        int labelWidth = Math.max(1, (int) Math.ceil(paint.measureText(text)) + 44);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        int height = (int) Math.ceil(metrics.bottom - metrics.top) + 20;
        label = Bitmap.createBitmap(labelWidth, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(label);
        paint.setColor(0xc8191722);
        canvas.drawRoundRect(0, 0, labelWidth, height, 14, 14, paint);
        paint.setColor(Color.WHITE);
        canvas.drawText(text, 22, 10 - metrics.top, paint);
    }

    private float scale(int width, int height) {
        return Math.min(width / 960f, height * 0.19f / label.getHeight());
    }

    private float verticalFraction(float fraction) {
        float t = Math.max(0, Math.min(1, fraction));
        float eased = t * t * (3 - 2 * t);
        return 0.16f + 0.34f * (from + (to - from) * eased);
    }

    void drawPreview(Canvas canvas, int width, int height, float fraction, Paint filter) {
        float scale = scale(width, height);
        float w = label.getWidth() * scale;
        float h = label.getHeight() * scale;
        float x = (width - w) / 2;
        float y = height * verticalFraction(fraction) - h / 2;
        canvas.drawBitmap(label, null, new RectF(x, y, x + w, y + h), filter);
    }

    BitmapOverlay exportOverlay(long durationUs) {
        return new BitmapOverlay() {
            private float outputScale;

            @Override
            public void configure(Size videoSize) {
                super.configure(videoSize);
                outputScale = scale(videoSize.getWidth(), videoSize.getHeight());
            }

            @Override
            public Bitmap getBitmap(long timeUs) {
                return label;
            }

            @Override
            public OverlaySettings getOverlaySettings(long timeUs) {
                float y = verticalFraction((float) timeUs / durationUs);
                return new StaticOverlaySettings.Builder()
                        .setScale(outputScale, outputScale)
                        .setBackgroundFrameAnchor(0, 1 - 2 * y)
                        .build();
            }
        };
    }
}
