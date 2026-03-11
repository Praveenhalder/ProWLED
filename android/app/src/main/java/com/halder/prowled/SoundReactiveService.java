package com.halder.prowled;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SoundReactiveService — WLED UDP realtime LED driver.
 *
 * KEY DESIGN DECISIONS (flicker prevention):
 *
 * 1. Single thread — audio capture, FFT, effects, and UDP send all run on one
 *    thread (audioThread). No networkThread queue. This eliminates the main
 *    source of flickering: a backlogged executor firing stale frames back-to-back.
 *
 * 2. fftResult[] is the only brightness source — values pass through
 *    postProcess() which applies fast-attack/slow-decay smoothing (WLED style).
 *    sendWled() never touches raw audio — only smoothed 0-255 channel values.
 *
 * 3. Persistent ledBuf — one byte[] reused every frame. Effects mutate it
 *    in-place (fade, trail, etc.). Never zeroed between frames.
 *
 * 4. Silence handled in-band — when quiet, the normal effect loop decays
 *    ledBuf toward black gradually. No separate sendSilence() packet that could
 *    interleave with a normal frame.
 */
public class SoundReactiveService extends Service {

    private static final String TAG          = "SoundReactiveSvc";
    public  static final String ACTION_START  = "com.halder.prowled.START_SOUND_REACTIVE";
    public  static final String ACTION_STOP   = "com.halder.prowled.STOP_SOUND_REACTIVE";
    public  static final String ACTION_UPDATE = "com.halder.prowled.UPDATE_EFFECT";
    private static final String CHANNEL_ID   = "wled_sound_reactive";
    private static final int    NOTIF_ID     = 1337;

    // ── Audio / FFT constants (matching WLED audio_reactive.cpp) ──────────────
    private static final int   SAMPLE_RATE   = 22050;
    private static final int   FFT_SIZE      = 512;
    private static final int   NUM_CH        = 16;
    private static final float FFT_DOWNSCALE = 0.46f;
    private static final float[] PINK = {
        1.70f,1.71f,1.73f,1.78f,1.68f,1.56f,1.55f,1.63f,
        1.79f,1.62f,1.80f,2.06f,2.47f,3.35f,6.83f,9.55f
    };
    private static final long FRAME_MS   = 33;  // ~30fps — relaxed, steady rhythm
    private static final int  WLED_PORT  = 21324;

    // ── Config ─────────────────────────────────────────────────────────────────
    private volatile String  wledIp      = "192.168.1.100";
    private volatile int     ledCount    = 60;
    private volatile float   sensitivity = 5f;
    private volatile float   noiseFloor  = 0.10f;
    private volatile int     brightness  = 255;
    private volatile float   minLightPct = 1.0f; // percent 0.039–10
    private volatile String  effect      = "geq";
    private volatile String  colorMode   = "single";
    private volatile int[]   singleColor = {255, 0, 0};
    private volatile int[][] comboColors = {};
    private volatile int[]   gradStart   = {255, 0, 0};
    private volatile int[]   gradEnd     = {0, 0, 255};

    // ── FFT / AGC state ────────────────────────────────────────────────────────
    private final float[] fftCalc   = new float[NUM_CH];
    private final float[] fftAvg    = new float[NUM_CH];
    private final int[]   fftResult = new int[NUM_CH];
    private float sampleAvg = 0f;
    private float multAgc   = 1f;
    private float sampleMax = 0f;
    private static final float AGC_TARGET = 0.5f;
    private static final float AGC_FOLLOW = 0.004f;

    // ── Effect animation state ─────────────────────────────────────────────────
    private int     frameCount   = 0;
    private byte[]  ledBuf       = new byte[0];
    // ripple
    private float  ripplePos    = -1f;
    private float  rippleSpeed  = 0f;
    private float  rippleHue    = 0f;
    private float  rippleAmp    = 0f;
    private float  prevBass     = 0f;
    // strobe
    private boolean strobeOn    = false;
    private long    strobeMs    = 0;

