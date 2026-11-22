package com.aifriend.voice;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

import javax.annotation.Nullable;

import com.aifriend.AiFriendMod;
import com.aifriend.MusicCatalog;
import com.aifriend.client.MusicPlayer;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import net.minecraft.client.Minecraft;

/**
 * Проигрывает в игре твои файлы (.ogg, .wav) из config/aifriend/music. Идёт на то же устройство вывода, что и голос бота,
 * и приглушается, пока бот говорит. Один трек повторяется, плейлист идёт по кругу вперемешку.
 */
public final class MusicStreamer {
    private static final int MAX_SECONDS = 15 * 60;

    private static volatile Thread thread;
    private static volatile boolean stop;
    private static volatile boolean paused;
    /** 1 - следующий, -1 - предыдущий, 0 - ничего. */
    private static volatile int cmd;
    private static volatile boolean loopOne;
    private static volatile boolean shuffleMode;
    /** Перемотка: доля трека 0..1, -1 - нет. */
    private static volatile float seekReq = -1;
    private static volatile long posSamples;
    private static volatile long totalSamples;
    private static volatile int outRate = 44100;
    private static volatile List<Path> playlist = List.of();
    private static volatile String current = "";

    private MusicStreamer() {
    }

    public static boolean isPlaying() {
        Thread t = thread;
        return t != null && t.isAlive();
    }

    public static String current() {
        return current;
    }

    public static boolean isLoop() {
        return loopOne;
    }

    public static boolean isPaused() {
        return paused;
    }

    public static boolean isShuffle() {
        return shuffleMode;
    }

    public static void toggleShuffle() {
        shuffleMode = !shuffleMode;
    }

    public static List<Path> playlist() {
        return playlist;
    }

    /** Сколько секунд проиграно (с точностью до буфера). */
    public static double positionSec() {
        return posSamples / 2.0 / Math.max(1, outRate);
    }

    public static double durationSec() {
        return isPlaying() ? totalSamples / 2.0 / Math.max(1, outRate) : 0.0;
    }

    /** Перемотать на долю трека (0..1). */
    public static void seek(float fraction) {
        seekReq = Math.max(0.0F, Math.min(0.999F, fraction));
    }

