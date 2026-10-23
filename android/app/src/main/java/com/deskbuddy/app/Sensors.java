package com.deskbuddy.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.util.Locale;

/**
 * The phone's senses: motion, light, proximity, battery and a rough
 * loudness meter from the microphone. Everything is packed into one small
 * JSON line 20 times a second and streamed to the ESP32.
 */
final class Sensors implements SensorEventListener {

    interface Sink {
        void sample(String json);
    }

    private Context ctx;
    private Sink sink;
    private HandlerThread thread;
    private Handler handler;
    private SensorManager sm;
    private volatile boolean running;

    private volatile float ax, ay, az, gx, gy, gz;
    private volatile float lux = -1f;
    private volatile float prox = -1f;
    private volatile float mic = 0f;
    private volatile int batt = -1;
    private volatile boolean charging;

    private AudioRecord record;
    private Thread micThread;
    private volatile boolean micOn;

    private final Runnable emitter = new Runnable() {
        @Override public void run() {
            if (!running) return;
            Sink s = sink;
            if (s != null) s.sample(sampleJson());
            Handler h = handler;
            if (h != null) h.postDelayed(this, 50);  // 20 Hz
        }
    };

    private final BroadcastReceiver batteryRx = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0 && scale > 0) batt = Math.round(level * 100f / scale);
            int st = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING
                    || st == BatteryManager.BATTERY_STATUS_FULL;
        }
    };

    void start(Context context, Sink sink, boolean micEnabled) {
        stop();
        this.ctx = context.getApplicationContext();
        this.sink = sink;
        this.running = true;

        thread = new HandlerThread("deskbuddy-sensors");
        thread.start();
        handler = new Handler(thread.getLooper());

        sm = (SensorManager) this.ctx.getSystemService(Context.SENSOR_SERVICE);
        if (sm != null) {
            register(Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_GAME);
            register(Sensor.TYPE_GYROSCOPE, SensorManager.SENSOR_DELAY_GAME);
            register(Sensor.TYPE_LIGHT, SensorManager.SENSOR_DELAY_NORMAL);
            register(Sensor.TYPE_PROXIMITY, SensorManager.SENSOR_DELAY_NORMAL);
        }
        try {
            this.ctx.registerReceiver(batteryRx, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (Exception ignored) {
        }
        handler.post(emitter);
        setMicEnabled(micEnabled);
    }

    @SuppressWarnings("deprecation")
    private void register(int type, int rate) {
        Sensor s = sm.getDefaultSensor(type);
        if (s != null) sm.registerListener(this, s, rate, handler);
    }

    void stop() {
        running = false;
        final SensorManager mgr = sm;
        Handler h = handler;
        if (h != null) {
            h.removeCallbacksAndMessages(null);
            if (mgr != null) {
                h.post(new Runnable() {
                    @Override public void run() {
                        try { mgr.unregisterListener(Sensors.this); } catch (Exception ignored) { }
                    }
                });
            }
        }
        try { if (ctx != null) ctx.unregisterReceiver(batteryRx); } catch (Exception ignored) { }
        stopMic();
        HandlerThread t = thread;
        thread = null;
        handler = null;
        if (t != null) t.quitSafely();
        sm = null;
        ctx = null;
        sink = null;
    }

    void setMicEnabled(boolean enabled) {
        if (enabled == micOn) return;
        micOn = enabled;
        if (enabled) startMic();
        else stopMic();
    }

    boolean micEnabled() {
        return micOn;
    }

    private void startMic() {
        if (micThread != null) return;
        micThread = new Thread(new Runnable() {
            @Override public void run() {
                AudioRecord rec = null;
                try {
                    int rate = 8000;
                    int min = AudioRecord.getMinBufferSize(rate,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    int size = Math.max(min, 2048);
                    rec = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
                    record = rec;
                    if (rec.getState() != AudioRecord.STATE_INITIALIZED) return;
                    short[] buf = new short[512];
                    rec.startRecording();
                    while (micOn && running) {
                        int read = rec.read(buf, 0, buf.length);
                        if (read <= 0) continue;
                        double sum = 0;
                        for (int i = 0; i < read; i++) {
                            double v = buf[i] / 32768.0;
                            sum += v * v;
                        }
                        double rms = Math.sqrt(sum / read);
                        float level = (float) Math.min(1.0, rms * 9.0);
                        mic = mic * 0.55f + level * 0.45f;
                    }
                } catch (Exception ignored) {
                } finally {
                    try {
                        if (rec != null) {
                            if (rec.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) rec.stop();
                            rec.release();
                        }
                    } catch (Exception ignored) { }
                    record = null;
                }
            }
        }, "deskbuddy-mic");
        micThread.setDaemon(true);
        micThread.start();
    }

    private void stopMic() {
        mic = 0f;
        AudioRecord rec = record;
        if (rec != null) {
            try { rec.stop(); } catch (Exception ignored) { }
        }
        Thread t = micThread;
        micThread = null;
        if (t != null) t.interrupt();
    }

    private String sampleJson() {
        return String.format(Locale.US,
                "{\"t\":\"sensors\",\"ax\":%.2f,\"ay\":%.2f,\"az\":%.2f,"
                        + "\"gx\":%.2f,\"gy\":%.2f,\"gz\":%.2f,"
                        + "\"lux\":%.1f,\"prox\":%.1f,\"mic\":%.2f,"
                        + "\"batt\":%d,\"chg\":%d,\"scr\":1}",
                ax, ay, az, gx, gy, gz, lux, prox, mic, batt, charging ? 1 : 0);
    }

    // ------------------------------------------------------------- callbacks
    @Override public void onSensorChanged(SensorEvent e) {
        switch (e.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                ax = e.values[0]; ay = e.values[1]; az = e.values[2];
                break;
            case Sensor.TYPE_GYROSCOPE:
                gx = e.values[0]; gy = e.values[1]; gz = e.values[2];
                break;
            case Sensor.TYPE_LIGHT:
                lux = e.values[0];
                break;
            case Sensor.TYPE_PROXIMITY:
                prox = e.values[0];
                break;
            default:
                break;
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
