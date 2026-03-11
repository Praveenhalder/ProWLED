package com.halder.prowled;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SoundReactiveActivity
 *
 * Captures system audio (Android 10+ via MediaProjection) or microphone (fallback),
 * performs FFT analysis to extract bass / mid / high frequency bands,
 * then sends WLED JSON API commands to sync LED colors and brightness.
 *
 * Usage from Capacitor plugin:
 *   Intent intent = new Intent(context, SoundReactiveActivity.class);
 *   intent.putExtra("wled_ip", "192.168.1.100");
 *   intent.putExtra("led_count", 60);
 *   intent.putExtra("use_system_audio", true);  // requires Android 10+
 *   context.startActivity(intent);
 */
public class SoundReactiveActivity extends Activity {

    private static final String TAG = "SoundReactive";

    // Audio config
    private static final int SAMPLE_RATE     = 44100;
    private static final int CHANNEL_CONFIG  = AudioFormat.CHANNEL_IN_STEREO;
    private static final int AUDIO_FORMAT    = AudioFormat.ENCODING_PCM_16BIT;
    private static final int FFT_SIZE        = 1024; // must be power of 2
    private static final int BUFFER_SIZE_MULTIPLIER = 4;

    // MediaProjection request code
    private static final int REQUEST_MEDIA_PROJECTION = 1001;

    // State
    private AudioRecord       audioRecord;
    private MediaProjection   mediaProjection;
    private MediaProjectionManager projectionManager;
    private boolean           isRecording   = false;
    private String            wledIp        = "192.168.1.100";
    private int               ledCount      = 60;
    private boolean           useSystemAudio = false;

    private ExecutorService   audioExecutor;
    private ExecutorService   networkExecutor;
    private Handler           mainHandler;

    // Smoothing state (exponential moving average)
    private float smoothBass = 0f;
    private float smoothMid  = 0f;
    private float smoothHigh = 0f;
    private static final float SMOOTH_FACTOR = 0.3f; // 0=no update, 1=no smoothing

    // -----------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mainHandler    = new Handler(Looper.getMainLooper());
        audioExecutor  = Executors.newSingleThreadExecutor();
        networkExecutor= Executors.newSingleThreadExecutor();

        // Read extras passed from Capacitor
        if (getIntent() != null) {
            wledIp         = getIntent().getStringExtra("wled_ip") != null
                             ? getIntent().getStringExtra("wled_ip") : wledIp;
            ledCount       = getIntent().getIntExtra("led_count", ledCount);
            useSystemAudio = getIntent().getBooleanExtra("use_system_audio", false);
        }

        if (useSystemAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requestSystemAudioPermission();
        } else {
            startMicCapture();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCapture();
        if (audioExecutor  != null) audioExecutor.shutdownNow();
        if (networkExecutor!= null) networkExecutor.shutdownNow();
        if (mediaProjection != null) mediaProjection.stop();
    }

