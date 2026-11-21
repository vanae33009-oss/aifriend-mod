package com.aifriend.voice;

import java.util.concurrent.LinkedBlockingQueue;

import javax.sound.sampled.AudioFormat;
import javax.annotation.Nullable;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

import com.aifriend.AiFriendMod;

/**
 * Воспроизведение озвучки бота. Играет по одной реплике из очереди, громкость и стерео-баланс
 * на каждом 20-мс куске берутся из gainL/gainR, которые обновляются из игры по положению бота.
 */
final class Speaker {
    record Job(int entityId, int radius, WavDecoder.Pcm pcm) {
    }

    private final LinkedBlockingQueue<Job> queue = new LinkedBlockingQueue<>(3);
    private Thread thread;

    volatile float gainL = 1.0F;
    volatile float gainR = 1.0F;
    /** -1, если играет тест (без позиции). */
    volatile int currentEntity = -1;
    volatile int currentRadius = 32;
    volatile boolean playing;
    volatile long quietUntil;

    synchronized void enqueue(Job job) {
        while (!queue.offer(job)) {
            queue.poll(); // очередь полна - выбрасываем самую старую реплику
        }
        if (thread == null || !thread.isAlive()) {
            thread = new Thread(this::run, "AIFriend-Speaker");
            thread.setDaemon(true);
            thread.start();
        }
    }

    void clear() {
        queue.clear();
    }

    boolean isBusy() {
        return playing || System.currentTimeMillis() < quietUntil;
    }

    private void run() {
        while (true) {
            Job job;
            try {
                job = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                play(job);
            } catch (Exception e) {
                AiFriendMod.LOGGER.warn("AI Friend: audio playback failed: {}", e.toString());
            } finally {
                playing = false;
                currentEntity = -1;
                quietUntil = System.currentTimeMillis() + 500;
            }
        }
    }

    private void play(Job job) throws Exception {
        int rate = job.pcm().sampleRate();
        short[] s = job.pcm().samples();
        String device = VoiceSettings.get().outputDevice;
        Mixer mixer = findMixer(device);
        // Устройство может не уметь играть на частоте озвучки (например 24 кГц): тогда пересчитываем звук в 48 или 44,1 кГц.
        int outRate = rate;
        for (int candidate : new int[] { rate, 48_000, 44_100 }) {
            AudioFormat f = new AudioFormat(candidate, 16, 2, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, f);
            if (mixer != null ? mixer.isLineSupported(info) : AudioSystem.isLineSupported(info)) {
                outRate = candidate;
                break;
            }
        }
        if (outRate != rate) {
            s = resample(s, rate, outRate);
            rate = outRate;
        }
        AudioFormat fmt = new AudioFormat(rate, 16, 2, true, false);
        SourceDataLine line = mixer != null
                ? (SourceDataLine) mixer.getLine(new DataLine.Info(SourceDataLine.class, fmt))
                : AudioSystem.getSourceDataLine(fmt);
        line.open(fmt, rate * 4 / 2);
        line.start();
        playing = true;
        currentRadius = job.radius();
        currentEntity = job.entityId();
        try {
            int chunk = Math.max(160, rate / 50); // 20 мс
            byte[] buf = new byte[chunk * 4];
            for (int pos = 0; pos < s.length; pos += chunk) {
                int n = Math.min(chunk, s.length - pos);
                float gl = gainL;
                float gr = gainR;
                for (int i = 0; i < n; i++) {
                    int l = clip(s[pos + i] * gl);
                    int r = clip(s[pos + i] * gr);
                    buf[4 * i] = (byte) l;
                    buf[4 * i + 1] = (byte) (l >> 8);
                    buf[4 * i + 2] = (byte) r;
                    buf[4 * i + 3] = (byte) (r >> 8);
                }
                line.write(buf, 0, n * 4);
            }
            line.drain();
        } finally {
            line.stop();
            line.close();
        }
    }

    @Nullable
    private static Mixer findMixer(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            if (mi.getName().equals(name)) {
                return AudioSystem.getMixer(mi);
            }
        }
        return null; // устройство больше не подключено: играем на системном
    }

    private static short[] resample(short[] in, int from, int to) {
        int n = (int) ((long) in.length * to / from);
        short[] out = new short[n];
        double ratio = (double) from / to;
        for (int i = 0; i < n; i++) {
            double pos = i * ratio;
            int i0 = (int) pos;
            int i1 = Math.min(in.length - 1, i0 + 1);
            double frac = pos - i0;
            out[i] = (short) Math.round(in[Math.min(in.length - 1, i0)] * (1 - frac) + in[i1] * frac);
        }
        return out;
    }

    private static int clip(float v) {
        return Math.max(-32768, Math.min(32767, Math.round(v)));
    }
}
