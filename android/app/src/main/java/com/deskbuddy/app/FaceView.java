package com.deskbuddy.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
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
        void onExitDisplay();
    }

    private static final int M_HAPPY = 0, M_CURIOUS = 1, M_SLEEPY = 2, M_SLEEPING = 3,
            M_HUNGRY = 4, M_ANNOYED = 5, M_DIZZY = 6, M_LOVE = 7, M_STARTLED = 8,
            M_BORED = 9, M_EXCITED = 10;

    // EMO-style eye expressions
    private static final int F_ROUND = 0, F_WIDE = 1, F_SLEEPY = 2, F_CLOSED = 3,
            F_HAPPY = 4, F_HEART = 5, F_STAR = 6, F_DIZZY = 7, F_ANGRY = 8,
            F_SAD = 9, F_HALF = 10;

    private static final class MoodStyle {
        float eye = 1f, pupil = 1f, blush = .34f;
        int face = F_ROUND, mouth = 0, brow = 0, glow = 0xFF79E6FF, body = 0xFFEEF3F9;
        String name = "happy";
    }

    private final MoodStyle[] moods = new MoodStyle[11];

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Random rnd = new Random();

    private final List<Fx> fx = new ArrayList<Fx>();
    private float density;

    private int mood = M_HAPPY;
    private String moodName = "happy";
    private String tempMood = null;
    private long tempUntil = 0;

    private float cx, cy, rx, ry;
    private boolean faceOnly = false;
    private int cornerTaps = 0;
    private long cornerAt = 0;
    private long lastFrame = 0;
    private long blinkAt = 0;
    private float blinkAmt = 0;
    private float eyeX = 0, eyeY = 0;
    private float squash = 1f, offX = 0, breathT = 0;
    private float tilt = 0, tiltV = 0, ant = 0, antV = 0, antKick = 0;
    private boolean forceBlink = false;

    private float touchX = -1, touchY = -1;
    private boolean touching = false;

    private long danceUntil = 0;
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
        s = new MoodStyle(); s.name = "happy"; s.face = F_ROUND; s.mouth = 0; s.blush = .34f; s.glow = 0xFF79E6FF; s.body = 0xFFEEF3F9; moods[M_HAPPY] = s;
        s = new MoodStyle(); s.name = "curious"; s.face = F_WIDE; s.eye = 1.1f; s.mouth = 6; s.blush = .22f; s.glow = 0xFF8FEDFF; s.body = 0xFFF3F7FB; moods[M_CURIOUS] = s;
        s = new MoodStyle(); s.name = "sleepy"; s.face = F_SLEEPY; s.eye = .42f; s.mouth = 2; s.blush = .26f; s.glow = 0xFF9BA9FF; s.body = 0xFFE9EDF6; moods[M_SLEEPY] = s;
        s = new MoodStyle(); s.name = "sleeping"; s.face = F_CLOSED; s.eye = 0f; s.mouth = 2; s.blush = .18f; s.glow = 0xFF7E8BFF; s.body = 0xFFE4E8F2; moods[M_SLEEPING] = s;
        s = new MoodStyle(); s.name = "hungry"; s.face = F_WIDE; s.eye = 1.05f; s.mouth = 1; s.blush = .32f; s.glow = 0xFFFFC36B; s.body = 0xFFF3EFE7; moods[M_HUNGRY] = s;
        s = new MoodStyle(); s.name = "annoyed"; s.face = F_ANGRY; s.eye = .96f; s.mouth = 3; s.blush = .20f; s.glow = 0xFFFF7A6B; s.body = 0xFFF2E9E4; moods[M_ANNOYED] = s;
        s = new MoodStyle(); s.name = "dizzy"; s.face = F_DIZZY; s.mouth = 5; s.blush = .26f; s.glow = 0xFFC9A2FF; s.body = 0xFFEFEAF7; moods[M_DIZZY] = s;
        s = new MoodStyle(); s.name = "love"; s.face = F_HEART; s.eye = 1.04f; s.mouth = 0; s.blush = .88f; s.glow = 0xFFFF8FB7; s.body = 0xFFF8EDF2; moods[M_LOVE] = s;
        s = new MoodStyle(); s.name = "startled"; s.face = F_WIDE; s.eye = 1.26f; s.mouth = 4; s.blush = .22f; s.brow = 3; s.glow = 0xFFFFE08A; s.body = 0xFFF5F2E8; moods[M_STARTLED] = s;
        s = new MoodStyle(); s.name = "bored"; s.face = F_HALF; s.eye = .62f; s.mouth = 2; s.blush = .16f; s.glow = 0xFF9FB6CF; s.body = 0xFFEDF1F5; moods[M_BORED] = s;
        s = new MoodStyle(); s.name = "excited"; s.face = F_STAR; s.eye = 1.12f; s.mouth = 1; s.blush = .60f; s.glow = 0xFFFF9BE0; s.body = 0xFFF6EDF6; moods[M_EXCITED] = s;
    }

    void setListener(Listener l) { listener = l; }

    void setFaceOnly(boolean on) {
        faceOnly = on;
        layoutFace(getWidth(), getHeight());
        invalidate();
    }

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
            antKick = 1f; forceBlink = true;
            setTempMood("startled", 800); burst(1, 4);
        } else if ("feed".equals(event)) {
            setTempMood("happy", 1600); burst(3, 9);
        } else if ("play".equals(event)) {
            antKick = 1.4f;
            danceUntil = now + 9000;
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
        layoutFace(w, h);
    }

    private void layoutFace(int w, int h) {
        if (w <= 0 || h <= 0) return;
        float radius;
        if (faceOnly) {
            radius = Math.min(w * 0.46f, h * 0.30f);
            if (radius < 60 * density) radius = 60 * density;
            rx = radius * 1.06f;
            ry = radius;
            cx = w / 2f;
            cy = h * 0.50f;
        } else {
            radius = Math.min(w * 0.288f, h * 0.22f);
            if (radius < 60 * density) radius = 60 * density;
            rx = radius * 1.06f;
            ry = radius;
            cx = w / 2f;
            cy = h * 0.42f;
            float headH = ry * 1.5f;
            if (cy - headH * .5f < 70 * density) cy = 70 * density + headH * .5f;
        }
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

        boolean dancing = now < danceUntil;

        // blink, with an occasional double blink
        if (forceBlink) { blinkAt = now; forceBlink = false; }
        float blink = 0;
        if (now > blinkAt && styleId != M_SLEEPING) {
            long p = now - blinkAt;
            if (p < 140) blink = (float) Math.sin(p / 140.0 * Math.PI);
            else blinkAt = now + (rnd.nextFloat() < .3f ? 200 + rnd.nextInt(120)
                                                        : 2200 + rnd.nextInt(4200));
        }

        // spring back
        squash += (1f - squash) * Math.min(1f, dt * 12f);
        offX *= 0.86f;

        // weighty head tilt
        float targetTilt = touching
                ? clamp((touchX - cx) / (getWidth() * .8f), -1, 1) * .05f
                : (float) Math.sin(breathT * .29f) * .014f;
        tiltV += (targetTilt - tilt) * .02f;
        tiltV *= .88f;
        tilt += tiltV;

        // gaze
        float tx, ty;
        if (touching && touchX > 0) {
            tx = clamp((touchX - cx) / (rx * 1.8f), -1, 1);
            ty = clamp((touchY - cy) / (ry * 1.8f), -1, 1);
        } else {
            tx = (float) (Math.sin(breathT * .4) * .14 + Math.sin(breathT * .93 + 1.7) * .08);
            ty = (float) (Math.cos(breathT * .31) * .09 + Math.sin(breathT * .67) * .06);
        }
        eyeX += (tx - eyeX) * Math.min(1f, dt * 8f);
        eyeY += (ty - eyeY) * Math.min(1f, dt * 8f);

        float bob = (float) Math.sin(breathT * 1.22) * 1.6f * density
                  + (dancing ? Math.abs((float) Math.sin(breathT * 6.0)) * .018f * ry : 0);
        float sc = 1 + (squash - 1) * .55f;
        float scx = 1 + (squash - 1) * .75f;
        float scy = 2 - squash;

        int glow = s.glow;

        c.drawColor(0xFF090C15);

        // ambient glow
        paint.setShader(new RadialGradient(cx, cy, Math.max(rx * 3.1f, 280 * density),
                new int[]{withAlpha(glow, 46), withAlpha(glow, 16), withAlpha(glow, 0)},
                new float[]{0f, .45f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, Math.max(rx * 3.1f, 280 * density), paint);
        paint.setShader(null);

        float hw = rx * 1.62f, hh = ry * 1.50f;
        float footH = hh * .16f;
        float shadowY = cy + hh * .5f + footH * .7f + 8 * density + bob * .35f;

        // contact shadow
        paint.setShader(new RadialGradient(cx, shadowY, hw * .50f,
                new int[]{0x7A000000, 0x38000000, 0x00000000},
                new float[]{0f, .5f, 1f}, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1f, .2f);
        c.drawCircle(cx, shadowY / .2f, hw * .50f, paint);
        c.restore();
        paint.setShader(null);

        c.save();
        c.translate(cx + offX, cy + bob);
        c.rotate((float) (tilt * 57.2958) + (dancing ? (float) Math.sin(breathT * 8.6) * .06f : 0));
        c.scale(sc * scx, sc * scy);

        drawFeet(c, hw, hh, footH, s, dancing);
        drawHead(c, hw, hh, s, styleId, dancing, blink);

        c.restore();

        drawFx(c, now, dt);
        if (styleId == M_SLEEPING) drawZzz(c);

        postInvalidateOnAnimation();
    }

    private void rr(float x, float y, float w, float h, float r) {
        r = Math.min(r, Math.min(w, h) * .5f);
        path.reset();
        rect.set(x, y, x + w, y + h);
        path.addRoundRect(rect, r, r, Path.Direction.CW);
    }

    private void drawFeet(Canvas c, float hw, float hh, float footH, MoodStyle s, boolean dancing) {
        paint.setStyle(Paint.Style.FILL);
        float fw = hw * .17f;
        for (int side = -1; side <= 1; side += 2) {
            float tap = dancing
                    ? Math.max(0, (float) Math.sin(breathT * 13 + (side > 0 ? Math.PI : 0))) * footH * .28f
                    : 0;
            float fx = side * hw * .19f;
            float fy = hh * .5f - footH * .30f - tap;
            rr(fx - fw * .5f, fy, fw, footH, fw * .42f);
            paint.setShader(new LinearGradient(0, fy, 0, fy + footH,
                    new int[]{shade(s.body, -6), shade(s.body, -26)},
                    null, Shader.TileMode.CLAMP));
            c.drawPath(path, paint);
            paint.setShader(null);
            stroke.setColor(0x66546A80);
            stroke.setStrokeWidth(1.2f * density);
            c.drawPath(path, stroke);

            c.save();
            c.clipPath(path);
            paint.setColor(0x80FFFFFF);
            c.drawRect(fx - fw * .5f + 2 * density, fy + 2 * density, fx - fw * .5f + fw * .28f, fy + footH * .7f, paint);
            c.restore();
        }
    }

    private void drawHead(Canvas c, float hw, float hh, MoodStyle s, int styleId,
                          boolean dancing, float blink) {
        int face = s.face;
        if (dancing) face = F_HAPPY;
        float pad = hw * .048f;
        float sx = -hw * .5f + pad, sy = -hh * .5f + pad;
        float sw = hw - pad * 2, sh = hh - pad * 2;
        float srad = hw * .205f;
        float ey = hh * .005f, ex = hw * .16f;
        float ew = hw * .228f, eh = hh * .255f;
        int glow = s.glow;
        paint.setStyle(Paint.Style.FILL);

        // shell
        rr(-hw * .5f, -hh * .5f, hw, hh, hw * .285f);
        paint.setShader(new LinearGradient(0, -hh * .5f, 0, hh * .5f,
                new int[]{0xFFFFFFFF, s.body, shade(s.body, -11), shade(s.body, -28)},
                new float[]{0f, .26f, .72f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(path, paint);
        paint.setShader(null);
        stroke.setColor(0x574E5C70);
        stroke.setStrokeWidth(1.6f * density);
        c.drawPath(path, stroke);

        // screen bezel
        rr(sx - 4 * density, sy - 4 * density, sw + 8 * density, sh + 8 * density, srad + 3 * density);
        paint.setColor(0xFF161C26);
        c.drawPath(path, paint);

        // screen glass
        rr(sx, sy, sw, sh, srad);
        paint.setShader(new LinearGradient(0, sy, 0, sy + sh,
                new int[]{0xFF0B121B, 0xFF070B12, 0xFF04060A},
                new float[]{0f, .45f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(path, paint);
        paint.setShader(null);

        c.save();
        rr(sx, sy, sw, sh, srad);
        c.clipPath(path);

        // glow rising from the bottom of the screen
        paint.setShader(new RadialGradient(0, sy + sh * .92f, sw * .8f,
                new int[]{withAlpha(glow, 40), withAlpha(glow, 12), withAlpha(glow, 0)},
                new float[]{0f, .5f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(sx, sy, sx + sw, sy + sh, paint);
        paint.setShader(null);

        // blush LEDs
        if (s.blush > .45f) {
            int alpha = (int) (255 * s.blush * (.30f + .25f * (float) Math.sin(breathT * 2.0)));
            for (int bs = -1; bs <= 1; bs += 2) {
                float bx = bs * hw * .305f, by = hh * .19f;
                paint.setShader(new RadialGradient(bx, by, hw * .07f,
                        new int[]{withAlpha(0xFFFF7EA0, alpha), withAlpha(0xFFFF7EA0, 0)},
                        new float[]{0f, 1f}, Shader.TileMode.CLAMP));
                c.drawCircle(bx, by, hw * .07f, paint);
            }
            paint.setShader(null);
        }

        float open = s.eye * (1 - blink);
        if (styleId == M_SLEEPING) open = 0;
        if (face == F_HEART || face == F_STAR || face == F_DIZZY) open = Math.max(open, .5f);

        for (int side = -1; side <= 1; side += 2) {
            drawRobotEye(c, side * ex, ey, ew, eh, open, face, s, side);
        }
        drawBrows(c, face, s, ex, ey, ew, eh);

        int mouthStyle = s.mouth;
        if (dancing) mouthStyle = 0;
        drawMouth(c, 0, hh * .24f, hw * .14f, hh * .10f, mouthStyle, glow);

        // glass highlight
        c.save();
        path.reset();
        path.moveTo(sx, sy + sh * .16f);
        path.lineTo(sx + sw * .46f, sy);
        path.lineTo(sx + sw * .72f, sy);
        path.lineTo(sx, sy + sh * .5f);
        path.close();
        paint.setShader(new LinearGradient(sx, sy, sx + sw * .7f, sy + sh * .4f,
                new int[]{0x09FFFFFF, 0x03FFFFFF, 0x00FFFFFF},
                new float[]{0f, .7f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(path, paint);
        paint.setShader(null);
        c.restore();

        // screen edge highlight
        stroke.setColor(0x12FFFFFF);
        stroke.setStrokeWidth(2 * density);
        rr(sx + 1, sy + 1, sw - 2, sh - 2, srad);
        c.drawPath(path, stroke);

        if (styleId == M_SLEEPING) {
            paint.setColor(0x47000000);
            c.drawRect(sx, sy, sx + sw, sy + sh, paint);
        }
        c.restore();

        // top status button
        rr(-hw * .05f, -hh * .5f - hw * .028f, hw * .10f, hw * .05f, hw * .02f);
        paint.setShader(new LinearGradient(0, -hh * .5f - hw * .028f, 0, -hh * .5f + hw * .022f,
                new int[]{0xFFFFFFFF, shade(s.body, -18)},
                null, Shader.TileMode.CLAMP));
        c.drawPath(path, paint);
        paint.setShader(null);
        stroke.setColor(0x59546A80);
        stroke.setStrokeWidth(1 * density);
        c.drawPath(path, stroke);

        float ledY = -hh * .5f - hw * .010f;
        paint.setShader(new RadialGradient(0, ledY, hw * .075f,
                new int[]{withAlpha(glow, 140), withAlpha(glow, 40), withAlpha(glow, 0)},
                new float[]{0f, .4f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(0, ledY, hw * .075f, paint);
        paint.setShader(null);
        paint.setColor(glow);
        c.drawCircle(0, ledY, hw * .017f, paint);
    }

    private void drawRobotEye(Canvas c, float x, float y, float ew, float eh,
                              float open, int face, MoodStyle s, int side) {
        int glow = s.glow;
        c.save();
        c.translate(x + eyeX * ew * .05f, y + eyeY * eh * .05f);

        if (open < .07f || face == F_CLOSED) {
            stroke.setColor(glow);
            stroke.setStrokeWidth(Math.max(2.4f * density, eh * .10f));
            path.reset();
            path.moveTo(-ew * .42f, 0);
            path.quadTo(0, eh * .06f, ew * .42f, 0);
            c.drawPath(path, stroke);
            stroke.setColor(0xB3FFFFFF);
            stroke.setStrokeWidth(Math.max(1 * density, eh * .03f));
            c.drawPath(path, stroke);
            c.restore();
            return;
        }

        // bloom
        paint.setShader(new RadialGradient(0, 0, ew * 1.35f,
                new int[]{withAlpha(glow, 66), withAlpha(glow, 20), withAlpha(glow, 0)},
                new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(0, 0, ew * 1.35f, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.FILL);

        if (face == F_HEART) {
            c.save();
            c.scale(ew * .98f / 16f, ew * .98f / 16f);
            drawHeart(c, 0, 0, 16f, glow);
            c.restore();
            c.save();
            c.scale(ew * .62f / 16f, ew * .62f / 16f);
            drawHeart(c, 0, 0, 16f, 0xFFFFEAF2);
            c.restore();
            c.restore();
            return;
        }
        if (face == F_STAR) {
            paint.setColor(glow);
            drawStar(c, ew * .55f);
            paint.setColor(0xFFFFFFFF);
            drawStar(c, ew * .28f);
            c.restore();
            return;
        }
        if (face == F_DIZZY) {
            stroke.setColor(glow);
            stroke.setStrokeWidth(Math.max(1.8f, ew * .06f));
            path.reset();
            for (int i = 0; i < 60; i++) {
                float ang = i * .42f + breathT * 5.4f * side;
                float rad = ew * .08f * (i / 7f);
                float px = (float) Math.cos(ang) * rad;
                float py = (float) Math.sin(ang) * rad;
                if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            c.drawPath(path, stroke);
            c.restore();
            return;
        }
        if (face == F_HAPPY) {
            float h2 = eh * (.17f + open * .15f);
            path.reset();
            path.moveTo(-ew * .34f, h2 * .85f);
            path.quadTo(0, -h2 * 1.05f, ew * .34f, h2 * .85f);
            stroke.setColor(glow);
            stroke.setStrokeWidth(Math.max(3 * density, eh * .14f));
            c.drawPath(path, stroke);
            stroke.setColor(0xCCFFFFFF);
            stroke.setStrokeWidth(Math.max(1.4f * density, eh * .045f));
            c.drawPath(path, stroke);
            c.restore();
            return;
        }

        float w = ew, h = eh * open;
        if (face == F_WIDE) { w *= 1.06f; h *= 1.08f; }
        if (face == F_ANGRY) h *= .78f;
        if (face == F_SLEEPY) h = eh * Math.max(.10f, open) * .42f;
        if (face == F_HALF) h = eh * Math.max(.10f, open) * .52f;

        c.save();
        if (face == F_ANGRY) c.rotate(side * 9f);
        else if (face == F_SAD) c.rotate(-side * 6f);

        rr(-w * .5f, -h * .5f, w, h, w * .42f);
        paint.setShader(new LinearGradient(0, -h * .5f, 0, h * .5f,
                new int[]{0xFFFFFFFF, 0xFFE9FDFF, 0xFFAEEFFF},
                new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(path, paint);
        paint.setShader(null);

        c.save();
        c.clipPath(path);
        paint.setShader(new LinearGradient(0, 0, 0, h * .5f,
                new int[]{withAlpha(glow, 0), withAlpha(glow, 140)},
                null, Shader.TileMode.CLAMP));
        c.drawRect(-w * .5f, 0, w * .5f, h * .5f, paint);
        paint.setShader(null);
        c.restore();

        stroke.setColor(withAlpha(glow, 220));
        stroke.setStrokeWidth(2 * density);
        c.drawPath(path, stroke);
        c.restore();
        c.restore();
    }

    private void drawBrows(Canvas c, int face, MoodStyle s, float ex, float ey, float ew, float eh) {
        if (face != F_ANGRY && face != F_SAD && s.brow != 3) return;
        float y = ey - eh * .74f;
        stroke.setStrokeCap(Paint.Cap.ROUND);
        for (int side = -1; side <= 1; side += 2) {
            float tilt = face == F_ANGRY ? -side * .34f : face == F_SAD ? side * .24f : 0;
            c.save();
            c.translate(side * ex, y - (s.brow == 3 ? eh * .18f : 0));
            c.rotate((float) (tilt * 57.2958));
            path.reset();
            path.moveTo(-ew * .40f, 0);
            path.quadTo(0, -eh * .06f, ew * .40f, 0);
            stroke.setColor(withAlpha(s.glow, 90));
            stroke.setStrokeWidth(eh * .10f);
            c.drawPath(path, stroke);
            stroke.setColor(0xD9FFFFFF);
            stroke.setStrokeWidth(Math.max(2.4f * density, eh * .06f));
            c.drawPath(path, stroke);
            c.restore();
        }
    }

    private void drawMouth(Canvas c, float x, float y, float w, float h, int style, int glow) {
        paint.setStyle(Paint.Style.FILL);
        c.save();
        c.translate(x, y);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);

        paint.setShader(new RadialGradient(0, 0, w * 1.6f,
                new int[]{withAlpha(glow, 46), withAlpha(glow, 0)},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(0, 0, w * 1.6f, paint);
        paint.setShader(null);

        if (style == 1) {              // open
            float mw = w * .55f, mh = h * .58f;
            path.reset();
            path.addOval(-mw, -mh, mw, mh, Path.Direction.CW);
            paint.setColor(0xFF07090F);
            c.drawPath(path, paint);
            stroke.setColor(withAlpha(glow, 220));
            stroke.setStrokeWidth(2 * density);
            c.drawPath(path, stroke);
            paint.setColor(withAlpha(glow, 140));
            c.drawOval(-mw * .48f, mh * .02f, mw * .48f, mh * .60f, paint);

        } else if (style == 5) {       // dizzy wave
            path.reset();
            for (int i = 0; i <= 16; i++) {
                float px = -w + (w * 2) * (i / 16f);
                float py = (float) Math.sin(breathT * 9 + i * .8) * h * .22f;
                if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            stroke.setColor(0x3DFFFFFF);
            stroke.setStrokeWidth(7 * density);
            c.drawPath(path, stroke);
            stroke.setColor(0xD9FFFFFF);
            stroke.setStrokeWidth(2.2f * density);
            c.drawPath(path, stroke);

        } else if (style == 4) {       // o
            paint.setColor(0xFF07090F);
            c.drawCircle(0, 0, w * .18f, paint);
            stroke.setColor(0xD9FFFFFF);
            stroke.setStrokeWidth(2 * density);
            c.drawCircle(0, 0, w * .18f, stroke);

        } else {
            path.reset();
            if (style == 3) {          // frown
                path.moveTo(-w * .55f, h * .14f);
                path.quadTo(0, -h * .40f, w * .55f, h * .14f);
            } else if (style == 6) {   // small
                path.moveTo(-w * .22f, 0);
                path.quadTo(0, h * .18f, w * .22f, 0);
            } else if (style == 2) {   // flat
                path.moveTo(-w * .35f, 0);
                path.lineTo(w * .35f, 0);
            } else {                   // smile
                path.moveTo(-w * .55f, -h * .06f);
                path.quadTo(0, h * .42f, w * .55f, -h * .06f);
            }
            stroke.setColor(withAlpha(glow, 77));
            stroke.setStrokeWidth(7 * density);
            c.drawPath(path, stroke);
            stroke.setColor(0xE6FFFFFF);
            stroke.setStrokeWidth(2.2f * density);
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
            float x = cx + rx * (.80f + k * .30f);
            float y = cy - ry * (.60f + k * .30f);
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
                if (now - downAt < 320 && moveAmount < 24 * density) {
                    if (touchX < getWidth() * .22f && touchY < getHeight() * .22f) {
                        if (now - cornerAt > 1100) cornerTaps = 0;
                        cornerAt = now;
                        cornerTaps++;
                        if (cornerTaps >= 3) {
                            cornerTaps = 0;
                            if (listener != null) listener.onExitDisplay();
                            touchX = -1;
                            break;
                        }
                    }
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
