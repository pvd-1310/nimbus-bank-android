package com.example.final_term.ui.common;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.final_term.R;

/** Smooth 12-month savings profit projection curve (matches Savings_Detail.html). */
public class SavingsCurveChartView extends View {
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();
    private final float[] points = {0.12f, 0.19f, 0.27f, 0.36f, 0.45f, 0.54f, 0.63f, 0.72f, 0.80f, 0.88f, 0.94f, 1.0f};
    private final String[] labels = {"T1", "T3", "T6", "T9", "T12"};

    public SavingsCurveChartView(Context context) {
        super(context);
        init(context);
    }

    public SavingsCurveChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public SavingsCurveChartView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2.5f));
        linePaint.setColor(ContextCompat.getColor(context, R.color.navy));
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        fillPaint.setStyle(Paint.Style.FILL);

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(ContextCompat.getColor(context, R.color.success));

        labelPaint.setColor(ContextCompat.getColor(context, R.color.ink_muted));
        labelPaint.setTextSize(dp(10));
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (int) dp(120));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float padX = dp(14);
        float topPad = dp(14);
        float bottomPad = dp(24);
        float chartW = w - padX * 2;
        float chartH = h - topPad - bottomPad;

        fillPaint.setShader(new LinearGradient(0, topPad, 0, h - bottomPad,
                0x382F3E6E, 0x052F3E6E, Shader.TileMode.CLAMP));

        linePath.reset();
        fillPath.reset();

        float prevX = 0f;
        float prevY = 0f;
        for (int i = 0; i < points.length; i++) {
            float x = padX + (chartW * i) / (points.length - 1);
            float y = topPad + chartH * (1f - points[i]);
            if (i == 0) {
                linePath.moveTo(x, y);
                fillPath.moveTo(x, h - bottomPad);
                fillPath.lineTo(x, y);
            } else {
                float midX = (prevX + x) / 2f;
                linePath.cubicTo(midX, prevY, midX, y, x, y);
                fillPath.cubicTo(midX, prevY, midX, y, x, y);
            }
            prevX = x;
            prevY = y;
        }
        fillPath.lineTo(prevX, h - bottomPad);
        fillPath.close();

        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);

        // Milestone dots (T3, T6, T9, T12)
        int[] milestones = {2, 5, 8, 11};
        for (int idx : milestones) {
            float x = padX + (chartW * idx) / (points.length - 1);
            float y = topPad + chartH * (1f - points[idx]);
            canvas.drawCircle(x, y, idx == 11 ? dp(5) : dp(3.5f), dotPaint);
        }

        for (int i = 0; i < labels.length; i++) {
            float x = padX + (chartW * i) / (labels.length - 1);
            canvas.drawText(labels[i], x, h - dp(6), labelPaint);
        }
    }
}
