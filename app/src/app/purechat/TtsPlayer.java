package app.purechat;

import android.media.AudioAttributes;
import android.media.MediaDataSource;
import android.media.MediaPlayer;
import android.os.Build;

import java.io.IOException;

/**
 * 用系统 {@link MediaPlayer} 播放 TTS 返回的 WAV 字节。
 *
 * <p>音频没有落盘：通过内存数据源直接交给播放器，播放结束即释放。
 * 同一时刻只播放一段，新的播放会先停掉旧的。
 */
final class TtsPlayer {

    /** 播放状态回调；所有回调都在主线程。 */
    interface Listener {
        /** 正常播放结束。 */
        void onEnded();

        /** 播放失败，参数为可读原因。 */
        void onError(String message);
    }

    /** 当前播放器；没有播放时为 null。 */
    private MediaPlayer player;

    /** 内存音频数据源；播放器读取期间需要保持引用。 */
    private ByteArrayDataSource source;

    /** 当前使用的回调。 */
    private Listener listener;

    /** 是否正在播放。 */
    boolean isPlaying() {
        try {
            return player != null && player.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /**
     * 播放一段 WAV 音频。
     *
     * @param wav      WAV 字节（由 {@link TtsClient} 负责校验与规整）
     * @param listener 播放状态回调
     */
    void play(final byte[] wav, final Listener listener) {
        stop();
        if (wav == null || wav.length == 0) {
            if (listener != null) {
                listener.onError("没有可播放的音频");
            }
            return;
        }
        try {
            MediaPlayer created = new MediaPlayer();
            created.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            ByteArrayDataSource data = new ByteArrayDataSource(wav);
            created.setDataSource(data);
            created.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    releaseQuietly();
                    Listener callback = TtsPlayer.this.listener;
                    TtsPlayer.this.listener = null;
                    if (callback != null) {
                        callback.onEnded();
                    }
                }
            });
            created.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    releaseQuietly();
                    Listener callback = TtsPlayer.this.listener;
                    TtsPlayer.this.listener = null;
                    if (callback != null) {
                        callback.onError("音频播放失败（" + what + "/" + extra + "）");
                    }
                    return true;
                }
            });
            created.prepare();
            created.start();
            player = created;
            source = data;
            this.listener = listener;
        } catch (IOException e) {
            releaseQuietly();
            if (listener != null) {
                listener.onError("音频无法播放：" + e.getMessage());
            }
        } catch (IllegalStateException e) {
            releaseQuietly();
            if (listener != null) {
                listener.onError("播放器状态异常，请重试");
            }
        } catch (RuntimeException e) {
            releaseQuietly();
            if (listener != null) {
                listener.onError("音频无法播放：" + e.getMessage());
            }
        }
    }

    /** 停止当前播放（不触发结束回调）。 */
    void stop() {
        listener = null;
        if (player != null) {
            try {
                if (player.isPlaying()) {
                    player.stop();
                }
            } catch (IllegalStateException ignored) {
                // 已经停止或释放，忽略。
            }
        }
        releaseQuietly();
    }

    /** 释放播放器与数据源。 */
    void release() {
        stop();
    }

    /** 释放底层资源并清空引用。 */
    private void releaseQuietly() {
        if (player != null) {
            try {
                player.reset();
            } catch (IllegalStateException ignored) {
                // 状态已失效，直接释放。
            }
            try {
                player.release();
            } catch (RuntimeException ignored) {
                // 释放失败无需处理。
            }
            player = null;
        }
        source = null;
    }

    /** 把内存里的 WAV 字节包装成 MediaPlayer 可读的数据源（要求 API 23+）。 */
    private static final class ByteArrayDataSource extends MediaDataSource {

        private final byte[] data;

        ByteArrayDataSource(byte[] data) {
            this.data = data;
        }

        @Override
        public int readAt(long position, byte[] buffer, int offset, int size) {
            if (position >= data.length) {
                return -1;
            }
            int length = (int) Math.min(size, data.length - position);
            System.arraycopy(data, (int) position, buffer, offset, length);
            return length;
        }

        @Override
        public long getSize() {
            return data.length;
        }

        @Override
        public void close() {
            // 内存数据源无需释放。
        }
    }
}