    /**
     * Играет плейлист. shuffle - перемешать, start - с какого трека, repeatOne - повторять выбранный трек без конца
     * (иначе после него идёт следующий по кругу).
     */
    public static synchronized void play(List<Path> tracks, boolean shuffle, @Nullable Path start, boolean repeatOne) {
        stop();
        if (tracks.isEmpty()) {
            return;
        }
        stop = false;
        paused = false;
        cmd = 0;
        loopOne = repeatOne;
        shuffleMode = shuffle;
        seekReq = -1;
        List<Path> list = new ArrayList<>(tracks);
        playlist = list;
        int startIndex = start == null ? (shuffle ? new java.util.Random().nextInt(list.size()) : 0) : Math.max(0, list.indexOf(start));
        Thread t = new Thread(() -> run(list, startIndex), "AIFriend-Music");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    public static synchronized void stop() {
        stop = true;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
        thread = null;
        current = "";
    }

    public static void togglePause() {
        paused = !paused;
    }

    public static void next() {
        cmd = 1;
    }

    public static void previous() {
        cmd = -1;
    }

    public static void toggleLoop() {
        loopOne = !loopOne;
    }

    private static void run(List<Path> tracks, int startIndex) {
        try {
            int n = tracks.size();
            int i = startIndex % n;
            while (!stop) {
                playOne(tracks.get(i));
                if (stop) {
                    return;
                }
                int c = cmd;
                cmd = 0;
                if (c < 0) {
                    i = (i - 1 + n) % n;
                } else if (c > 0 || !loopOne) {
                    // дальше: по порядку или случайный другой трек, если включено перемешивание
                    i = shuffleMode && n > 1 ? (i + 1 + new java.util.Random().nextInt(n - 1)) % n : (i + 1) % n;
                } // иначе тот же трек ещё раз
            }
        } catch (Exception e) {
            AiFriendMod.LOGGER.warn("AI Friend: music playback failed: {}", e.toString());
        } finally {
            if (Thread.currentThread() == thread) {
                thread = null;
                current = "";
                Minecraft.getInstance().execute(MusicPlayer::hide);
            }
        }
    }

    private static void playOne(Path file) {
        Track track;
        try {
            track = decode(file);
        } catch (Exception e) {
            AiFriendMod.LOGGER.warn("AI Friend: cannot read track '{}': {}", file.getFileName(), e.toString());
            stop = true;
            return;
        }
        String title = MusicCatalog.title(file);
        current = title;
        Path cover = MusicCatalog.cover(file);
        Minecraft.getInstance().execute(() -> MusicPlayer.showLocal(title, cover));

        String device = VoiceSettings.get().outputDevice;
        Mixer mixer = Speaker.findMixer(device);
        SourceDataLine line = Speaker.tryOpen(mixer, track.rate, null);
        if (line == null && mixer != null) {
            line = Speaker.tryOpen(null, track.rate, null);
        }
        if (line == null) {
            AiFriendMod.LOGGER.warn("AI Friend: no audio output for music");
            stop = true;
            return;
        }
        short[] data = track.stereo;
        AudioFormat fmt = line.getFormat();
        int rate = (int) fmt.getSampleRate();
        if (rate != track.rate) {
            data = Speaker.resample(data, track.rate, rate, 2);
        }
        AiFriendMod.LOGGER.info("AI Friend: playing '{}' at {} Hz", title, rate);
        line.start();
        try {
            int frames = rate / 25; // 40 мс
            byte[] buf = new byte[frames * 4];
            float gain = 1.0F;
            outRate = rate;
            totalSamples = data.length;
            posSamples = 0;
            int pos = 0;
            while (pos + 1 < data.length && !stop && cmd == 0) {
                float sr = seekReq;
                if (sr >= 0) {
                    seekReq = -1;
                    pos = ((int) (sr * data.length)) & ~1;
                    line.flush();
                }
                while (paused && !stop && cmd == 0 && seekReq < 0) {
                    line.stop();
                    Thread.sleep(80);
                }
                if (seekReq >= 0) {
                    continue;
                }
                line.start();
                float target = (float) VoiceSettings.get().musicVolume * (VoiceManager.botSpeaking() ? 0.3F : 1.0F);
                gain += (target - gain) * 0.25F; // плавно, без щелчков
                int n = Math.min(frames * 2, data.length - pos);
                for (int i = 0; i < n; i++) {
                    int v = Math.max(-32768, Math.min(32767, Math.round(data[pos + i] * gain)));
                    buf[2 * i] = (byte) v;
                    buf[2 * i + 1] = (byte) (v >> 8);
                }
                line.write(buf, 0, n * 2);
                pos += n;
                posSamples = pos;
            }
            if (!stop && cmd == 0) {
                line.drain();
            }
        } catch (InterruptedException e) {
            // остановили
        } finally {
            if (stop || cmd != 0) {
                line.flush();
            }
            line.stop();
            line.close();
        }
    }

    private record Track(short[] stereo, int rate) {
    }

    private static Track decode(Path file) throws Exception {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".wav")) {
            WavDecoder.Pcm pcm = WavDecoder.decode(Files.readAllBytes(file));
            short[] mono = pcm.samples();
            short[] st = new short[mono.length * 2];
            for (int i = 0; i < mono.length; i++) {
                st[2 * i] = mono[i];
                st[2 * i + 1] = mono[i];
            }
            return limit(new Track(st, pcm.sampleRate()));
        }
        if (name.endsWith(".mp3")) {
            return decodeMp3(Files.readAllBytes(file));
        }
        // OGG Vorbis: декодер STB из LWJGL, он входит в Minecraft (внутренние классы игры для этого не используем).
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer mem = MemoryUtil.memAlloc(bytes.length);
        try {
            mem.put(bytes).flip();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer channels = stack.mallocInt(1);
                IntBuffer rate = stack.mallocInt(1);
                ShortBuffer pcm = STBVorbis.stb_vorbis_decode_memory(mem, channels, rate);
                if (pcm == null) {
                    throw new IllegalStateException("not a valid OGG Vorbis file");
                }
                try {
                    int ch = Math.max(1, channels.get(0));
                    int frames = pcm.remaining() / ch;
                    short[] st = new short[frames * 2];
                    for (int i = 0; i < frames; i++) {
                        short l = pcm.get(i * ch);
                        short r = ch > 1 ? pcm.get(i * ch + 1) : l;
                        st[2 * i] = l;
                        st[2 * i + 1] = r;
                    }
                    return limit(new Track(st, rate.get(0)));
                } finally {
                    MemoryUtil.memFree(pcm);
                }
            }
        } finally {
            MemoryUtil.memFree(mem);
        }
    }

    /** MP3 через библиотеку JLayer (LGPL), которая вкладывается в мод. */
    private static Track decodeMp3(byte[] bytes) throws Exception {
        try {
            javazoom.jl.decoder.Bitstream bs = new javazoom.jl.decoder.Bitstream(new java.io.ByteArrayInputStream(bytes));
            javazoom.jl.decoder.Decoder dec = new javazoom.jl.decoder.Decoder();
            short[] out = new short[1 << 20];
            int n = 0;
            int rate = 0;
            try {
                while (true) {
                    javazoom.jl.decoder.Header h = bs.readFrame();
                    if (h == null) {
                        break;
                    }
                    javazoom.jl.decoder.SampleBuffer sb = (javazoom.jl.decoder.SampleBuffer) dec.decodeFrame(h, bs);
                    short[] buf = sb.getBuffer();
                    int ch = Math.max(1, sb.getChannelCount());
                    int frames = sb.getBufferLength() / ch;
                    rate = sb.getSampleFrequency();
                    if (n + frames * 2 > out.length) {
                        out = java.util.Arrays.copyOf(out, Math.max(out.length * 2, n + frames * 2));
                    }
                    for (int i = 0; i < frames; i++) {
                        short l = buf[i * ch];
                        out[n++] = l;
                        out[n++] = ch > 1 ? buf[i * ch + 1] : l;
                    }
                    bs.closeFrame();
                    if (n >= MAX_SECONDS * rate * 2) {
                        break;
                    }
                }
            } finally {
                bs.close();
            }
            if (n == 0) {
                throw new IllegalStateException("no audio found in the MP3 file");
            }
            return new Track(java.util.Arrays.copyOf(out, n), rate);
        } catch (LinkageError e) {
            throw new IllegalStateException("MP3 support library is missing from this build", e);
        }
    }

    private static Track limit(Track t) {
        int max = MAX_SECONDS * t.rate * 2;
        return t.stereo.length > max ? new Track(java.util.Arrays.copyOf(t.stereo, max), t.rate) : t;
    }
}
