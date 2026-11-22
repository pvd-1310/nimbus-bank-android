package com.example.final_term.ui.common;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.final_term.R;

public class HatchedProgressBar extends View {
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hatchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final RectF bounds = new RectF();
    private final RectF progressBounds = new RectF();
    private float progress = 0.64f; // 0.0 to 1.0

    public HatchedProgressBar(Context context) {
        super(context);
        init(context);
    }

    public HatchedProgressBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public HatchedProgressBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        trackPaint.setStyle(Paint.Style.FILL);
        trackPaint.setColor(0x40FFFFFF); // semi-transparent glass track

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1));
        borderPaint.setColor(0x60FFFFFF);

        hatchPaint.setStyle(Paint.Style.STROKE);
        hatchPaint.setStrokeWidth(dp(2));
        hatchPaint.setColor(ContextCompat.getColor(context, R.color.navy));

        textPaint.setColor(ContextCompat.getColor(context, R.color.white));
        textPaint.setTextSize(dp(10));
        textPaint.setFakeBoldText(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    public void setProgress(float fraction) {
        this.progress = Math.max(0f, Math.min(1f, fraction));
        invalidate();
    }

    public float getProgress() {
        return progress;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = (int) dp(20);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float r = h / 2f;

        bounds.set(0, 0, w, h);
        // Draw track
        canvas.drawRoundRect(bounds, r, r, trackPaint);
        canvas.drawRoundRect(bounds, r, r, borderPaint);

        if (progress <= 0f) return;

        float fillWidth = Math.max(h, w * progress);
        progressBounds.set(0, 0, fillWidth, h);

        clipPath.reset();
        clipPath.addRoundRect(progressBounds, r, r, Path.Direction.CW);

        canvas.save();
        canvas.clipPath(clipPath);

        // Draw solid navy tinted backdrop for fill
        fillBgPaint.setColor(0x262F3E6E);
        canvas.drawRoundRect(progressBounds, r, r, fillBgPaint);

        // Draw diagonal hatch pattern (45° stripes: 2dp stroke, 5dp step)
        float step = dp(5);
        float diagMax = fillWidth + h;
        for (float x = -h; x < diagMax; x += step) {
            canvas.drawLine(x, h, x + h, 0, hatchPaint);
        }

        canvas.restore();
    }
}
