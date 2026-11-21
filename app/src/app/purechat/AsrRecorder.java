/*
 * 语音输入：用 android.media.AudioRecord 采集 16 kHz / 16 bit / 单声道 PCM，
 * 录完后在内存里封装成合法的 WAV（44 字节 RIFF 头）。
 *
 * 约束：
 * - 只用 android.media.*，不引入任何第三方库；
 * - 录音数据全部驻留内存（120 秒 16 kHz/16 bit 单声道约 3.84 MB，远小于接口 8 MiB 上限）；
 * - 单次录音最长 120 秒，超时由采集线程自行停止，并通过回调交回已录到的音频。
 */
package app.purechat;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;

import java.io.ByteArrayOutputStream;

/**
 * 录音器：采集 16 kHz / 16 bit / 单声道 PCM，并在内存中封装成 WAV。
 *
 * <p>生命周期：{@link #start(Listener)} → （最长 120 秒）→ 回调 {@link Listener#onFinished}
 * 或主动 {@link #stop()} / {@link #stopAndGetWav()}。结束时一定要调用 {@link #release()} 释放底层资源。</p>
 *
 * <p>线程模型：采集在独立线程中进行，{@code start/stop} 可在任意线程调用；
 * 回调也在采集线程触发，调用方需要自行切回 UI 线程。</p>
 */
public final class AsrRecorder {

    /** 运行时权限请求码，由 MainActivity 透传回 {@link #onRequestPermissionsResult}。 */
    public static final int PERMISSION_REQUEST_CODE = 0x4153;

    /** 录音时长上限（毫秒），与接口最长 120 秒对齐。 */
    public static final int MAX_DURATION_MS = 120000;

    /** 采样率，与 {@link AsrClient#SAMPLE_RATE} 一致。 */
    public static final int SAMPLE_RATE = AsrClient.SAMPLE_RATE;

    /** 声道数：单声道。 */
    public static final int CHANNELS = AsrClient.CHANNELS;

    /** 位深：16 位。 */
    public static final int BITS_PER_SAMPLE = AsrClient.BITS_PER_SAMPLE;

    /** 每 100 毫秒检查一次是否到达时长上限。 */
    private static final int POLL_BYTES = SAMPLE_RATE * (BITS_PER_SAMPLE / 8) * CHANNELS / 10;

    /** 单次读取的最小缓冲字节数。 */
    private static final int MIN_READ_BYTES = 2048;

    /** 采集回调。 */
    public interface Listener {
        /**
         * 录音结束（主动停止、超时自动停止或启动失败都会触发一次）。
         *
         * @param wav        完整的 WAV 字节；失败时为 null
         * @param durationMs 实际录音时长（毫秒）
         * @param error      失败原因，成功时为 null
         */
        void onFinished(byte[] wav, long durationMs, String error);
    }

    /** 保护状态字段。 */
    private final Object lock = new Object();

    /** 底层录音对象，仅在采集线程内创建与释放。 */
    private AudioRecord record;

    /** 采集线程。 */
    private Thread worker;

    /** 是否正在录音（含正在启动）。 */
    private volatile boolean recording;

    /** 是否已被请求停止。 */
    private volatile boolean stopRequested;

    /** 本次录音的完成通知，可为 null。 */
    private Listener listener;

    /** 采集缓存的 PCM 数据。 */
    private ByteArrayOutputStream buffer;

    /** 最近一次录音的结果（供 {@link #stopAndGetWav()} 取用）。 */
    private byte[] lastWav;

    /** 最近一次录音的失败原因。 */
    private String lastError;

    /** 最近一次录音的实际时长（毫秒）。 */
    private long lastDurationMs;

    /** 是否已释放。 */
    private boolean released;

    /**
     * 检查是否已获得录音权限。
     *
     * @param context 任意 Context
     * @return 已授权返回 true
     */
    public static boolean hasPermission(Context context) {
        if (context == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 通过 Activity.requestPermissions 申请录音权限（必须在 UI 线程调用）。
     *
     * @param activity 承载 WebView 的 Activity
     * @return 立即发起申请返回 true；已有权限或无法申请返回 false
     */
    public static boolean requestPermission(final Activity activity) {
        if (activity == null) {
            return false;
        }
        if (hasPermission(activity)) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                activity.requestPermissions(
                        new String[] {Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
            }
        });
        return true;
    }

    /**
     * 权限申请结果回调，由 MainActivity.onRequestPermissionsResult 转交。
     *
     * @param requestCode  请求码
     * @param permissions  被申请的权限
     * @param grantResults 授权结果
     * @return 是自己处理的请求码返回 true
     */
    public static boolean onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        if (requestCode != PERMISSION_REQUEST_CODE) {
            return false;
        }
        return true;
    }

