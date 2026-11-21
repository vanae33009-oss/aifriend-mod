package com.aifriend.voice;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Objects;
import java.util.function.Consumer;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import com.aifriend.AiFriendMod;
import com.aifriend.entity.FriendEntity;
import com.aifriend.net.VoiceTextPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Клиентская часть голоса:
 * микрофон -> распознавание речи (STT) -> текст уходит на сервер, как будто игрок сказал это боту;
 * реплика бота приходит с сервера -> озвучка (TTS) -> воспроизведение с учётом расстояния и стороны.
 * Все ключи и настройки берутся из VoiceSettings (только на этом компьютере).
 */
public final class VoiceManager {
    private static final int CHUNK_BYTES = 640;            // 20 мс при 16 кГц, 16 бит, моно
    private static final int MIN_SPEECH_BYTES = 12_800;    // 0,4 с
    private static final int MAX_SPEECH_BYTES = 16_000 * 2 * 20; // 20 с
    private static final int PRE_ROLL_CHUNKS = 15;         // 0,3 с до начала голоса
    private static final long VAD_SILENCE_MS = 900;
    /** Верхняя граница шкалы индикатора уровня; тот же диапазон у ползунка чувствительности. */
    public static final double METER_MAX = 0.15;

    private static final Speaker SPEAKER = new Speaker();

    private static Thread captureThread;
    private static volatile boolean captureRun;
    private static volatile int captureGen;
    private static volatile String captureDevice;

    private static volatile boolean pttDown;
    private static volatile boolean worldActive;
    private static volatile boolean friendNearby;
    private static volatile boolean recording;
    private static volatile boolean recognizing;
    private static volatile float level;
    private static volatile long meterUntil;
    private static volatile String message;
    private static long lastWarnMs;
    private static volatile long micFailedAt;
    private static int ticks;

    // тест распознавания из экрана настроек
    private static volatile long testUntil;
    private static volatile Consumer<Component> testCallback;

    private VoiceManager() {
    }

    // ------------------------------------------------------------------ вызовы из игры (главный поток)

    /** Каждый тик клиента. */
    public static void tick(Minecraft mc, boolean talkKeyDown) {
        pttDown = talkKeyDown;
        VoiceSettings.Data d = VoiceSettings.get();
        boolean inWorld = mc.level != null && mc.player != null;
        worldActive = inWorld;

        if (inWorld && ticks++ % 10 == 0) {
            friendNearby = !mc.level.getEntitiesOfClass(FriendEntity.class, mc.player.getBoundingBox().inflate(40.0)).isEmpty();
        }
        if (!inWorld) {
            friendNearby = false;
            SPEAKER.clear();
        }

        boolean meter = System.currentTimeMillis() < meterUntil;
        boolean want = (inWorld && d.micMode() != VoiceSettings.MicMode.OFF) || meter;
        String dev = d.micDevice;
        if (want) {
            if (!captureRun || !Objects.equals(dev, captureDevice)) {
                if (System.currentTimeMillis() - micFailedAt > 10_000 || !Objects.equals(dev, captureDevice)) {
                    startCapture(dev);
                }
            }
        } else if (captureRun) {
            stopCapture();
        }

        String m = message;
        if (m != null) {
            message = null;
            if (mc.gui != null) {
                mc.gui.setOverlayMessage(Component.literal(m), false);
            }
        } else if (inWorld && mc.gui != null) {
            if (recording) {
                mc.gui.setOverlayMessage(Component.literal("Mic: recording..."), false);
            } else if (recognizing) {
                mc.gui.setOverlayMessage(Component.literal("Mic: recognizing..."), false);
            }
        }

        if (inWorld && SPEAKER.currentEntity != -1) {
            Entity e = mc.level.getEntity(SPEAKER.currentEntity);
            if (e != null) {
                setSpatial(mc.player, e, SPEAKER.currentRadius);
            }
        }
    }

    /** Экран настроек вызывает это каждый тик, чтобы микрофон был включён для индикатора уровня. */
    public static void requestMeter() {
        meterUntil = System.currentTimeMillis() + 600;
    }

    public static float level() {
        return level;
    }

