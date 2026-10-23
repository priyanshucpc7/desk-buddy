package com.deskbuddy.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The buddy's face, drawn with plain Canvas so it runs happily on Android 5.
 * Same shapes and moods as the browser version, just hand-rolled.
 */
public class FaceView extends View {

    interface Listener {
        void onPoke(float x, float y);
        void onPet(float amount);
    }

    private static final int M_HAPPY = 0, M_CURIOUS = 1, M_SLEEPY = 2, M_SLEEPING = 3,
            M_HUNGRY = 4, M_ANNOYED = 5, M_DIZZY = 6, M_LOVE = 7, M_STARTLED = 8,
            M_BORED = 9, M_EXCITED = 10;

    private static final class MoodStyle {
        float eye = 1f, pupil = 1f, blush = .55f;
        int mouth = 0, brow = 0, glow = 0xFFFFB47A, body = 0xFFFFE6CF;
        String name = "happy";
    }

    private final MoodStyle[] moods = new MoodStyle[11];

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Random rnd = new Random();

    private final List<Fx> fx = new ArrayList<Fx>();
    private float density;

    private int mood = M_HAPPY;
    private String moodName = "happy";
    private String tempMood = null;
    private long tempUntil = 0;

    private float cx, cy, rx, ry;
    private long lastFrame = 0;
    private long blinkAt = 0;
    private float blinkAmt = 0;
    private float eyeX = 0, eyeY = 0;
    private float squash = 1f, offX = 0, breathT = 0;

    private float touchX = -1, touchY = -1;
    private boolean touching = false;

    private long downAt = 0;
    private float downX = 0, downY = 0, moveAmount = 0;
    private long lastPetAt = 0;

    private Listener listener;

    public FaceView(Context c) { this(c, null); }

    public FaceView(Context c, AttributeSet attrs) {
        super(c, attrs);
        density = getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        buildMoods();
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextAlign(Paint.Align.CENTER);
        blinkAt = SystemClock.uptimeMillis() + 1800;
    }

    private void buildMoods() {
        MoodStyle s;
        s = new MoodStyle(); s.name = "happy"; s.mouth = 0; s.blush = .55f; s.glow = 0xFFFFB47A; s.body = 0xFFFFE6CF; moods[M_HAPPY] = s;
        s = new MoodStyle(); s.name = "curious"; s.eye = 1.1f; s.pupil = 1.18f; s.mouth = 6; s.blush = .3f; s.glow = 0xFF9EE7FF; s.body = 0xFFFFE9D6; moods[M_CURIOUS] = s;
        s = new MoodStyle(); s.name = "sleepy"; s.eye = .4f; s.mouth = 2; s.blush = .35f; s.glow = 0xFF7FA8FF; s.body = 0xFFFFEDDD; moods[M_SLEEPY] = s;
        s = new MoodStyle(); s.name = "sleeping"; s.eye = .1f; s.mouth = 2; s.blush = .3f; s.glow = 0xFF6F86FF; s.body = 0xFFFFEDDD; moods[M_SLEEPING] = s;
        s = new MoodStyle(); s.name = "hungry"; s.eye = 1.05f; s.pupil = 1.05f; s.mouth = 1; s.blush = .4f; s.brow = 2; s.glow = 0xFFFFC078; s.body = 0xFFFFE3C6; moods[M_HUNGRY] = s;
        s = new MoodStyle(); s.name = "annoyed"; s.eye = .72f; s.mouth = 3; s.blush = .25f; s.brow = 1; s.glow = 0xFFFF8F6B; s.body = 0xFFFFDCC6; moods[M_ANNOYED] = s;
        s = new MoodStyle(); s.name = "dizzy"; s.mouth = 5; s.blush = .45f; s.glow = 0xFFC9A2FF; s.body = 0xFFFFE6CF; moods[M_DIZZY] = s;
        s = new MoodStyle(); s.name = "love"; s.eye = 1.05f; s.pupil = 1.1f; s.mouth = 0; s.blush = .95f; s.glow = 0xFFFF9BB8; s.body = 0xFFFFE1D2; moods[M_LOVE] = s;
        s = new MoodStyle(); s.name = "startled"; s.eye = 1.3f; s.pupil = .62f; s.mouth = 4; s.blush = .3f; s.brow = 3; s.glow = 0xFFFFD479; s.body = 0xFFFFEBD8; moods[M_STARTLED] = s;
        s = new MoodStyle(); s.name = "bored"; s.eye = .6f; s.mouth = 2; s.blush = .25f; s.brow = 4; s.glow = 0xFF9FB0C9; s.body = 0xFFF7E3D2; moods[M_BORED] = s;
        s = new MoodStyle(); s.name = "excited"; s.eye = 1.15f; s.pupil = 1.25f; s.mouth = 1; s.blush = .7f; s.brow = 3; s.glow = 0xFFFF9BD2; s.body = 0xFFFFE8D2; moods[M_EXCITED] = s;
    }

