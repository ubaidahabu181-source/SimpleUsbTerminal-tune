
package de.kai_morich.simple_usb_terminal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class GaugeView extends View {
    private float value = 0;
    private float min = 0;
    private float max = 100;
    private String title = "";
    private String display = "";
    private Paint bgPaint, arcPaint, textPaint, titlePaint;
    private RectF oval;

    public GaugeView(Context c, AttributeSet attrs) { super(c, attrs); init(); }
    public GaugeView(Context c) { super(c); init(); }

    private void init() {
        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.parseColor("#222222"));
        bgPaint.setStyle(Paint.Style.STROKE);
        bgPaint.setStrokeWidth(18f);
        arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arcPaint.setColor(Color.parseColor("#00E676"));
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(18f);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(48f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(Color.parseColor("#AAAAAA"));
        titlePaint.setTextSize(32f);
        titlePaint.setTextAlign(Paint.Align.CENTER);
        oval = new RectF();
    }

    public void setValue(float v) { this.value = v; invalidate(); }

    public void setValue(int val, String title, String display, int min, int max) {
        this.value = val; this.title = title; this.display = display; this.min = min; this.max = max;
        if (title.contains("RPM")) arcPaint.setColor(Color.parseColor("#00E676"));
        else if (title.contains("MAP")) arcPaint.setColor(Color.parseColor("#2979FF"));
        else if (title.contains("ADV")) arcPaint.setColor(Color.parseColor("#FF9100"));
        invalidate();
    }

    public void setValue(float val, String title, String display, float min, float max) {
        setValue((int)val, title, display, (int)min, (int)max);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(); int h = getHeight();
        float size = Math.min(w, h) * 0.85f;
        float cx = w / 2f; float cy = h / 2f + 20f;
        oval.set(cx - size/2, cy - size/2, cx + size/2, cy + size/2);
        canvas.drawArc(oval, 135, 270, false, bgPaint);
        float pct = 0; if (max > min) pct = (value - min) / (max - min);
        pct = Math.max(0, Math.min(1, pct));
        canvas.drawArc(oval, 135, 270 * pct, false, arcPaint);
        if (display != null && !display.isEmpty()) canvas.drawText(display, cx, cy + 20, textPaint);
        else canvas.drawText(String.valueOf((int)value), cx, cy + 20, textPaint);
        if (title != null && !title.isEmpty()) canvas.drawText(title, cx, cy + 80, titlePaint);
    }
}