    /** Бот сказал реплику (пришло с сервера). */
    public static void onFriendSpeech(int entityId, String text, int radius) {
        Minecraft mc = Minecraft.getInstance();
        VoiceSettings.Data d = VoiceSettings.get();
        if (!d.ttsEnabled || mc.level == null || mc.player == null) {
            return;
        }
        Entity e = mc.level.getEntity(entityId);
        if (e == null || e.distanceTo(mc.player) > radius) {
            return; // слишком далеко - не слышим
        }
        VoiceSettings.TtsResolved r = VoiceSettings.resolveTts();
        if (!r.usable()) {
            warn("Voice: set up speech output (key K > Voice)");
            return;
        }
        TtsClient.synth(r, text).whenComplete((pcm, err) -> mc.execute(() -> {
            if (err != null) {
                AiFriendMod.LOGGER.warn("TTS failed: {}", rootMessage(err));
                warn("Voice output failed: " + VoiceHttp.shorten(rootMessage(err), 90));
                return;
            }
            Minecraft m = Minecraft.getInstance();
            Entity ent = m.level == null ? null : m.level.getEntity(entityId);
            if (ent != null && m.player != null) {
                setSpatial(m.player, ent, radius);
                SPEAKER.enqueue(new Speaker.Job(entityId, radius, pcm));
            }
        }));
    }

    // ------------------------------------------------------------------ тесты из настроек

    public static void testSpeech(Consumer<Component> out) {
        VoiceSettings.TtsResolved r = VoiceSettings.resolveTts();
        if (!r.usable()) {
            out.accept(Component.literal("Fill in the speech output settings first"));
            return;
        }
        out.accept(Component.literal("Generating voice with " + r.label() + "..."));
        TtsClient.synth(r, "Hello! Can you hear me? Привет, это проверка голоса.").whenComplete((pcm, err) -> {
            if (err != null) {
                out.accept(Component.literal(VoiceHttp.shorten(rootMessage(err), 90)));
                return;
            }
            float v = (float) VoiceSettings.get().outputVolume;
            SPEAKER.gainL = v;
            SPEAKER.gainR = v;
            SPEAKER.enqueue(new Speaker.Job(-1, 0, pcm));
            out.accept(Component.literal("OK: playing (" + String.format("%.1f", pcm.seconds()) + " s)"));
        });
    }

    public static void testRecognition(Consumer<Component> out) {
        VoiceSettings.SttResolved r = VoiceSettings.resolveStt();
        if (!r.usable()) {
            out.accept(Component.literal("Fill in the speech recognition settings first"));
            return;
        }
        testCallback = out;
        testUntil = System.currentTimeMillis() + 3000;
        out.accept(Component.literal("Speak now (3 seconds)..."));
    }

    // ------------------------------------------------------------------ захват микрофона

