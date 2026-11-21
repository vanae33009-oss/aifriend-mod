package com.aifriend.voice;

import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/**
 * Доступ к микрофону через javax.sound. Распознаванию речи нужен звук 16 кГц, 16 бит, моно (FORMAT), но многие устройства
 * (Bluetooth-наушники, встроенные микрофоны macOS) такой формат напрямую не отдают. Поэтому микрофон открывается в любом
 * поддерживаемом формате (16/44.1/48 кГц, моно или стерео), а {@link Capture} сам приводит звук к 16 кГц моно.
 */
public final class Mic {
    public static final float RATE = 16_000f;
    public static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 1, true, false);

    private static final float[] RATES = { 16_000f, 48_000f, 44_100f, 32_000f, 24_000f, 22_050f, 8_000f };
    private static final int[] CHANNELS = { 1, 2 };

    private Mic() {
    }

    private static List<AudioFormat> candidates() {
        List<AudioFormat> list = new ArrayList<>();
        for (float rate : RATES) {
            for (int ch : CHANNELS) {
                list.add(new AudioFormat(rate, 16, ch, true, false));
            }
        }
        return list;
    }

    /** Подходящий формат для этого устройства или null. */
    private static AudioFormat supported(Mixer mixer) {
        for (AudioFormat f : candidates()) {
            try {
                if (mixer.isLineSupported(new DataLine.Info(TargetDataLine.class, f))) {
                    return f;
                }
            } catch (Exception ignored) {
                // пробуем следующий формат
            }
        }
        return null;
    }

    /** Названия устройств, которые умеют записывать звук (в любом из форматов выше). */
    public static List<String> devices() {
        List<String> names = new ArrayList<>();
        try {
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                try {
                    if (supported(AudioSystem.getMixer(mi)) != null && !names.contains(mi.getName())) {
                        names.add(mi.getName());
                    }
                } catch (Exception ignored) {
                    // устройство недоступно - пропускаем
                }
            }
        } catch (Exception ignored) {
            // нет аудиосистемы
        }
        return names;
    }

    /** Устройства, на которые можно выводить звук (стерео, 16 бит). */
    public static List<String> outputDevices() {
        List<String> names = new ArrayList<>();
        try {
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                try {
                    Mixer m = AudioSystem.getMixer(mi);
                    for (float rate : new float[] { 48_000f, 44_100f, 24_000f }) {
                        if (m.isLineSupported(new DataLine.Info(javax.sound.sampled.SourceDataLine.class, new AudioFormat(rate, 16, 2, true, false)))) {
                            if (!names.contains(mi.getName())) {
                                names.add(mi.getName());
                            }
                            break;
                        }
                    }
                } catch (Exception ignored) {
                    // устройство недоступно
                }
            }
        } catch (Exception ignored) {
            // нет аудиосистемы
        }
        return names;
    }

    /** Открывает и запускает запись. Пустое имя = устройство по умолчанию, при неудаче перебираем остальные. */
    public static Capture open(String deviceName) throws LineUnavailableException {
        List<String> errors = new ArrayList<>();
        // 1. выбранное устройство
        if (deviceName != null && !deviceName.isBlank()) {
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (mi.getName().equals(deviceName)) {
                    Capture c = tryMixer(mi, errors);
                    if (c != null) {
                        return c;
                    }
                }
            }
        }
        // 2. устройство по умолчанию (системный выбор) в любом из форматов
        for (AudioFormat f : candidates()) {
            try {
                TargetDataLine line = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, f));
                return start(line, f);
            } catch (Exception e) {
                // следующий формат
            }
        }
        // 3. любое устройство, которое умеет записывать
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            Capture c = tryMixer(mi, errors);
            if (c != null) {
                return c;
            }
        }
        throw new LineUnavailableException("no usable microphone found (tried formats 8-48 kHz, mono/stereo, 16-bit)"
                + (errors.isEmpty() ? "" : ": " + errors.get(0)));
    }

    private static Capture tryMixer(Mixer.Info mi, List<String> errors) {
        try {
            Mixer mixer = AudioSystem.getMixer(mi);
            AudioFormat f = supported(mixer);
            if (f == null) {
                return null;
            }
            TargetDataLine line = (TargetDataLine) mixer.getLine(new DataLine.Info(TargetDataLine.class, f));
            return start(line, f);
        } catch (Exception e) {
            errors.add(mi.getName() + ": " + e.getMessage());
            return null;
        }
    }

    private static Capture start(TargetDataLine line, AudioFormat f) throws LineUnavailableException {
        line.open(f, (int) (f.getSampleRate() * f.getFrameSize() * 0.5)); // буфер ~0.5 с
        line.start();
        return new Capture(line, f);
    }

    /** Запись с приведением к 16 кГц, 16 бит, моно (little-endian). */
    public static final class Capture {
        private final TargetDataLine line;
        private final AudioFormat format;
        private final String description;

        Capture(TargetDataLine line, AudioFormat format) {
            this.line = line;
            this.format = format;
            this.description = (int) format.getSampleRate() + " Hz, " + format.getChannels() + " ch";
        }

        public String description() {
            return description;
        }

        /** Читает len байт готового звука (16 кГц моно), блокируется до получения. Возвращает len или -1 при ошибке. */
        public int read(byte[] out, int off, int len) {
            int outSamples = len / 2;
            double ratio = format.getSampleRate() / RATE;
            int ch = format.getChannels();
            int srcFrames = Math.max(1, (int) Math.round(outSamples * ratio));
            byte[] src = new byte[srcFrames * ch * 2];
            int got = 0;
            while (got < src.length) {
                int n = line.read(src, got, src.length - got);
                if (n <= 0) {
                    return -1;
                }
                got += n;
            }
            for (int i = 0; i < outSamples; i++) {
                int from = (int) Math.floor(i * ratio);
                int to = Math.min(srcFrames, Math.max(from + 1, (int) Math.floor((i + 1) * ratio)));
                long sum = 0;
                int count = 0;
                for (int f = from; f < to; f++) {
                    for (int c = 0; c < ch; c++) {
                        int idx = (f * ch + c) * 2;
                        sum += (short) ((src[idx] & 0xFF) | (src[idx + 1] << 8));
                        count++;
                    }
                }
                short v = (short) (count == 0 ? 0 : sum / count);
                out[off + i * 2] = (byte) (v & 0xFF);
                out[off + i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
            }
            return outSamples * 2;
        }

        public void stop() {
            line.stop();
        }

        public void close() {
            line.close();
        }
    }
}