    void setListener(Listener l) { listener = l; }

    void setMood(String name) {
        if (name == null) return;
        moodName = name;
        for (int i = 0; i < moods.length; i++) {
            if (moods[i] != null && moods[i].name.equals(name)) { mood = i; return; }
        }
        mood = M_HAPPY;
    }

    void setTempMood(String name, long ms) {
        tempMood = name;
        tempUntil = SystemClock.uptimeMillis() + ms;
    }

    int moodColor() {
        MoodStyle s = moods[mood];
        return s != null ? s.glow : 0xFFFFB47A;
    }

    void react(String event, float value) {
        long now = SystemClock.uptimeMillis();
        if ("pet".equals(event)) {
            squash = 1.05f; setTempMood("love", 1300); burst(0, 2);
        } else if ("poke".equals(event)) {
            squash = .9f; offX = (rnd.nextFloat() - .5f) * 14 * density;
            setTempMood("startled", 800); burst(1, 4);
        } else if ("feed".equals(event)) {
            setTempMood("happy", 1600); burst(3, 9);
        } else if ("play".equals(event)) {
            setTempMood("excited", 2400); burst(1, 14);
        } else if ("sleep".equals(event)) {
            setTempMood("sleeping", 3500);
        } else if ("wake".equals(event)) {
            setTempMood("sleepy", 2200);
        } else if ("shake".equals(event)) {
            setTempMood("dizzy", 2000); offX = 9 * density;
        } else if ("noise".equals(event)) {
            setTempMood("startled", 1000); burst(1, 4);
        } else if ("level".equals(event)) {
            setTempMood("excited", 3000); burst(2, 24);
        } else if ("dark".equals(event)) {
            setTempMood("sleepy", 2200);
        }
        postInvalidateOnAnimation();
    }

    void petLocal() {
        squash = 1.05f;
        setTempMood("love", 1300);
        burst(0, 2);
        postInvalidateOnAnimation();
    }