    private static synchronized void startCapture(String dev) {
        stopCapture();
        captureRun = true;
        captureDevice = dev;
        int gen = ++captureGen;
        captureThread = new Thread(() -> captureLoop(dev, gen), "AIFriend-Mic");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    private static synchronized void stopCapture() {
        captureRun = false;
        captureGen++;
        recording = false;
        level = 0;
    }

    private static void captureLoop(String dev, int gen) {
        Mic.Capture line;
        try {
            line = Mic.open(dev);
        } catch (Exception e) {
            AiFriendMod.LOGGER.warn("AI Friend: microphone unavailable: {}", e.toString());
            message = "Microphone unavailable: " + VoiceHttp.shorten(String.valueOf(e.getMessage()), 70);
            micFailedAt = System.currentTimeMillis(); // повторная попытка не раньше чем через 10 секунд
            if (gen == captureGen) {
                captureRun = false;
            }
            return;
        }

        AiFriendMod.LOGGER.info("AI Friend: microphone opened ({}), format {} -> 16 kHz mono", dev == null || dev.isBlank() ? "default device" : dev, line.description());
        byte[] buf = new byte[CHUNK_BYTES];
        ByteArrayOutputStream speech = new ByteArrayOutputStream();
        java.util.ArrayDeque<byte[]> preRoll = new java.util.ArrayDeque<>();
        ByteArrayOutputStream testBuf = new ByteArrayOutputStream();
        boolean pttWas = false;
        boolean inSpeech = false;
        long lastVoice = 0;
        try {
            while (captureRun && gen == captureGen) {
                int n = line.read(buf, 0, buf.length);
                if (n <= 0) {
                    throw new IllegalStateException("microphone stopped delivering audio");
                }
                double rms = rms(buf, n);
                level = (float) Math.max(rms, level * 0.85);
                long now = System.currentTimeMillis();
                VoiceSettings.Data d = VoiceSettings.get();

                // --- тест распознавания (3 секунды записи)
                if (testUntil > now) {
                    testBuf.write(buf, 0, n);
                } else if (testBuf.size() > 0) {
                    byte[] pcm = testBuf.toByteArray();
                    testBuf.reset();
                    Consumer<Component> cb = testCallback;
                    if (cb != null) {
                        runTestRecognition(pcm, cb);
                    }
                }

                if (!worldActive) {
                    speech.reset();
                    recording = false;
                    pttWas = false;
                    inSpeech = false;
                    continue;
                }

                switch (d.micMode()) {
                    case PUSH_TO_TALK -> {
                        boolean down = pttDown && friendNearby;
                        if (pttDown && !friendNearby && !pttWas) {
                            AiFriendMod.LOGGER.info("AI Friend: talk key pressed but no friend within 40 blocks");
                            warn("No friend nearby - nobody to talk to");
                        }
                        if (down) {
                            speech.write(buf, 0, n);
                            recording = true;
                            if (speech.size() >= MAX_SPEECH_BYTES) {
                                finishSpeech(speech);
                                recording = false;
                            }
                        } else if (pttWas || recording) {
                            recording = false;
                            finishSpeech(speech);
                        }
                        pttWas = down;
                    }
                    case VOICE_ACTIVATION -> {
                        if (SPEAKER.isBusy() || !friendNearby) { // не слушаем собственную озвучку бота
                            speech.reset();
                            preRoll.clear();
                            inSpeech = false;
                            recording = false;
                            continue;
                        }
                        boolean voice = rms > d.vadThreshold;
                        if (voice) {
                            if (!inSpeech) {
                                inSpeech = true;
                                for (byte[] c : preRoll) {
                                    speech.write(c, 0, c.length);
                                }
                                preRoll.clear();
                            }
                            lastVoice = now;
                        }
                        if (inSpeech) {
                            speech.write(buf, 0, n);
                            recording = true;
                            if (now - lastVoice > VAD_SILENCE_MS || speech.size() >= MAX_SPEECH_BYTES) {
                                inSpeech = false;
                                recording = false;
                                finishSpeech(speech);
                            }
                        } else {
                            preRoll.addLast(buf.clone());
                            while (preRoll.size() > PRE_ROLL_CHUNKS) {
                                preRoll.pollFirst();
                            }
                        }
                    }
                    default -> {
                        speech.reset();
                        recording = false;
                    }
                }
            }
        } catch (Exception e) {
            AiFriendMod.LOGGER.warn("AI Friend: microphone loop stopped: {}", e.toString());
            micFailedAt = System.currentTimeMillis();
        } finally {
            recording = false;
            try {
                line.stop();
                line.close();
            } catch (Exception ignored) {
                // уже закрыта
            }
            if (gen == captureGen) {
                captureRun = false;
            }
        }
    }

    private static int peak(byte[] pcm) {
        int max = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int v = Math.abs((short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8)));
            if (v > max) {
                max = v;
            }
        }
        return max;
    }

    private static void finishSpeech(ByteArrayOutputStream speech) {
        byte[] pcm = speech.toByteArray();
        speech.reset();
        if (pcm.length < MIN_SPEECH_BYTES) {
            AiFriendMod.LOGGER.info("AI Friend: recording too short ({} ms), ignored", pcm.length / 32);
            warn("Mic: too short, hold the key while speaking");
            return;
        }
        int peak = peak(pcm);
        AiFriendMod.LOGGER.info("AI Friend: recorded {} s, peak level {}/32767", String.format("%.1f", pcm.length / 32000.0), peak);
        if (peak < 30) {
            AiFriendMod.LOGGER.warn("AI Friend: the microphone delivers silence. On macOS allow microphone access for your launcher/Java "
                    + "(System Settings > Privacy & Security > Microphone), on Windows check Settings > Privacy > Microphone and the input device.");
            warn("Mic hears only silence: check microphone permission (macOS: Privacy > Microphone)");
            return;
        }
        VoiceSettings.SttResolved r = VoiceSettings.resolveStt();
        if (!r.usable()) {
            AiFriendMod.LOGGER.warn("AI Friend: speech recognition is not configured (K > Voice > Recognition)");
            warn("Voice: set up speech recognition (key K > Voice)");
            return;
        }
        recognizing = true;
        SttClient.transcribe(r, toWav(pcm)).whenComplete((text, err) -> {
            recognizing = false;
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (err != null) {
                    AiFriendMod.LOGGER.warn("STT failed: {}", rootMessage(err));
                    warn("Recognition failed: " + VoiceHttp.shorten(rootMessage(err), 90));
                    return;
                }
                String t = text == null ? "" : text.trim();
                AiFriendMod.LOGGER.info("AI Friend: recognized '{}'", t);
                if (t.length() < 2) {
                    warn("Mic: nothing recognized, try speaking louder or choose the language in Voice settings");
                    return;
                }
                if (mc.player == null || !friendNearby) {
                    return;
                }
                PacketDistributor.sendToServer(new VoiceTextPayload(t));
            });
        });
    }

    private static void runTestRecognition(byte[] pcm, Consumer<Component> cb) {
        testCallback = null;
        VoiceSettings.SttResolved r = VoiceSettings.resolveStt();
        cb.accept(Component.literal("Recognizing..."));
        SttClient.transcribe(r, toWav(pcm)).whenComplete((text, err) -> {
            if (err != null) {
                cb.accept(Component.literal(VoiceHttp.shorten(rootMessage(err), 90)));
            } else if (text == null || text.isBlank()) {
                cb.accept(Component.literal("Nothing recognized - check the microphone level"));
            } else {
                cb.accept(Component.literal("Heard: " + VoiceHttp.shorten(text, 80)));
            }
        });
    }

    // ------------------------------------------------------------------ утилиты

    /** Громкость по расстоянию (до 6 блоков - полная, дальше спадает до нуля на радиусе) и стерео-баланс по направлению. */
    private static void setSpatial(Player player, Entity friend, int radius) {
        double dx = friend.getX() - player.getX();
        double dy = friend.getY() - player.getY();
        double dz = friend.getZ() - player.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double gain = dist <= 6.0 ? 1.0 : Math.max(0.0, 1.0 - (dist - 6.0) / Math.max(1.0, radius - 6.0));

        double yaw = Math.toRadians(player.getYRot());
        double rx = -Math.cos(yaw);
        double rz = -Math.sin(yaw);
        double flat = Math.sqrt(dx * dx + dz * dz);
        double pan = flat < 0.5 ? 0.0 : Math.max(-1.0, Math.min(1.0, (dx * rx + dz * rz) / flat)); // -1 слева .. +1 справа
        double angle = (pan + 1.0) * Math.PI / 4.0;
        double vol = VoiceSettings.get().outputVolume * gain * 1.4142;
        SPEAKER.gainL = (float) Math.min(1.0, Math.cos(angle) * vol);
        SPEAKER.gainR = (float) Math.min(1.0, Math.sin(angle) * vol);
    }

    private static double rms(byte[] b, int n) {
        long sum = 0;
        int count = n / 2;
        for (int i = 0; i < count; i++) {
            int s = (short) ((b[2 * i] & 0xFF) | (b[2 * i + 1] << 8));
            sum += (long) s * s;
        }
        return count == 0 ? 0 : Math.sqrt(sum / (double) count) / 32768.0;
    }

    private static byte[] toWav(byte[] pcm) {
        try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(pcm), Mic.FORMAT, pcm.length / 2)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Сообщение над панелью предметов, не чаще раза в 5 секунд (по одному на тип ошибки). */
    private static void warn(String text) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs > 5000) {
            lastWarnMs = now;
            message = text;
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while ((c instanceof java.util.concurrent.CompletionException || c instanceof java.util.concurrent.ExecutionException) && c.getCause() != null) {
            c = c.getCause();
        }
        return String.valueOf(c.getMessage());
    }
}
