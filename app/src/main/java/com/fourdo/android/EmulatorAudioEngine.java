package com.fourdo.android;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;

final class EmulatorAudioEngine {

    interface FrameDrainer {
        int drain(int[] packedFrames);
    }

    private AudioTrack audioTrack;
    private Thread audioThread;
    private volatile boolean audioRunning;

    synchronized void start(FrameDrainer frameDrainer) {
        if (audioRunning || frameDrainer == null) {
            return;
        }

        int sampleRate = 44100;
        int minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);

        if (minBufferSize <= 0) {
            return;
        }

        AudioTrack track = new AudioTrack(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                Math.max(minBufferSize * 8, sampleRate),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE);

        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            try {
                track.release();
            } catch (Exception ignored) {
            }
            return;
        }

        audioTrack = track;
        audioTrack.play();
        audioRunning = true;

        audioThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

            int[] packedFrames = new int[4096];
            short[] pcm = new short[packedFrames.length * 2];

            while (audioRunning) {
                int frameCount = frameDrainer.drain(packedFrames);
                if (frameCount > 0) {
                    for (int i = 0; i < frameCount; i++) {
                        int sample = packedFrames[i];
                        pcm[i * 2] = (short) (sample & 0xFFFF);
                        pcm[i * 2 + 1] = (short) ((sample >> 16) & 0xFFFF);
                    }
                    audioTrack.write(pcm, 0, frameCount * 2);
                } else {
                    try {
                        Thread.sleep(0, 500000);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }, "3DOOpera-AudioThread");
        audioThread.start();
    }

    synchronized void stop() {
        audioRunning = false;

        if (audioThread != null) {
            try {
                audioThread.join(250);
            } catch (InterruptedException ignored) {
            }
            audioThread = null;
        }

        if (audioTrack != null) {
            try {
                audioTrack.pause();
                audioTrack.flush();
                audioTrack.release();
            } catch (Exception ignored) {
            }
            audioTrack = null;
        }
    }
}
