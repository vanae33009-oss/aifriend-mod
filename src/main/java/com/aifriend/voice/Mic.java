package com.aifriend.voice;

import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/** Доступ к микрофону через javax.sound: 16 кГц, 16 бит, моно (этого хватает для распознавания речи). */
public final class Mic {
    public static final float RATE = 16_000f;
    public static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 1, true, false);

    private Mic() {
    }

    /** Названия устройств, которые умеют записывать звук в нужном формате. */
    public static List<String> devices() {
        List<String> names = new ArrayList<>();
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        try {
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                try {
                    if (AudioSystem.getMixer(mi).isLineSupported(info) && !names.contains(mi.getName())) {
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

    /** Открывает и запускает запись. Пустое имя = устройство по умолчанию. */
    public static TargetDataLine open(String deviceName) throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
        TargetDataLine line = null;
        if (deviceName != null && !deviceName.isBlank()) {
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (mi.getName().equals(deviceName)) {
                    Mixer mixer = AudioSystem.getMixer(mi);
                    if (mixer.isLineSupported(info)) {
                        line = (TargetDataLine) mixer.getLine(info);
                        break;
                    }
                }
            }
        }
        if (line == null) {
            line = (TargetDataLine) AudioSystem.getLine(info);
        }
        line.open(FORMAT, (int) (RATE * 2 * 0.5)); // буфер ~0.5 с
        line.start();
        return line;
    }
}