    // ── Runtime ────────────────────────────────────────────────────────────────
    private AudioRecord      audioRecord;
    private volatile boolean running = false;
    private ExecutorService  audioThread;
    private DatagramSocket   udpSocket;
    private long             lastFrameMs = 0;

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }

        if (ACTION_UPDATE.equals(intent.getAction())) {
            // Hot-swap effect, color, and tuning — audio keeps running untouched
            effect      = str(intent, "effect",    effect);
            colorMode   = str(intent, "colorMode", colorMode);
            brightness  = intent.getIntExtra("brightness",  brightness);
            sensitivity = intent.getIntExtra("sensitivity", (int)sensitivity);
            noiseFloor  = intent.getIntExtra("noise_floor", (int)(noiseFloor*100)) / 100f;
            minLightPct = intent.getFloatExtra("min_light", minLightPct);
            singleColor = new int[]{ intent.getIntExtra("colorR", singleColor[0]), intent.getIntExtra("colorG", singleColor[1]), intent.getIntExtra("colorB", singleColor[2]) };
            gradStart   = new int[]{ intent.getIntExtra("gradR1", gradStart[0]),   intent.getIntExtra("gradG1", gradStart[1]),   intent.getIntExtra("gradB1", gradStart[2]) };
            gradEnd     = new int[]{ intent.getIntExtra("gradR2", gradEnd[0]),     intent.getIntExtra("gradG2", gradEnd[1]),     intent.getIntExtra("gradB2", gradEnd[2]) };
            try {
                String j = intent.getStringExtra("comboColors");
                if (j != null && !j.isEmpty()) {
                    JSONArray a = new JSONArray(j);
                    comboColors = new int[a.length()][3];
                    for (int i = 0; i < a.length(); i++) {
                        JSONArray c = a.getJSONArray(i);
                        comboColors[i] = new int[]{ c.getInt(0), c.getInt(1), c.getInt(2) };
                    }
                }
            } catch (Exception ignored) {}
            Log.d(TAG, "Effect updated → " + effect + " / " + colorMode);
            return START_STICKY;
        }

        wledIp      = str(intent, "wled_ip",   wledIp);
        ledCount    = intent.getIntExtra("led_count",   ledCount);
        sensitivity = intent.getIntExtra("sensitivity", 5);
        noiseFloor  = intent.getIntExtra("noise_floor", 10) / 100f;
        brightness   = intent.getIntExtra("brightness",  255);
        minLightPct  = intent.getFloatExtra("min_light", 1.0f);
        effect      = str(intent, "effect",    effect);
        colorMode   = str(intent, "colorMode", colorMode);
        singleColor = new int[]{ intent.getIntExtra("colorR",255), intent.getIntExtra("colorG",0), intent.getIntExtra("colorB",0) };
        gradStart   = new int[]{ intent.getIntExtra("gradR1",255), intent.getIntExtra("gradG1",0), intent.getIntExtra("gradB1",0) };
        gradEnd     = new int[]{ intent.getIntExtra("gradR2",0),   intent.getIntExtra("gradG2",0), intent.getIntExtra("gradB2",255) };
        try {
            String j = intent.getStringExtra("comboColors");
            if (j != null && !j.isEmpty()) {
                JSONArray a = new JSONArray(j);
                comboColors = new int[a.length()][3];
                for (int i = 0; i < a.length(); i++) {
                    JSONArray c = a.getJSONArray(i);
                    comboColors[i] = new int[]{ c.getInt(0), c.getInt(1), c.getInt(2) };
                }
            }
        } catch (Exception ignored) {}

        startForeground(NOTIF_ID, buildNotification());
        if (running) {
            // Audio thread already running — config fields updated above are
            // read directly by the loop on the next frame. No restart needed.
            return START_STICKY;
        }
        ledBuf = new byte[ledCount * 3];
        startAudio();
        return START_STICKY;
    }

    private String str(Intent i, String k, String d) { String v=i.getStringExtra(k); return v!=null?v:d; }

    @Override
    public void onDestroy() {
        running = false;
        if (audioRecord  != null) { try { audioRecord.stop(); audioRecord.release(); } catch (Exception e) {} audioRecord = null; }
        if (audioThread  != null) audioThread.shutdownNow();
        if (udpSocket    != null) { udpSocket.close(); udpSocket = null; }
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent i) { return null; }

    // ── Notification ───────────────────────────────────────────────────────────

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "WLED Sound Reactive", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WLED Sound Reactive")
            .setContentText(effect + " · " + colorMode + " · " + ledCount + " LEDs")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true).build();
    }

    // ── Audio init ─────────────────────────────────────────────────────────────

    private void startAudio() {
        // Single thread — capture + FFT + render + send all in one place.
        // No second thread = no queue = no frame backlog = no flicker.
        audioThread = Executors.newSingleThreadExecutor();

        int bufSize = Math.max(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            FFT_SIZE * 4);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed"); stopSelf(); return;
        }
        try {
            udpSocket = new DatagramSocket();
            udpSocket.setSendBufferSize(8192);
        } catch (Exception e) { Log.e(TAG, "Socket failed", e); stopSelf(); return; }

        running = true;
        audioRecord.startRecording();
        audioThread.submit(this::loop);
    }

    // ── Main loop — single thread: capture → FFT → render → send ──────────────

    private void loop() {
        short[] buf = new short[FFT_SIZE];

        while (running) {
            // --- 1. Read exactly FFT_SIZE samples ---
            int read = audioRecord.read(buf, 0, FFT_SIZE);
            if (read < FFT_SIZE) continue;

            // --- 2. DC removal ---
            float[] samples = new float[FFT_SIZE];
            float dc = 0f;
            for (int i = 0; i < FFT_SIZE; i++) { samples[i] = buf[i]; dc += samples[i]; }
            dc /= FFT_SIZE;
            float maxSample = 0f;
            for (int i = 0; i < FFT_SIZE; i++) {
                samples[i] -= dc;
                float a = Math.abs(samples[i]); if (a > maxSample) maxSample = a;
            }

            // --- 3. AGC ---
            if (maxSample > sampleMax) sampleMax = maxSample; else sampleMax *= 0.9997f;
            if (sampleMax > 1f) {
                float tgt = (AGC_TARGET * 32768f) / sampleMax;
                multAgc += AGC_FOLLOW * (tgt - multAgc);
                multAgc  = Math.max(0.1f, Math.min(multAgc, 40f));
            }
            float gain = multAgc * (sensitivity / 5f);
            for (int i = 0; i < FFT_SIZE; i++) samples[i] *= gain;

            // --- 4. Volume envelope (smoothed) ---
            float instantVol = maxSample * gain / 32768f;
            sampleAvg = sampleAvg * 0.92f + instantVol * 0.08f;
            boolean gateOpen = sampleAvg > noiseFloor;

            // --- 5. FFT → 16 GEQ channels ---
            if (gateOpen) {
                applyFlatTop(samples);
                float[] re = Arrays.copyOf(samples, FFT_SIZE);
                float[] im = new float[FFT_SIZE];
                fft(re, im);
                float[] mag = new float[FFT_SIZE / 2];
                mag[0] = 0;
                for (int i = 1; i < FFT_SIZE / 2; i++)
                    mag[i] = (float) Math.sqrt(re[i]*re[i] + im[i]*im[i]) / FFT_SIZE;
                mapBins(mag);
            } else {
                // Noise gate closed — decay channels toward zero
                for (int i = 0; i < NUM_CH; i++) {
                    fftCalc[i] *= 0.85f;
                    if (fftCalc[i] < 4f) fftCalc[i] = 0f;
                }
            }

            // --- 6. Post-process → smooth fftResult[] 0-255 ---
            postProcess(gateOpen);

            // --- 7. Rate-limit to FRAME_MS — skip render if too soon ---
            long now = System.currentTimeMillis();
            if (now - lastFrameMs < FRAME_MS) continue;
            lastFrameMs = now;

            // --- 8. Render effect into ledBuf, send UDP (same thread, no queue) ---
            renderAndSend();
        }
    }

    // ── Bin mapping (WLED audio_reactive.cpp @22050Hz/512) ────────────────────

    private void mapBins(float[] mag) {
        fftCalc[ 0] = avg(mag,  1,  2);
        fftCalc[ 1] = avg(mag,  2,  3);
        fftCalc[ 2] = avg(mag,  3,  5);
        fftCalc[ 3] = avg(mag,  5,  7);
        fftCalc[ 4] = avg(mag,  7, 10);
        fftCalc[ 5] = avg(mag, 10, 13);
        fftCalc[ 6] = avg(mag, 13, 19);
        fftCalc[ 7] = avg(mag, 19, 26);
        fftCalc[ 8] = avg(mag, 26, 33);
        fftCalc[ 9] = avg(mag, 33, 44);
        fftCalc[10] = avg(mag, 44, 56);
        fftCalc[11] = avg(mag, 56, 70);
        fftCalc[12] = avg(mag, 70, 86);
        fftCalc[13] = avg(mag, 86,104);
        fftCalc[14] = avg(mag,104,165) * 0.88f;
        fftCalc[15] = avg(mag,165,215) * 0.70f;
    }

    private float avg(float[] m, int from, int to) {
        to = Math.min(to, m.length-1); if (from > to) return 0f;
        float s=0f; for (int i=from; i<=to; i++) s+=m[i];
        return s / (to-from+1);
    }

    // ── Post-process (WLED postProcessFFTResults, mode=3 sqrt) ───────────────

    private void postProcess(boolean gateOpen) {
        for (int i = 0; i < NUM_CH; i++) {
            float v = fftCalc[i];
            if (gateOpen) { v *= PINK[i]; v *= FFT_DOWNSCALE; if (v < 0) v = 0; }
            // Fast attack, slow decay — this IS the flicker prevention for FFT data
            if (v > fftAvg[i]) fftAvg[i] = v * 0.75f + fftAvg[i] * 0.25f;
            else               fftAvg[i] = v * 0.17f + fftAvg[i] * 0.83f;
            fftCalc[i] = Math.max(0, Math.min(fftCalc[i], 1023f));
            fftAvg[i]  = Math.max(0, Math.min(fftAvg[i],  1023f));
            float cur = fftAvg[i] * 0.38f - 6f;
            if (cur > 1f) cur = (float) Math.sqrt(cur); else cur = 0f;
            cur *= 0.85f + (i / 4.5f);
            cur  = cur / 16f * 255f;
            fftResult[i] = Math.max(0, Math.min(255, (int) cur));
        }
    }

    // ── Effect engine ──────────────────────────────────────────────────────────
    //
    // ALL brightness values come from fftResult[] — never from raw audio.
    // fftResult[] is smoothed by postProcess() so values never jump abruptly.
    //
    // ledBuf is persistent — effects mutate it in place, trails/fades work
    // across frames naturally without any frame-to-frame resetting.
    // ──────────────────────────────────────────────────────────────────────────

    private void renderAndSend() {
        if (udpSocket == null || udpSocket.isClosed()) return;
        if (ledBuf.length != ledCount * 3) ledBuf = new byte[ledCount * 3];

        frameCount++;

        // Derive smooth float values from already-smoothed fftResult[] only
        final float bass = (fftResult[0]+fftResult[1]+fftResult[2]+fftResult[3]) / (4f*255f);
        final float mid  = (fftResult[5]+fftResult[6]+fftResult[7]+fftResult[8]) / (4f*255f);
        final float high = (fftResult[12]+fftResult[13]+fftResult[14]+fftResult[15]) / (4f*255f);
        // sampleAvg smoothed with 0.92/0.08 in loop() — safe for volume
        final float vol  = Math.min(sampleAvg * 2.5f, 1f);

        switch (effect) {

            // ── GEQ Spectrum ───────────────────────────────────────────────────
            case "geq": {
                for (int led = 0; led < ledCount; led++) {
                    int zone  = Math.min((led * NUM_CH) / ledCount, NUM_CH-1);
                    float val = fftResult[zone] / 255f;
                    float hue = (float) zone / (NUM_CH-1) * 0.75f;
                    put(ledBuf, led, hsv(hue, 1f, val));
                }
                break;
            }

            // ── Ripple ─────────────────────────────────────────────────────────
            case "ripple": {
                if (bass > 0.45f && bass > prevBass + 0.12f) {
                    ripplePos = 0f; rippleSpeed = 0.025f + bass*0.04f;
                    rippleHue = (frameCount * 0.11f) % 1f; rippleAmp = 0.9f;
                }
                prevBass = bass;
                if (ripplePos >= 0) { ripplePos += rippleSpeed; rippleAmp *= 0.94f; if (ripplePos > 1f) ripplePos = -1f; }
                int center = ledCount / 2;
                for (int led = 0; led < ledCount; led++) {
                    float dist  = Math.abs(led - center) / (float) center;
                    float glow  = ripplePos >= 0 ? Math.max(0f, 1f - Math.abs(dist-ripplePos)*12f) * rippleAmp : 0f;
                    float bri   = Math.min(1f, glow + vol*0.15f);
                    int[] mc    = colorAt(led);
                    put(ledBuf, led, (int)(mc[0]*bri), (int)(mc[1]*bri), (int)(mc[2]*bri));
                }
                break;
            }

            // ── Strobe ─────────────────────────────────────────────────────────
            case "strobe": {
                long nowMs = System.currentTimeMillis();
                if (bass > 0.70f && nowMs - strobeMs > 100) { strobeOn = true; strobeMs = nowMs; }
                if (nowMs - strobeMs > 55) strobeOn = false;
                // Fade in persistent buffer
                for (int i = 0; i < ledBuf.length; i++)
                    ledBuf[i] = (byte)((ledBuf[i] & 0xFF) * 180 / 255);
                if (strobeOn) {
                    int[] mc = colorAt(0);
                    for (int led = 0; led < ledCount; led++) put(ledBuf, led, mc[0], mc[1], mc[2]);
                }
                break;
            }

            // ── VU Meter ───────────────────────────────────────────────────────
            case "vuMeter": {
                int lit = Math.min((int)(vol * ledCount), ledCount);
                for (int led = 0; led < ledCount; led++) {
                    if (led < lit) {
                        float pos = (float) led / ledCount;
                        int[] vu  = hsv(0.33f - pos*0.33f, 1f, 1f);
                        int[] mc  = colorAt(led);
                        put(ledBuf, led, (vu[0]+mc[0])/2, (vu[1]+mc[1])/2, (vu[2]+mc[2])/2);
                    } else {
                        // Smooth fade to black — no hard cut
                        ledBuf[led*3]   = (byte)((ledBuf[led*3]   & 0xFF) * 200 / 255);
                        ledBuf[led*3+1] = (byte)((ledBuf[led*3+1] & 0xFF) * 200 / 255);
                        ledBuf[led*3+2] = (byte)((ledBuf[led*3+2] & 0xFF) * 200 / 255);
                    }
                }
                break;
            }

            // ── Twinkle ────────────────────────────────────────────────────────
            case "twinkle": {
                // Decay all pixels in persistent buffer each frame
                for (int i = 0; i < ledBuf.length; i++)
                    ledBuf[i] = (byte)((ledBuf[i] & 0xFF) * 215 / 255);
                int stars = (int)(high*8f + bass*2f);
                for (int s = 0; s < stars; s++) {
                    int led = (int)(Math.random() * ledCount);
                    float bri = 0.55f + (float)Math.random()*0.45f;
                    int[] mc  = colorAt(led);
                    put(ledBuf, led, (int)(mc[0]*bri), (int)(mc[1]*bri), (int)(mc[2]*bri));
                }
                break;
            }

            // ── GEQ fallback (default) ─────────────────────────────────────────
            default: {
                for (int led = 0; led < ledCount; led++) {
                    int zone  = Math.min((led * NUM_CH) / ledCount, NUM_CH-1);
                    float val = fftResult[zone] / 255f;
                    float hue = (float) zone / (NUM_CH-1) * 0.75f;
                    put(ledBuf, led, hsv(hue, 1f, val));
                }
                break;
            }
        }

        // ── Send DRGB packet ───────────────────────────────────────────────────
        try {
            byte[] pkt = new byte[2 + ledCount*3];
            pkt[0] = 2; pkt[1] = 2; // DRGB, 2s timeout
            for (int i = 0; i < ledCount; i++) {
                pkt[2+i*3]   = (byte) sc(ledBuf[i*3]   & 0xFF);
                pkt[2+i*3+1] = (byte) sc(ledBuf[i*3+1] & 0xFF);
                pkt[2+i*3+2] = (byte) sc(ledBuf[i*3+2] & 0xFF);
            }
            InetAddress addr = InetAddress.getByName(wledIp);
            udpSocket.send(new DatagramPacket(pkt, pkt.length, addr, WLED_PORT));
        } catch (Exception e) { Log.e(TAG, "UDP error", e); }
    }

    // ── Color for a given LED based on colorMode ───────────────────────────────

    private int[] colorAt(int led) {
        switch (colorMode) {
            case "combo": {
                if (comboColors == null || comboColors.length == 0) return singleColor;
                return comboColors[Math.abs(led) % comboColors.length];
            }
            case "gradient": {
                float t = ledCount > 1 ? (float)(led % ledCount) / (ledCount-1) : 0f;
                return new int[]{
                    (int)(gradStart[0] + t*(gradEnd[0]-gradStart[0])),
                    (int)(gradStart[1] + t*(gradEnd[1]-gradStart[1])),
                    (int)(gradStart[2] + t*(gradEnd[2]-gradStart[2]))
                };
            }
            case "rainbow": {
                float hue = ((float)(led % ledCount) / ledCount + frameCount*0.003f) % 1f;
                return hsv(hue, 1f, 1f);
            }
            default: return singleColor;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    // Scale by master brightness, then apply min light floor
    private int sc(int v) {
        int scaled = (v * brightness) / 255;
        int floor  = Math.round(minLightPct / 100f * 255f);
        return Math.max(floor, scaled);
    }

    private void put(byte[] buf, int led, int[] rgb)          { put(buf, led, rgb[0], rgb[1], rgb[2]); }
    private void put(byte[] buf, int led, int r, int g, int b) {
        if (led < 0 || led >= ledCount) return;
        buf[led*3]   = (byte) Math.max(0, Math.min(255, r));
        buf[led*3+1] = (byte) Math.max(0, Math.min(255, g));
        buf[led*3+2] = (byte) Math.max(0, Math.min(255, b));
    }

    // ── DSP ────────────────────────────────────────────────────────────────────

    private void applyFlatTop(float[] d) {
        int n = d.length;
        for (int i = 0; i < n; i++) {
            double x = 2.0 * Math.PI * i / (n-1);
            d[i] *= (float)(0.21557895 - 0.41663158*Math.cos(x)
                          + 0.27726316*Math.cos(2*x) - 0.08357895*Math.cos(3*x)
                          + 0.00694737*Math.cos(4*x));
        }
    }

    private void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i=1,j=0; i<n; i++) {
            int bit=n>>1; for(;(j&bit)!=0;bit>>=1) j^=bit; j^=bit;
            if (i<j) { float t=re[i];re[i]=re[j];re[j]=t; t=im[i];im[i]=im[j];im[j]=t; }
        }
        for (int len=2; len<=n; len<<=1) {
            double ang=2*Math.PI/len; float wr=(float)Math.cos(ang),wi=(float)Math.sin(ang);
            for (int i=0; i<n; i+=len) {
                float cr=1,ci=0;
                for (int j=0; j<len/2; j++) {
                    float ur=re[i+j],ui=im[i+j];
                    float vr=re[i+j+len/2]*cr-im[i+j+len/2]*ci;
                    float vi=re[i+j+len/2]*ci+im[i+j+len/2]*cr;
                    re[i+j]=ur+vr; im[i+j]=ui+vi;
                    re[i+j+len/2]=ur-vr; im[i+j+len/2]=ui-vi;
                    float nr=cr*wr-ci*wi; ci=cr*wi+ci*wr; cr=nr;
                }
            }
        }
    }

    private int[] hsv(float h, float s, float v) {
        int hi=(int)(h*6f)%6; float f=h*6f-(int)(h*6f);
        float p=v*(1f-s),q=v*(1f-f*s),t=v*(1f-(1f-f)*s);
        float r,g,b;
        switch(hi){case 0:r=v;g=t;b=p;break;case 1:r=q;g=v;b=p;break;
                   case 2:r=p;g=v;b=t;break;case 3:r=p;g=q;b=v;break;
                   case 4:r=t;g=p;b=v;break;default:r=v;g=p;b=q;break;}
        return new int[]{(int)(r*255),(int)(g*255),(int)(b*255)};
    }
}
