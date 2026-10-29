package com.fongmi.android.tv.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.textview.MaterialTextView;

/**
 * 支持背景进度填充的集数方块：绿色百分比区域从左往右延伸，文字始终浮在最上层。
 */
public class ProgressTextView extends MaterialTextView {

    private static final int COLOR_DONE = 0xFFC8E6C9;
    private static final int COLOR_RUNNING = 0xFF4CAF50;
    private static final int COLOR_FAILED = 0xFFEF5350;
    private static final int COLOR_QUEUED = 0x334CAF50;

    private final RectF rect = new RectF();
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float progress;
    private boolean indeterminate;
    private int mode = MODE_NONE;

    public static final int MODE_NONE = 0;
    public static final int MODE_QUEUED = 1;
    public static final int MODE_RUNNING = 2;
    public static final int MODE_DONE = 3;
    public static final int MODE_FAILED = 4;

    public ProgressTextView(@NonNull Context context) {
        this(context, null);
    }

    public ProgressTextView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ProgressTextView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        progressPaint.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);
    }

    public void setState(int mode, float progress) {
        setState(mode, progress, false);
    }

    /**
     * @param indeterminate 拿不到总长度（部分 m3u8）时用描边扫描动画表示正在下载，避免进度条永远不动。
     */
    public void setState(int mode, float progress, boolean indeterminate) {
        boolean wasIndeterminate = this.indeterminate && this.mode == MODE_RUNNING && mode == MODE_RUNNING;
        this.mode = mode;
        this.indeterminate = indeterminate;
        this.progress = Math.max(0f, Math.min(1f, progress));
        if (indeterminate && mode == MODE_RUNNING) {
            // 高频重绑时不要重置扫描动画的相位，否则会不停从头跳、看起来在抖。
            if (!wasIndeterminate) startIndeterminate();
        } else {
            removeCallbacks(indeterminateTask);
        }
        if (mode == MODE_QUEUED) progressPaint.setColor(COLOR_QUEUED);
        else if (mode == MODE_FAILED) progressPaint.setColor(COLOR_FAILED);
        else progressPaint.setColor(COLOR_RUNNING);
        invalidate();
    }

    private final Runnable indeterminateTask = new Runnable() {
        @Override
        public void run() {
            progress += 0.06f;
            if (progress > 1f) progress = 0f;
            invalidate();
            postDelayed(this, 60);
        }
    };

    private void startIndeterminate() {
        progress = 0f;
        post(indeterminateTask);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(indeterminateTask);
        super.onDetachedFromWindow();
    }

    /**
     * 只刷新进度，避免重复设置颜色。
     */
    public void setProgress(float value) {
        if (indeterminate) return;
        this.progress = Math.max(0f, Math.min(1f, value));
        invalidate();
    }

    public float getProgress() {
        return progress;
    }

    public int getMode() {
        return mode;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float radius = getResources().getDisplayMetrics().density * 8;
        if (mode == MODE_DONE) {
            drawFill(canvas, radius, 1f, COLOR_DONE);
        } else if (mode == MODE_QUEUED) {
            drawFill(canvas, radius, 1f, COLOR_QUEUED);
        } else if (mode == MODE_FAILED) {
            drawFill(canvas, radius, 1f, withAlpha(COLOR_FAILED, 0x33));
        } else if (mode == MODE_RUNNING && (indeterminate || progress > 0)) {
            drawFill(canvas, radius, progress, COLOR_RUNNING);
        } else if (mode == MODE_RUNNING) {
            drawBorder(canvas, radius);
        }
        super.onDraw(canvas);
    }

    /**
     * 总长度未知时，用一层淡绿描边表示「正在下载」。
     */
    private void drawBorder(Canvas canvas, float radius) {
        rect.set(0, 0, getWidth(), getHeight());
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(getResources().getDisplayMetrics().density * 2);
        borderPaint.setColor(COLOR_RUNNING);
        canvas.drawRoundRect(rect, radius, radius, borderPaint);
    }

    private void drawFill(Canvas canvas, float radius, float ratio, int color) {
        rect.set(0, 0, getWidth() * ratio, getHeight());
        progressPaint.setColor(color);
        canvas.drawRoundRect(rect, radius, radius, progressPaint);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
