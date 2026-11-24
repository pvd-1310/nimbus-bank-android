package com.example.final_term.ui.common;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.final_term.R;

public class SignatureBarChartView extends View {
    private final Paint barGlassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barSelectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();

    private final String[] days = {"T2", "T3", "T4", "T5", "T6", "T7", "CN"};
    private final float[] heights = new float[7];
    private int selectedIndex = -1;

    public SignatureBarChartView(Context context) {
        super(context);
        init(context);
    }

    public SignatureBarChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public SignatureBarChartView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        barGlassPaint.setStyle(Paint.Style.FILL);
        barGlassPaint.setColor(0x55FFFFFF); // frosted glass bar

        barSelectedPaint.setStyle(Paint.Style.FILL);
        barSelectedPaint.setColor(ContextCompat.getColor(context, R.color.navy));

        labelPaint.setColor(ContextCompat.getColor(context, R.color.ink_muted));
        labelPaint.setTextSize(dp(11));
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    public void setSelectedIndex(int index) {
        if (index >= 0 && index < days.length) {
            this.selectedIndex = index;
            invalidate();
        }
    }

    public void setValues(float[] values, int selected) {
        if (values != null) {
            for (int i = 0; i < heights.length; i++) {
                heights[i] = i < values.length ? Math.max(0f, Math.min(1f, values[i])) : 0f;
            }
        }
        selectedIndex = selected >= 0 && selected < days.length ? selected : -1;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = (int) dp(120);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float bottomLabelSpace = dp(24);
        float chartHeight = h - bottomLabelSpace;
        float barWidth = dp(14);
        float r = barWidth / 2f;

        int count = days.length;
        float gap = (w - (barWidth * count)) / (count + 1);

        for (int i = 0; i < count; i++) {
            float left = gap + i * (barWidth + gap);
            float right = left + barWidth;
            float barH = chartHeight * heights[i];
            float top = chartHeight - barH;
            float bottom = chartHeight;

            barRect.set(left, top, right, bottom);
            boolean isSelected = (i == selectedIndex);
            canvas.drawRoundRect(barRect, r, r, isSelected ? barSelectedPaint : barGlassPaint);

            // Draw label below
            labelPaint.setColor(ContextCompat.getColor(getContext(), isSelected ? R.color.navy : R.color.ink_muted));
            labelPaint.setFakeBoldText(isSelected);
            canvas.drawText(days[i], left + r, h - dp(6), labelPaint);
        }
    }
}