    // -----------------------------------------------------------------
    // Permission / Setup
    // -----------------------------------------------------------------

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void requestSystemAudioPermission() {
        projectionManager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(
                projectionManager.createScreenCaptureIntent(),
                REQUEST_MEDIA_PROJECTION
        );
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mediaProjection = projectionManager.getMediaProjection(resultCode, data);
                startSystemAudioCapture();
            } else {
                Log.w(TAG, "System audio permission denied, falling back to mic");
                startMicCapture();
            }
        }
    }

    // -----------------------------------------------------------------
    // Audio capture — Microphone
    // -----------------------------------------------------------------

    private void startMicCapture() {
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                         * BUFFER_SIZE_MULTIPLIER;

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
        );
        startRecordingLoop(bufferSize);
    }

    // -----------------------------------------------------------------
    // Audio capture — System audio (Android 10+)
    // -----------------------------------------------------------------

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void startSystemAudioCapture() {
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                         * BUFFER_SIZE_MULTIPLIER;

        android.media.AudioPlaybackCaptureConfiguration captureConfig =
                new android.media.AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                        .build();

        android.media.AudioFormat audioFormat = new android.media.AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG)
                .build();

        audioRecord = new AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build();

        startRecordingLoop(bufferSize);
    }

    // -----------------------------------------------------------------
    // Core recording loop
    // -----------------------------------------------------------------

    private void startRecordingLoop(final int bufferSize) {
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize");
            return;
        }
        isRecording = true;
        audioRecord.startRecording();
        Log.i(TAG, "Recording started. Buffer=" + bufferSize);

        audioExecutor.submit(() -> {
            short[] buffer = new short[FFT_SIZE];

            while (isRecording) {
                int read = audioRecord.read(buffer, 0, FFT_SIZE);
                if (read > 0) {
                    float[] fftInput = shortArrayToFloat(buffer, read);
                    applyHannWindow(fftInput);
                    float[] magnitude = computeFFTMagnitude(fftInput);
                    AudioBands bands   = extractBands(magnitude);
                    sendToWled(bands);
                }
            }
        });
    }

    private void stopCapture() {
        isRecording = false;
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping AudioRecord", e);
            }
            audioRecord = null;
        }
    }

    // -----------------------------------------------------------------
    // DSP helpers
    // -----------------------------------------------------------------

    /** Convert short PCM samples to normalized floats [-1, 1] */
    private float[] shortArrayToFloat(short[] src, int len) {
        float[] dst = new float[len];
        for (int i = 0; i < len; i++) {
            dst[i] = src[i] / 32768.0f;
        }
        return dst;
    }

    /** Hann window to reduce spectral leakage */
    private void applyHannWindow(float[] data) {
        int n = data.length;
        for (int i = 0; i < n; i++) {
            data[i] *= 0.5f * (1 - (float) Math.cos(2 * Math.PI * i / (n - 1)));
        }
    }

    /**
     * In-place FFT (Cooley–Tukey, radix-2 DIT).
     * Returns interleaved [re0, im0, re1, im1, ...].
     */
    private void fft(float[] re, float[] im) {
        int n = re.length;
        // Bit-reversal permutation
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float tmp = re[i]; re[i] = re[j]; re[j] = tmp;
                      tmp = im[i]; im[i] = im[j]; im[j] = tmp;
            }
        }
        // FFT butterfly
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len;
            float wRe = (float) Math.cos(ang);
            float wIm = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float curRe = 1, curIm = 0;
                for (int j = 0; j < len / 2; j++) {
                    float uRe = re[i + j],           uIm = im[i + j];
                    float vRe = re[i+j+len/2]*curRe - im[i+j+len/2]*curIm;
                    float vIm = re[i+j+len/2]*curIm + im[i+j+len/2]*curRe;
                    re[i + j]         = uRe + vRe;
                    im[i + j]         = uIm + vIm;
                    re[i+j+len/2]     = uRe - vRe;
                    im[i+j+len/2]     = uIm - vIm;
                    float newRe = curRe*wRe - curIm*wIm;
                    float newIm = curRe*wIm + curIm*wRe;
                    curRe = newRe; curIm = newIm;
                }
            }
        }
    }

    /** Compute FFT and return magnitude spectrum (first half only) */
    private float[] computeFFTMagnitude(float[] input) {
        int n = input.length;
        float[] re = java.util.Arrays.copyOf(input, n);
        float[] im = new float[n];
        fft(re, im);
        float[] mag = new float[n / 2];
        for (int i = 0; i < n / 2; i++) {
            mag[i] = (float) Math.sqrt(re[i]*re[i] + im[i]*im[i]) / n;
        }
        return mag;
    }

    // -----------------------------------------------------------------
    // Band extraction
    // -----------------------------------------------------------------

    /**
     * Split the spectrum into three perceptual bands and
     * return their normalized RMS energies [0, 1].
     *
     * Frequency bin → Hz: bin * SAMPLE_RATE / FFT_SIZE
     * Bass  : 20 – 300 Hz
     * Mid   : 300 – 4000 Hz
     * High  : 4000 – 20000 Hz
     */
    private AudioBands extractBands(float[] mag) {
        int bassEnd = freqToBin(300);
        int midEnd  = freqToBin(4000);
        int highEnd = Math.min(freqToBin(20000), mag.length - 1);

        float bass = rms(mag, freqToBin(20),  bassEnd);
        float mid  = rms(mag, bassEnd,          midEnd);
        float high = rms(mag, midEnd,           highEnd);

        // Normalize roughly (tweak multipliers to taste)
        bass = Math.min(bass * 60f, 1f);
        mid  = Math.min(mid  * 40f, 1f);
        high = Math.min(high * 80f, 1f);

        // Exponential moving average smoothing
        smoothBass = smoothBass + SMOOTH_FACTOR * (bass - smoothBass);
        smoothMid  = smoothMid  + SMOOTH_FACTOR * (mid  - smoothMid);
        smoothHigh = smoothHigh + SMOOTH_FACTOR * (high - smoothHigh);

        return new AudioBands(smoothBass, smoothMid, smoothHigh);
    }

    private int freqToBin(int hz) {
        return Math.max(0, (int)((long) hz * FFT_SIZE / SAMPLE_RATE));
    }

    private float rms(float[] data, int start, int end) {
        if (start >= end) return 0f;
        float sum = 0;
        for (int i = start; i < end; i++) sum += data[i] * data[i];
        return (float) Math.sqrt(sum / (end - start));
    }

    // -----------------------------------------------------------------
    // WLED JSON API
    // -----------------------------------------------------------------

    private void sendToWled(AudioBands bands) {
        networkExecutor.submit(() -> {
            try {
                String json = buildWledJson(bands);
                postJson("http://" + wledIp + "/json/state", json);
            } catch (Exception e) {
                Log.e(TAG, "WLED send error", e);
            }
        });
    }

    /**
     * Build WLED JSON from audio bands.
     *
     * Strategy:
     *  - Brightness (bri) driven by overall loudness
     *  - Primary color (seg[0].col[0]) shifts from blue (quiet) → red (bass)
     *  - Secondary color (seg[0].col[1]) reflects mids (green)
     *  - Effect speed (sx) follows highs for sparkle/glitter effects
     *  - Uses "Colorwave" or "Fire" effect that responds well to color changes
     *
     * You can swap the effect ID (fx) to any WLED effect number.
     * Effect 65 = "Colorwaves", 66 = "BPM", 45 = "Fire Flicker"
     */
    private String buildWledJson(AudioBands b) throws Exception {
        // Overall loudness
        float loudness = (b.bass * 0.5f + b.mid * 0.3f + b.high * 0.2f);
        int bri = Math.max(10, (int)(loudness * 255));

        // Primary color: bass → red channel, mid → green, high → blue
        int r = (int)(b.bass * 255);
        int g = (int)(b.mid  * 255);
        int blue = (int)(b.high * 255);

        // Secondary color: complementary (inverted)
        int r2 = 255 - r;
        int g2 = 255 - g;
        int b2 = 255 - blue;

        // Effect speed driven by high frequencies (sparkle rate)
        int sx = (int)(b.high * 200) + 30;

        // Transition 0 for instant reactivity
        JSONObject state = new JSONObject();
        state.put("on",  true);
        state.put("bri", bri);
        state.put("tt",  0);  // instant transition for this call only

        JSONObject seg = new JSONObject();
        seg.put("id", 0);
        seg.put("fx", 65);   // Colorwaves — swap to taste
        seg.put("sx", sx);
        seg.put("ix", (int)(loudness * 200) + 30);

        // Colors: primary, secondary, tertiary
        JSONArray colors = new JSONArray();
        colors.put(new JSONArray(new int[]{r,  g,  blue}));
        colors.put(new JSONArray(new int[]{r2, g2, b2}));
        colors.put(new JSONArray(new int[]{(r+r2)/2, (g+g2)/2, (blue+b2)/2}));
        seg.put("col", colors);

        JSONArray segs = new JSONArray();
        segs.put(seg);
        state.put("seg", segs);

        return state.toString();
    }

    private void postJson(String urlString, String json) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(500);
        conn.setReadTimeout(500);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            Log.w(TAG, "WLED HTTP " + code);
        }
        conn.disconnect();
    }

    // -----------------------------------------------------------------
    // Inner types
    // -----------------------------------------------------------------

    private static class AudioBands {
        final float bass, mid, high;
        AudioBands(float bass, float mid, float high) {
            this.bass = bass;
            this.mid  = mid;
            this.high = high;
        }
    }
}
