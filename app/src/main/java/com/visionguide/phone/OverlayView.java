package com.visionguide.phone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class OverlayView extends View {
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint movingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Detection> detections = new ArrayList<>();
    private GuidanceEngine guidanceEngine;
    private int imageWidth = 1;
    private int imageHeight = 1;

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public OverlayView(Context context) {
        super(context);
        init();
    }

    private void init() {
        gridPaint.setColor(Color.argb(170, 80, 95, 110));
        gridPaint.setStrokeWidth(2f);

        movingPaint.setColor(Color.rgb(255, 170, 70));
        movingPaint.setStyle(Paint.Style.STROKE);
        movingPaint.setStrokeWidth(5f);

        stillPaint.setColor(Color.rgb(70, 215, 170));
        stillPaint.setStyle(Paint.Style.STROKE);
        stillPaint.setStrokeWidth(5f);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(36f);
        textPaint.setShadowLayer(5f, 0f, 0f, Color.BLACK);
    }

    void update(List<Detection> newDetections, GuidanceEngine engine, int sourceWidth, int sourceHeight) {
        detections.clear();
        detections.addAll(newDetections);
        guidanceEngine = engine;
        imageWidth = Math.max(1, sourceWidth);
        imageHeight = Math.max(1, sourceHeight);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        canvas.drawLine(w / 3f, 0, w / 3f, h, gridPaint);
        canvas.drawLine(w * 2f / 3f, 0, w * 2f / 3f, h, gridPaint);
        canvas.drawLine(0, h / 3f, w, h / 3f, gridPaint);
        canvas.drawLine(0, h * 2f / 3f, w, h * 2f / 3f, gridPaint);

        float scaleX = w / imageWidth;
        float scaleY = h / imageHeight;
        for (Detection detection : detections) {
            boolean moving = guidanceEngine != null && guidanceEngine.isMoving(detection, imageWidth, imageHeight);
            Paint boxPaint = moving ? movingPaint : stillPaint;
            RectF mapped = new RectF(
                    detection.box.left * scaleX,
                    detection.box.top * scaleY,
                    detection.box.right * scaleX,
                    detection.box.bottom * scaleY
            );
            canvas.drawRect(mapped, boxPaint);
            String cell = GuidanceEngine.gridCellFor(detection, imageWidth, imageHeight);
            String text = String.format(Locale.US, "%s %.0f%% | %s | %s",
                    detection.label, detection.confidence * 100f, cell, moving ? "moving" : "still");
            canvas.drawText(text, mapped.left, Math.max(42f, mapped.top - 10f), textPaint);
        }
    }
}