    // ------------------------------------------------------------------ layout
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float radius = Math.min(w * 0.31f, h * 0.235f);
        if (radius < 60 * density) radius = 60 * density;
        rx = radius * 1.06f;
        ry = radius;
        cx = w / 2f;
        cy = h * 0.42f;
        if (cy - ry < 90 * density) cy = 90 * density + ry;
    }

    // ------------------------------------------------------------------- draw
    @Override protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        breathT += dt;

        int styleId = mood;
        if (tempMood != null && now < tempUntil) {
            for (int i = 0; i < moods.length; i++) {
                if (moods[i] != null && moods[i].name.equals(tempMood)) { styleId = i; break; }
            }
        } else {
            tempMood = null;
        }
        MoodStyle s = moods[styleId];

        // blink
        float blink = 0;
        if (now > blinkAt && styleId != M_SLEEPING) {
            long p = now - blinkAt;
            if (p < 150) blink = (float) Math.sin(p / 150.0 * Math.PI);
            else blinkAt = now + (rnd.nextFloat() < .35f ? 180 + rnd.nextInt(120)
                                                         : 2200 + rnd.nextInt(4200));
        }
        if (styleId == M_SLEEPING) blink = 1f - s.eye;

        // spring back
        squash += (1f - squash) * Math.min(1f, dt * 12f);
        offX *= 0.86f;

        // pupils follow the finger, or wander
        float tx, ty;
        if (touching && touchX > 0) {
            tx = clamp((touchX - cx) / (rx * 1.6f), -1, 1);
            ty = clamp((touchY - cy) / (ry * 1.6f), -1, 1);
        } else {
            tx = (float) (Math.sin(breathT * .37) * .22 + Math.sin(breathT * .83 + 1.7) * .12);
            ty = (float) (Math.cos(breathT * .29) * .14 + Math.sin(breathT * .61) * .1);
        }
        eyeX += (tx - eyeX) * Math.min(1f, dt * 8f);
        eyeY += (ty - eyeY) * Math.min(1f, dt * 8f);

        float breath = 1f + (float) Math.sin(breathT * 1.5) * .012f;
        float bounce = styleId == M_EXCITED ? Math.abs((float) Math.sin(breathT * 5.2)) * .04f : 0;
        float sc = squash > 1 ? 1 + (squash - 1) * .5f : squash;
        float scx = 1 + (squash - 1) * .8f;
        float scy = 2 - squash;

        c.drawColor(0xFF090C15);

        // ambient glow
        int glow = s.glow;
        paint.setShader(new RadialGradient(cx, cy - 10, rx * 3.1f,
                new int[]{withAlpha(glow, 66), withAlpha(glow, 22), withAlpha(glow, 0)},
                new float[]{0f, .45f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, rx * 3.1f, paint);
        paint.setShader(null);

        // contact shadow
        paint.setShader(new RadialGradient(cx, cy + ry + 14 * density, rx * .9f,
                new int[]{0x7A000000, 0x00000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1f, .22f);
        c.drawCircle(cx, (cy + ry + 14 * density) / .22f, rx * .9f, paint);
        c.restore();
        paint.setShader(null);

        c.save();
        c.translate(cx + offX, cy + bounce * ry);
        c.scale(sc * scx * breath, sc * scy * breath);

        // feet
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(shade(s.body, -30));
        c.drawOval(-rx * .52f, ry * .9f, -rx * .12f, ry * 1.045f, paint);
        c.drawOval(rx * .12f, ry * .9f, rx * .52f, ry * 1.045f, paint);

        // antenna
        stroke.setColor(shade(s.body, -24));
        stroke.setStrokeWidth(Math.max(2.4f * density, rx * .022f));
        path.reset();
        path.moveTo(0, -ry * .94f);
        path.quadTo(rx * .02f, -ry * 1.16f, rx * .09f, -ry * 1.26f);
        c.drawPath(path, stroke);
        float tipX = rx * .09f, tipY = -ry * 1.29f;
        paint.setShader(new RadialGradient(tipX, tipY, rx * .16f,
                new int[]{withAlpha(glow, 200), withAlpha(glow, 0)},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(tipX, tipY, rx * .16f, paint);
        paint.setShader(null);
        paint.setColor(glow);
        c.drawCircle(tipX, tipY, Math.max(2.5f * density, rx * .045f), paint);

        // body
        bodyPath(rx, ry);
        paint.setShader(new LinearGradient(0, -ry, 0, ry * 1.04f,
                new int[]{0xFFFFF8EE, s.body, shade(s.body, -10), shade(s.body, -26)},
                new float[]{0f, .38f, .82f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(path, paint);
        paint.setShader(null);

        // inner shading
        c.save();
        c.clipPath(path);
        paint.setShader(new RadialGradient(-rx * .32f, -ry * .46f, rx * 1.6f,
                new int[]{0x8CFFFFFF, 0x00FFFFFF, 0x38563022},
                new float[]{0f, .42f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(-rx * 1.2f, -ry * 1.2f, rx * 1.2f, ry * 1.2f, paint);
        paint.setShader(null);
        paint.setColor(0x66FFFFFF);
        c.drawOval(-rx * .66f, -ry * .66f, -rx * .26f, -ry * .46f, paint);
        c.restore();

        // contour
        stroke.setColor(0x29543224);
        stroke.setStrokeWidth(1.6f * density);
        bodyPath(rx, ry);
        c.drawPath(path, stroke);

        // cheeks
        if (s.blush > .02f) {
            paint.setAlpha((int) (255 * s.blush * (.6f + .4f * (float) Math.sin(breathT * 2.1))));
            for (int k = -1; k <= 1; k += 2) {
                float bx = k * rx * .57f, by = ry * .2f;
                paint.setShader(new RadialGradient(bx, by, rx * .25f,
                        new int[]{0xF2FF848A, 0x8CFF848A, 0x00FF848A},
                        new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));
                c.drawOval(bx - rx * .25f, by - ry * .155f, bx + rx * .25f, by + ry * .155f, paint);
            }
            paint.setShader(null);
            paint.setAlpha(255);
        }

        // eyes
        float eyeY = -ry * .1f;
        float eyeDX = rx * .355f;
        float er = rx * .215f;
        float eyeScale = s.eye * (1 - blink) + .06f;
        if (eyeScale < .06f) eyeScale = .06f;

        for (int side = -1; side <= 1; side += 2) {
            float ex = side * eyeDX;
            c.save();
            c.translate(ex, eyeY);
            c.scale(1f, eyeScale);

            if (blink > .92f || styleId == M_SLEEPING) {
                stroke.setColor(0xFF2C2537);
                stroke.setStrokeWidth(Math.max(2.5f * density, rx * .028f));
                path.reset();
                path.moveTo(-er * .82f, 0);
                path.quadTo(0, er * .34f, er * .82f, 0);
                c.drawPath(path, stroke);
            } else if (styleId == M_DIZZY) {
                stroke.setColor(0xFF2C2537);
                stroke.setStrokeWidth(Math.max(2f, rx * .018f));
                path.reset();
                for (int i = 0; i < 58; i++) {
                    float ang = i * .42f + breathT * 5.2f * (side > 0 ? 1 : -1);
                    float rad = er * .1f * (i / 7f);
                    float px = (float) Math.cos(ang) * rad;
                    float py = (float) Math.sin(ang) * rad;
                    if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
                }
                c.drawPath(path, stroke);
            } else if (styleId == M_LOVE) {
                c.save();
                c.scale(er * 1.15f / 16f, er * 1.15f / 16f);
                drawHeart(c, 0, 0, 16f, 0xFFE2556F);
                c.restore();
            } else {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFF221D2E);
                c.drawOval(-er * .9f, -er, er * .9f, er, paint);

                float pr = er * .5f * s.pupil;
                c.save();
                c.translate(eyeX * er * .42f, eyeY * er * .38f);
                paint.setColor(0xFF0B0D18);
                c.drawCircle(0, 0, pr, paint);
                paint.setColor(0xF2FFFFFF);
                c.drawCircle(-pr * .42f, -pr * .52f, pr * .44f, paint);
                paint.setColor(0x80FFFFFF);
                c.drawCircle(pr * .36f, pr * .44f, pr * .18f, paint);
                c.restore();
            }
            c.restore();

            if (eyeScale < .72f && styleId != M_SLEEPING) {
                stroke.setColor(0x2E7A584A);
                stroke.setStrokeWidth(2f * density);
                path.reset();
                path.moveTo(ex - er * .9f, eyeY - er * .62f);
                path.quadTo(ex, eyeY - er * .92f, ex + er * .9f, eyeY - er * .62f);
                c.drawPath(path, stroke);
            }
        }

        // brows
        drawBrows(c, styleId, eyeY, eyeDX, er);

        // mouth
        drawMouth(c, 0, ry * .33f, rx, ry, s.mouth);

        c.restore();

        drawFx(c, now, dt);
        if (styleId == M_SLEEPING) drawZzz(c);

        postInvalidateOnAnimation();
    }

    private void bodyPath(float w, float h) {
        path.reset();
        path.moveTo(0, -h);
        path.cubicTo(w * .62f, -h, w * .98f, -h * .43f, w * 1.01f, h * .1f);
        path.cubicTo(w * 1.03f, h * .76f, w * .56f, h, 0, h);
        path.cubicTo(-w * .56f, h, -w * 1.03f, h * .76f, -w * 1.01f, h * .1f);
        path.cubicTo(-w * .98f, -h * .43f, -w * .62f, -h, 0, -h);
        path.close();
    }

    private void drawBrows(Canvas c, int styleId, float eyeY, float eyeDX, float er) {
        float slant, lift, alpha;
        if (styleId == M_ANNOYED) { slant = .48f; lift = 1.22f; alpha = .74f; }
        else if (styleId == M_STARTLED) { slant = -.1f; lift = 1.55f; alpha = .5f; }
        else if (styleId == M_EXCITED) { slant = -.14f; lift = 1.48f; alpha = .42f; }
        else if (styleId == M_BORED) { slant = .04f; lift = 1.22f; alpha = .28f; }
        else if (styleId == M_HUNGRY) { slant = -.08f; lift = 1.45f; alpha = .38f; }
        else return;

        Paint p = stroke;
        p.setColor(0xFF4A3340);
        p.setAlpha((int) (255 * alpha));
        p.setStrokeWidth(Math.max(2.2f * density, rx * .022f));
        for (int side = -1; side <= 1; side += 2) {
            float bx = side * eyeDX;
            float y = eyeY - er * lift;
            path.reset();
            path.moveTo(bx + side * er * .8f, y - er * slant * .5f);
            path.quadTo(bx, y - er * .1f, bx - side * er * .78f, y + er * slant * .7f);
            c.drawPath(path, p);
        }
        p.setAlpha(255);
    }

    private void drawMouth(Canvas c, float x, float y, float w, float h, int style) {
        c.save();
        c.translate(x, y);
        stroke.setColor(0xFF3A2A38);
        stroke.setStrokeWidth(Math.max(3f * density, w * .03f));
        paint.setStyle(Paint.Style.FILL);

        if (style == 1) {              // open, happy shout
            float mw = w * .175f, mh = h * .145f * (1 + (float) Math.sin(breathT * 7) * .06f);
            paint.setColor(0xFF3A2A38);
            c.drawOval(-mw, -mh, mw, mh, paint);
            paint.setColor(0xD9FF9DA6);
            c.drawOval(-mw * .62f, mh * .02f, mw * .62f, mh * .86f, paint);
        } else if (style == 0) {        // smile
            path.reset();
            path.moveTo(-w * .195f, -h * .025f);
            path.quadTo(0, h * .155f, w * .195f, -h * .025f);
            c.drawPath(path, stroke);
        } else if (style == 3) {        // frown
            path.reset();
            path.moveTo(-w * .15f, h * .06f);
            path.quadTo(0, -h * .065f, w * .15f, h * .06f);
            c.drawPath(path, stroke);
        } else if (style == 4) {        // small o
            paint.setColor(0xFF3A2A38);
            c.drawOval(-w * .06f, -h * .082f, w * .06f, h * .082f, paint);
        } else if (style == 5) {        // dizzy wave
            path.reset();
            for (int i = 0; i <= 20; i++) {
                float px = -w * .18f + (w * .36f) * (i / 20f);
                float py = (float) Math.sin(breathT * 9 + i * .7) * h * .038f;
                if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            c.drawPath(path, stroke);
        } else if (style == 6) {        // tiny curious smile
            path.reset();
            path.addArc(-w * .062f, -h * .075f, w * .062f, h * .03f, 8, 165);
            c.drawPath(path, stroke);
        } else {                        // flat
            path.reset();
            path.moveTo(-w * .13f, 0);
            path.lineTo(w * .13f, 0);
            c.drawPath(path, stroke);
        }
        c.restore();
    }

    // ----------------------------------------------------------------- effects
    private static final class Fx {
        int type;
        float x, y, vx, vy, life, max, size, rot;
    }

    private void burst(int type, int n) {
        for (int i = 0; i < n; i++) {
            Fx p = new Fx();
            p.type = type;
            p.x = cx + (rnd.nextFloat() - .5f) * rx * 1.1f;
            p.y = cy - ry * (.15f + rnd.nextFloat() * .5f);
            double a = -Math.PI / 2 + (rnd.nextDouble() - .5) * 2.1;
            if (type == 2) a = rnd.nextDouble() * Math.PI * 2;
            float speed = (26 + rnd.nextFloat() * 85) * density;
            p.vx = (float) Math.cos(a) * speed;
            p.vy = (float) Math.sin(a) * speed - (type == 2 ? 70 * density : 26 * density);
            p.max = 1.2f + rnd.nextFloat() * 1.1f;
            p.size = (6 + rnd.nextFloat() * 8) * density;
            p.rot = (rnd.nextFloat() - .5f) * 2;
            fx.add(p);
        }
    }

    private void drawFx(Canvas c, long now, float dt) {
        for (int i = fx.size() - 1; i >= 0; i--) {
            Fx p = fx.get(i);
            p.life += dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.vy += (p.type == 2 ? 150 : -6) * density * dt;
            p.vx *= .995f;
            float a = 1 - p.life / p.max;
            if (a <= 0) { fx.remove(i); continue; }
            c.save();
            c.translate(p.x, p.y);
            c.rotate(p.rot * p.life * 57f);
            paint.setAlpha((int) (255 * clamp(a, 0, 1)));
            if (p.type == 0) {
                c.scale(p.size * 1.15f / 16f, p.size * 1.15f / 16f);
                drawHeart(c, 0, 0, 16f, 0xFFFF7D9C);
            } else if (p.type == 1) {
                paint.setColor(0xFFFFDF8A);
                drawStar(c, p.size * .6f);
            } else if (p.type == 2) {
                paint.setColor(0xFFFFD479);
                c.drawRect(-p.size * .35f, -p.size * .2f, p.size * .35f, p.size * .2f, paint);
            } else {
                paint.setColor(0xFFC98A5B);
                c.drawCircle(0, 0, p.size * .26f, paint);
            }
            c.restore();
            paint.setAlpha(255);
        }
    }

    private void drawHeart(Canvas c, float x, float y, float s, int color) {
        Path p = new Path();
        p.moveTo(x, y + s * .375f);
        p.cubicTo(x - s * .875f, y - s * .25f, x - s * .56f, y - s, x, y - s * .56f);
        p.cubicTo(x + s * .56f, y - s, x + s * .875f, y - s * .25f, x, y + s * .375f);
        p.close();
        paint.setColor(color);
        c.drawPath(p, paint);
    }

    private void drawStar(Canvas c, float s) {
        Path p = new Path();
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            float r = (i % 2 == 0) ? s : s * .38f;
            float px = (float) Math.cos(a) * r;
            float py = (float) Math.sin(a) * r;
            if (i == 0) p.moveTo(px, py); else p.lineTo(px, py);
        }
        p.close();
        c.drawPath(p, paint);
    }

    private void drawZzz(Canvas c) {
        text.setColor(0xCCD0E0FF);
        for (int i = 0; i < 3; i++) {
            float k = (breathT * .35f + i * .33f) % 1f;
            float x = cx + rx * (.45f + k * .45f);
            float y = cy - ry * (.55f + k * .55f);
            text.setTextSize((11 + k * 15) * density);
            text.setAlpha((int) (255 * Math.sin(k * Math.PI) * .85f));
            c.drawText("z", x, y, text);
        }
        text.setAlpha(255);
    }

    // ------------------------------------------------------------------- touch
    @Override public boolean onTouchEvent(MotionEvent e) {
        long now = SystemClock.uptimeMillis();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching = true;
                touchX = e.getX();
                touchY = e.getY();
                downAt = now;
                downX = touchX; downY = touchY;
                moveAmount = 0;
                break;
            case MotionEvent.ACTION_MOVE:
                touchX = e.getX();
                touchY = e.getY();
                moveAmount += Math.abs(e.getX() - downX) + Math.abs(e.getY() - downY);
                downX = e.getX(); downY = e.getY();
                if (moveAmount > 12 * density && now - lastPetAt > 110) {
                    lastPetAt = now;
                    if (listener != null) listener.onPet(.55f);
                    petLocal();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                if (now - downAt < 280 && moveAmount < 20 * density) {
                    if (listener != null) {
                        listener.onPoke(clamp((touchX - cx) / (rx * 2) + .5f, 0, 1),
                                clamp((touchY - cy) / (ry * 2) + .5f, 0, 1));
                    }
                }
                touchX = -1;
                break;
            default:
                break;
        }
        postInvalidateOnAnimation();
        return true;
    }

    // ------------------------------------------------------------------ helpers
    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private static int shade(int color, int amount) {
        int r = clamp(Color.red(color) + amount, 0, 255);
        int g = clamp(Color.green(color) + amount, 0, 255);
        int b = clamp(Color.blue(color) + amount, 0, 255);
        return Color.rgb(r, g, b);
    }

    private static float clamp(float v, float a, float b) {
        return v < a ? a : (v > b ? b : v);
    }

    private static int clamp(int v, int a, int b) {
        return v < a ? a : (v > b ? b : v);
    }
}
