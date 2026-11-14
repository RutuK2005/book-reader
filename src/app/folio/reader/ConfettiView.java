package app.folio.reader;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

import java.util.Random;

/** A few seconds of falling paper for the moment a book is finished. Removes itself when done. */
final class ConfettiView extends View {
    private static final int COUNT = 140;
    private static final int[] COLORS = {0xFFE8A33D, 0xFFC0392B, 0xFF2F7A6B, 0xFF3B6FB5, 0xFFF4ECD8, 0xFF8E5BB5};

    private final float[] x = new float[COUNT], speed = new float[COUNT], drift = new float[COUNT],
            size = new float[COUNT], spin = new float[COUNT], delay = new float[COUNT];
    private final int[] color = new int[COUNT];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float t;

    ConfettiView(Context c) {
        super(c);
        float dp = c.getResources().getDisplayMetrics().density;
        Random r = new Random();
        for (int i = 0; i < COUNT; i++) {
            x[i] = r.nextFloat();
            speed[i] = 0.75f + r.nextFloat() * 0.7f;
            drift[i] = (r.nextFloat() - 0.5f) * 0.25f;
            size[i] = (5 + r.nextFloat() * 7) * dp;
            spin[i] = 2f + r.nextFloat() * 6f;
            delay[i] = r.nextFloat() * 0.35f;
            color[i] = COLORS[r.nextInt(COLORS.length)];
        }
    }

    void start() {
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(3200);
        a.setInterpolator(new LinearInterpolator());
        a.addUpdateListener(v -> {
            t = (float) v.getAnimatedValue();
            invalidate();
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(ConfettiView.this);
            }
        });
        a.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        for (int i = 0; i < COUNT; i++) {
            float p = (t - delay[i]) * speed[i] * 1.5f;
            if (p <= 0f) continue;
            float px = (x[i] + drift[i] * p + 0.03f * (float) Math.sin(p * 9f + i)) * w;
            float py = -size[i] + p * (h + 2 * size[i]);
            if (py > h + size[i]) continue;
            paint.setColor(color[i]);
            paint.setAlpha(t > 0.8f ? Math.round(255 * (1f - t) / 0.2f) : 255);
            c.save();
            c.translate(px, py);
            c.rotate(p * spin[i] * 180f);
            // A tumbling scrap of paper: its height pulses as it turns over.
            float flip = (float) Math.abs(Math.cos(p * spin[i] * 3f));
            c.drawRect(-size[i] / 2, -size[i] * 0.3f * flip, size[i] / 2, size[i] * 0.3f * flip, paint);
            c.restore();
        }
    }
}
