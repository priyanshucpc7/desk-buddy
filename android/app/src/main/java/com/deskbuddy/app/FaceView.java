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
    private float tilt = 0, tiltV = 0, ant = 0, antV = 0, antKick = 0;
    private boolean forceBlink = false;

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
        s = new MoodStyle(); s.name = "sleepy"; s.eye = .4f; s.mouth = 2; s.blush = .35f; s.glow = 0xFF7FA8FF; s.body = 0xFFEDEAF8; moods[M_SLEEPY] = s;
        s = new MoodStyle(); s.name = "sleeping"; s.eye = .1f; s.mouth = 2; s.blush = .3f; s.glow = 0xFF6F86FF; s.body = 0xFFE9E8F6; moods[M_SLEEPING] = s;
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
            antKick = 1f; forceBlink = true;
            setTempMood("startled", 800); burst(1, 4);
        } else if ("feed".equals(event)) {
            setTempMood("happy", 1600); burst(3, 9);
        } else if ("play".equals(event)) {
            antKick = 1.4f;
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

        // weighty head tilt + spring-loaded antenna
        float targetTilt = touching
                ? clamp((touchX - cx) / (getWidth() * .8f), -1, 1) * .045f
                : (float) Math.sin(breathT * .29f) * .013f;
        tiltV += (targetTilt - tilt) * .02f;
        tiltV *= .88f;
        tilt += tiltV;

        if (antKick != 0) { antV += antKick * .028f; antKick = 0; }
        float targetAnt = -tilt * 2.4f + (float) Math.sin(breathT * .9f) * .05f + antV * .2f;
        antV += (targetAnt - ant) * .014f;
        antV *= .93f;
        ant += antV;

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
        c.translate(cx + offX + (float) Math.sin(tilt) * 8 * density, cy + bounce * ry);
        c.rotate((float) (tilt * 57.2958));
        c.scale(sc * scx * breath, sc * scy * breath);

        // feet, warm and slightly folded under the body
        for (int ft = -1; ft <= 1; ft += 2) {
            float fx = ft * rx * .31f;
            float fy = ry * .965f;
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(fx, fy - ry * .08f, fx, fy + ry * .08f,
                    new int[]{shade(s.body, -14), shade(s.body, -32), shade(s.body, -46)},
                    new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));
            c.drawOval(fx - rx * .2f, fy - ry * .075f, fx + rx * .2f, fy + ry * .075f, paint);
            paint.setShader(null);
            stroke.setColor(0x5970432C);
            stroke.setStrokeWidth(1.2f * density);
            path.reset();
            path.addArc(fx - rx * .185f, fy - ry * .06f, fx + rx * .185f, fy + ry * .05f, 200, 140);
            c.drawPath(path, stroke);
        }

        // spring-loaded antenna
        float baseY = -ry * .935f;
        float tipX = (float) Math.sin(ant) * ry * .36f;
        float tipY = baseY - (float) Math.cos(ant) * ry * .36f;
        stroke.setColor(shade(s.body, -26));
        stroke.setStrokeWidth(Math.max(2.2f * density, rx * .021f));
        path.reset();
        path.moveTo(0, baseY);
        path.quadTo((float) Math.sin(ant * .5f) * ry * .02f, baseY - ry * .2f, tipX, tipY);
        c.drawPath(path, stroke);

        paint.setAlpha((int) (255 * (.7f + .3f * Math.sin(breathT * 2.2))));
        paint.setShader(new RadialGradient(tipX, tipY, rx * .14f,
                new int[]{withAlpha(glow, 230), withAlpha(glow, 90), withAlpha(glow, 0)},
                new float[]{0f, .35f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(tipX, tipY, rx * .14f, paint);
        paint.setShader(null);
        paint.setAlpha(255);
        paint.setColor(glow);
        c.drawCircle(tipX, tipY, Math.max(2.2f * density, rx * .042f), paint);
        paint.setColor(0xCCFFFFFF);
        c.drawCircle(tipX - rx * .012f, tipY - rx * .014f, Math.max(.8f * density, rx * .017f), paint);

        // body, lit from the upper left
        bodyPath(rx, ry);
        paint.setShader(new LinearGradient(0, -ry, 0, ry * 1.04f,
                new int[]{shade(s.body, 7), shade(s.body, 3), shade(s.body, -8), shade(s.body, -24)},
                new float[]{0f, .32f, .76f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(path, paint);
        paint.setShader(null);

        c.save();
        c.clipPath(path);

        // key light
        paint.setShader(new RadialGradient(-rx * .40f, -ry * .52f, rx * 1.7f,
                new int[]{0x66FFFFFF, 0x14FFFFFF, 0x00FFFFFF},
                new float[]{0f, .38f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(-rx * 1.3f, -ry * 1.3f, rx * 1.3f, ry * 1.3f, paint);
        paint.setShader(null);

        // mood bounce from the desk
        paint.setShader(new LinearGradient(0, ry * .12f, 0, ry * 1.02f,
                new int[]{withAlpha(glow, 0), withAlpha(glow, 70)},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(-rx, ry * .12f, rx, ry * 1.02f, paint);
        paint.setShader(null);

        // contact occlusion
        paint.setShader(new RadialGradient(0, ry * .98f, rx * .95f,
                new int[]{0x47603426, 0x1A603426, 0x00603426},
                new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(-rx, -ry, rx, ry, paint);
        paint.setShader(null);

        // fresnel rim
        stroke.setShader(new LinearGradient(-rx, -ry, rx * .6f, ry,
                new int[]{0x8CFFFFFF, 0x14FFFFFF, withAlpha(glow, 60)},
                new float[]{0f, .45f, 1f}, Shader.TileMode.CLAMP));
        stroke.setStrokeWidth(rx * .045f);
        bodyPath(rx * .985f, ry * .985f);
        c.drawPath(path, stroke);
        stroke.setShader(null);
        c.restore();

        // contour
        stroke.setColor(0x24543224);
        stroke.setStrokeWidth(1.4f * density);
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
        float open = s.eye * (1 - blink);
        if (styleId == M_SLEEPING) open = 0;
        if (styleId == M_LOVE) open *= .82f;

        for (int side = -1; side <= 1; side += 2) {
            float ex = side * eyeDX;
            c.save();
            c.translate(ex, eyeY);

            // socket shadow seats the eye in the face
            paint.setShader(new RadialGradient(0, 0, er * 1.55f,
                    new int[]{0x1A6E3E2E, 0x006E3E2E},
                    new float[]{.25f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(0, 0, er * 1.55f, paint);
            paint.setShader(null);

            if (styleId == M_DIZZY) {
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

            } else if (open < .07f) {
                stroke.setColor(0xFF3A2C3C);
                stroke.setStrokeWidth(Math.max(2.5f * density, er * .13f));
                path.reset();
                path.moveTo(-er * .8f, 0);
                path.quadTo(0, er * .30f, er * .8f, 0);
                c.drawPath(path, stroke);
                stroke.setColor(0x2E8C5A46);
                stroke.setStrokeWidth(1.4f * density);
                path.reset();
                path.moveTo(-er * .7f, er * .16f);
                path.quadTo(0, er * .42f, er * .7f, er * .16f);
                c.drawPath(path, stroke);

            } else {
                // glossy iris
                c.save();
                path.reset();
                path.addOval(-er * .9f, -er * 1.02f, er * .9f, er * 1.02f, Path.Direction.CW);
                c.clipPath(path);

                paint.setShader(new RadialGradient(eyeX * er * .34f, eyeY * er * .30f, er * 1.15f,
                        new int[]{0xFF4E4074, 0xFF251F3A, 0xFF0D0E1B},
                        new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));
                c.drawRect(-er, -er * 1.1f, er, er * 1.1f, paint);
                paint.setShader(null);

                float px = eyeX * er * .34f, py = eyeY * er * .30f;
                float pr = er * .46f * s.pupil;
                paint.setColor(0xFF07080E);
                c.drawCircle(px, py, pr, paint);

                stroke.setColor(0x33A08CCD);
                stroke.setStrokeWidth(1.2f * density);
                c.drawCircle(px, py, pr * 1.12f, stroke);

                paint.setColor(0xF2FFFFFF);
                c.drawOval(-er * .60f, -er * .63f, -er * .08f, -er * .21f, paint);
                paint.setColor(0x8CFFFFFF);
                c.drawCircle(er * .30f, er * .36f, er * .10f, paint);

                stroke.setColor(0x21FFFFFF);
                stroke.setStrokeWidth(1.4f * density);
                path.reset();
                path.addArc(-er * .82f, -er * .82f, er * .82f, er * .82f, 35, 100);
                c.drawPath(path, stroke);
                c.restore();

                // physical upper lid, clipped to the eye
                float lidY = -er * 1.12f + (1 - open) * er * 2.24f;
                c.save();
                path.reset();
                path.addOval(-er * .92f, -er * 1.04f, er * .92f, er * 1.04f, Path.Direction.CW);
                c.clipPath(path);

                path.reset();
                path.moveTo(-er * 1.2f, -er * 1.4f);
                path.lineTo(er * 1.2f, -er * 1.4f);
                path.lineTo(er * 1.2f, lidY);
                path.quadTo(0, lidY + er * .20f, -er * 1.2f, lidY);
                path.close();
                paint.setShader(new LinearGradient(0, -er * 1.1f, 0, lidY + er * .2f,
                        shade(s.body, 5), shade(s.body, -4), Shader.TileMode.CLAMP));
                c.drawPath(path, paint);
                paint.setShader(null);

                stroke.setColor(0x42784A38);
                stroke.setStrokeWidth(1.5f * density);
                path.reset();
                path.moveTo(-er * 1.2f, lidY);
                path.quadTo(0, lidY + er * .20f, er * 1.2f, lidY);
                c.drawPath(path, stroke);
                c.restore();
            }
            c.restore();
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
            float mw = w * .165f, mh = h * .135f * (1 + (float) Math.sin(breathT * 6.5f) * .03f);
            paint.setShader(new LinearGradient(0, -mh, 0, mh,
                    new int[]{0xFF241A24, 0xFF3A2430, 0xFF4A2A34},
                    new float[]{0f, .6f, 1f}, Shader.TileMode.CLAMP));
            c.drawOval(-mw, -mh, mw, mh, paint);
            paint.setShader(null);
            paint.setColor(0xFFF08296);
            c.drawOval(-mw * .62f, mh * .02f, mw * .62f, mh * .86f, paint);
            paint.setColor(0x38FFFFFF);
            c.drawOval(-mw * .63f, -mh * .53f, -mw * .07f, -mh * .17f, paint);
            stroke.setColor(0x80503240);
            stroke.setStrokeWidth(1.4f * density);
            path.reset();
            path.addOval(-mw, -mh, mw, mh, Path.Direction.CW);
            c.drawPath(path, stroke);
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