    /**
     * 开始录音。
     *
     * <p>本方法立即返回，真正的采集在独立线程里进行；录音满 120 秒后会自动停止并回调。</p>
     *
     * @param listener 完成回调，可为 null
     * @return 已开始返回 true；已在录音或已释放返回 false
     */
    public boolean start(Listener listener) {
        synchronized (lock) {
            if (released || recording) {
                return false;
            }
            recording = true;
            stopRequested = false;
            this.listener = listener;
            buffer = new ByteArrayOutputStream(SAMPLE_RATE * 2 * 8);
            lastWav = null;
            lastError = null;
            lastDurationMs = 0;
        }
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                capture();
            }
        }, "purechat-asr-record");
        worker.start();
        return true;
    }

    /** 是否正在录音。 */
    public boolean isRecording() {
        return recording;
    }

    /**
     * 请求停止录音（不阻塞）；采集线程收尾后触发 {@link Listener#onFinished}。
     */
    public void stop() {
        synchronized (lock) {
            stopRequested = true;
            lock.notifyAll();
        }
    }

    /**
     * 请求停止录音并阻塞等待采集线程结束。
     *
     * <p>用于没有注册 Listener 的调用方；若在 UI 线程调用请改用 {@link #stop()}。</p>
     *
     * @return 完整 WAV 字节；没有录到数据或失败时返回 null
     */
    public byte[] stopAndGetWav() {
        Thread current;
        synchronized (lock) {
            stopRequested = true;
            lock.notifyAll();
            current = worker;
        }
        if (current != null && current != Thread.currentThread()) {
            try {
                current.join(3000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (lock) {
            return lastWav;
        }
    }

    /** 最近一次录音的失败原因，成功为 null。 */
    public String lastError() {
        synchronized (lock) {
            return lastError;
        }
    }

    /** 释放底层资源：停止录音、回收 AudioRecord。可重复调用。 */
    public void release() {
        stop();
        AudioRecord toRelease;
        synchronized (lock) {
            released = true;
            toRelease = record;
            record = null;
        }
        if (toRelease != null) {
            try {
                toRelease.stop();
            } catch (IllegalStateException ignored) {
                // 未开始录音时 stop 会抛，忽略。
            }
            toRelease.release();
        }
    }

    // ------------------------------------------------------------------
    // 采集线程
    // ------------------------------------------------------------------

    /** 在采集线程里创建 AudioRecord、循环读取 PCM，结束时回调。 */
    private void capture() {
        byte[] wav = null;
        String error = null;
        long durationMs = 0;
        AudioRecord audioRecord = null;
        try {
            int minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBytes <= 0) {
                throw new IllegalStateException("设备不支持 16 kHz 单声道录音");
            }
            int bufferBytes = Math.max(minBytes * 2, MIN_READ_BYTES * 2);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("录音器初始化失败，请检查录音权限");
            }
            synchronized (lock) {
                if (released) {
                    throw new IllegalStateException("录音器已释放");
                }
                record = audioRecord;
            }
            audioRecord.startRecording();
            byte[] chunk = new byte[Math.max(POLL_BYTES, MIN_READ_BYTES)];
            long startedAt = System.currentTimeMillis();
            long total = 0;
            while (!stopRequested) {
                int read = audioRecord.read(chunk, 0, chunk.length);
                if (read < 0) {
                    throw new IllegalStateException("读取麦克风数据失败（错误码 " + read + "）");
                }
                if (read == 0) {
                    continue;
                }
                synchronized (lock) {
                    if (buffer != null) {
                        buffer.write(chunk, 0, read);
                    }
                }
                total += read;
                durationMs = Math.max(System.currentTimeMillis() - startedAt,
                        total * 1000L / (SAMPLE_RATE * (BITS_PER_SAMPLE / 8) * CHANNELS));
                if (durationMs >= MAX_DURATION_MS) {
                    stopRequested = true;
                }
            }
            byte[] pcm;
            synchronized (lock) {
                pcm = buffer == null ? new byte[0] : buffer.toByteArray();
                buffer = null;
            }
            if (pcm.length == 0) {
                throw new IllegalStateException("没有录到音频数据");
            }
            durationMs = pcm.length * 1000L / (SAMPLE_RATE * (BITS_PER_SAMPLE / 8) * CHANNELS);
            wav = AsrClient.buildWav(pcm, SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE);
        } catch (SecurityException e) {
            error = "未授予录音权限";
        } catch (RuntimeException e) {
            error = e.getMessage() == null ? "录音失败" : e.getMessage();
        } finally {
            if (audioRecord != null) {
                try {
                    if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop();
                    }
                } catch (IllegalStateException ignored) {
                    // 已经停止，忽略。
                }
                audioRecord.release();
            }
            synchronized (lock) {
                if (record == audioRecord) {
                    record = null;
                }
                recording = false;
                stopRequested = false;
                lastWav = wav;
                lastError = error;
                lastDurationMs = durationMs;
                lock.notifyAll();
            }
        }
        Listener target;
        synchronized (lock) {
            target = listener;
            listener = null;
        }
        if (target != null) {
            target.onFinished(wav, durationMs, error);
        }
    }
}
