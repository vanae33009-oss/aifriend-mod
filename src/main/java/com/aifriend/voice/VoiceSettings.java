package com.aifriend.voice;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.aifriend.AiFriendMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.neoforged.fml.loading.FMLPaths;

/**
 * Настройки голоса (клиентские!): микрофон, распознавание речи (STT) и озвучка (TTS).
 * Хранятся в config/aifriend-voice.json. Ключи остаются на компьютере игрока и никуда, кроме выбранного им сервиса, не уходят.
 * Устроено так же, как AiSettings: готовые провайдеры + «Custom API» со своим форматом, URL, ключом и моделью.
 */
public final class VoiceSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public enum MicMode {
        OFF("Off"),
        PUSH_TO_TALK("Push-to-talk"),
        VOICE_ACTIVATION("Voice activation");

        public final String label;

        MicMode(String label) {
            this.label = label;
        }
    }

    /** Формат HTTP-API сервиса. */
    public enum ApiStyle {
        OPENAI("OpenAI-compatible"),
        ELEVENLABS("ElevenLabs-compatible"),
        FISH("Fish Audio-compatible");

        public final String label;

        ApiStyle(String label) {
            this.label = label;
        }
    }

    public enum TtsProvider {
        ELEVENLABS("ElevenLabs", ApiStyle.ELEVENLABS, "https://api.elevenlabs.io", "eleven_multilingual_v2", "21m00Tcm4TlvDq8ikWAM"),
        FISH("Fish Audio", ApiStyle.FISH, "https://api.fish.audio", "s2.1-pro", ""),
        CUSTOM("Custom API", ApiStyle.OPENAI, "", "", "");

        public final String label;
        public final ApiStyle style;
        public final String baseUrl;
        public final String defaultModel;
        public final String defaultVoice;

        TtsProvider(String label, ApiStyle style, String baseUrl, String defaultModel, String defaultVoice) {
            this.label = label;
            this.style = style;
            this.baseUrl = baseUrl;
            this.defaultModel = defaultModel;
            this.defaultVoice = defaultVoice;
        }
    }

    public enum SttProvider {
        OPENAI("OpenAI Whisper", ApiStyle.OPENAI, "https://api.openai.com/v1", "whisper-1"),
        GROQ("Groq Whisper", ApiStyle.OPENAI, "https://api.groq.com/openai/v1", "whisper-large-v3-turbo"),
        ELEVENLABS("ElevenLabs Scribe", ApiStyle.ELEVENLABS, "https://api.elevenlabs.io", "scribe_v2"),
        CUSTOM("Custom API", ApiStyle.OPENAI, "", "");

        public final String label;
        public final ApiStyle style;
        public final String baseUrl;
        public final String defaultModel;

        SttProvider(String label, ApiStyle style, String baseUrl, String defaultModel) {
            this.label = label;
            this.style = style;
            this.baseUrl = baseUrl;
            this.defaultModel = defaultModel;
        }
    }

    public static final class Profile {
        public String apiKey = "";
        public String voice = "";
        public String model = "";
    }

    public static final class Data {
        // --- микрофон
        public String micMode = MicMode.PUSH_TO_TALK.name();
        /** Пусто = устройство по умолчанию. */
        public String micDevice = "";
        /** Порог громкости (RMS, 0..1) для режима «по голосу». */
        public double vadThreshold = 0.02;

        // --- озвучка
        public boolean ttsEnabled = true;
        public double outputVolume = 1.0;
        public String ttsProvider = TtsProvider.ELEVENLABS.name();
        public Map<String, Profile> ttsProfiles = new LinkedHashMap<>();
        public String ttsCustomStyle = ApiStyle.OPENAI.name();
        public String ttsCustomUrl = "";
        public String ttsCustomKey = "";
        public String ttsCustomVoice = "";
        public String ttsCustomModel = "";

        // --- распознавание речи
        public String sttProvider = SttProvider.OPENAI.name();
        public Map<String, Profile> sttProfiles = new LinkedHashMap<>();
        /** Код языка (например ru, en). Пусто = определять автоматически. */
        public String sttLanguage = "";
        public String sttCustomStyle = ApiStyle.OPENAI.name();
        public String sttCustomUrl = "";
        public String sttCustomKey = "";
        public String sttCustomModel = "";

        public MicMode micMode() {
            return parse(MicMode.class, micMode, MicMode.PUSH_TO_TALK);
        }

        public TtsProvider ttsProvider() {
            return parse(TtsProvider.class, ttsProvider, TtsProvider.ELEVENLABS);
        }

        public SttProvider sttProvider() {
            return parse(SttProvider.class, sttProvider, SttProvider.OPENAI);
        }

        public ApiStyle ttsCustomStyle() {
            return parse(ApiStyle.class, ttsCustomStyle, ApiStyle.OPENAI);
        }

        public ApiStyle sttCustomStyle() {
            ApiStyle s = parse(ApiStyle.class, sttCustomStyle, ApiStyle.OPENAI);
            return s == ApiStyle.FISH ? ApiStyle.OPENAI : s; // для распознавания Fish Audio не поддерживается
        }

        public Profile ttsProfile(TtsProvider p) {
            return ttsProfiles.computeIfAbsent(p.name(), k -> new Profile());
        }

        public Profile sttProfile(SttProvider p) {
            return sttProfiles.computeIfAbsent(p.name(), k -> new Profile());
        }
    }

    /** Итоговые параметры озвучки по выбранному провайдеру. */
    public record TtsResolved(ApiStyle style, String baseUrl, String apiKey, String voice, String model, String label, boolean custom) {
        public boolean usable() {
            if (baseUrl.isBlank() || (!custom && apiKey.isBlank())) {
                return false;
            }
            return switch (style) {
                case ELEVENLABS -> !voice.isBlank();
                case OPENAI -> !voice.isBlank() && !model.isBlank();
                case FISH -> true;
            };
        }
    }

    /** Итоговые параметры распознавания речи. */
    public record SttResolved(ApiStyle style, String baseUrl, String apiKey, String model, String label, String language, boolean custom) {
        public boolean usable() {
            return !baseUrl.isBlank() && !model.isBlank() && (custom || !apiKey.isBlank());
        }
    }

    private static volatile Data data = new Data();

    private VoiceSettings() {
    }

    public static Data get() {
        return data;
    }

    private static Path path() {
        Path p = FMLPaths.CONFIGDIR.get().resolve("aifriend-voice.json");
        Path legacy = FMLPaths.CONFIGDIR.get().resolve("aibuddy-voice.json"); // настройки из прежнего названия мода (AI Buddy)
        if (!Files.exists(p) && Files.exists(legacy)) {
            try {
                Files.copy(legacy, p);
            } catch (IOException ignored) {
                // не критично: просто начнём с пустых настроек
            }
        }
        return p;
    }

    public static synchronized void load() {
        Path p = path();
        try {
            if (Files.exists(p)) {
                try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    Data loaded = GSON.fromJson(r, Data.class);
                    if (loaded != null) {
                        data = loaded;
                    }
                }
            }
        } catch (Exception e) {
            AiFriendMod.LOGGER.error("Could not read {} - using defaults", p, e);
        }
        normalize(data);
        save();
    }

    public static synchronized void save() {
        Path p = path();
        try {
            Files.createDirectories(p.getParent());
            try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
                GSON.toJson(data, w);
            }
        } catch (IOException e) {
            AiFriendMod.LOGGER.error("Could not write {}", p, e);
        }
    }

    private static void normalize(Data d) {
        if (d.ttsProfiles == null) d.ttsProfiles = new LinkedHashMap<>();
        if (d.sttProfiles == null) d.sttProfiles = new LinkedHashMap<>();
        if (d.micMode == null) d.micMode = MicMode.PUSH_TO_TALK.name();
        if (d.micDevice == null) d.micDevice = "";
        if (d.sttLanguage == null) d.sttLanguage = "";
        if (d.ttsCustomUrl == null) d.ttsCustomUrl = "";
        if (d.ttsCustomKey == null) d.ttsCustomKey = "";
        if (d.ttsCustomVoice == null) d.ttsCustomVoice = "";
        if (d.ttsCustomModel == null) d.ttsCustomModel = "";
        if (d.sttCustomUrl == null) d.sttCustomUrl = "";
        if (d.sttCustomKey == null) d.sttCustomKey = "";
        if (d.sttCustomModel == null) d.sttCustomModel = "";
        if (d.vadThreshold < 0.003 || d.vadThreshold > 0.3) d.vadThreshold = 0.02;
        if (d.outputVolume < 0.0 || d.outputVolume > 1.0) d.outputVolume = 1.0;
        for (TtsProvider p : TtsProvider.values()) {
            Profile pr = d.ttsProfile(p);
            if (pr.apiKey == null) pr.apiKey = "";
            if (pr.voice == null) pr.voice = "";
            if (pr.model == null) pr.model = "";
        }
        for (SttProvider p : SttProvider.values()) {
            Profile pr = d.sttProfile(p);
            if (pr.apiKey == null) pr.apiKey = "";
            if (pr.model == null) pr.model = "";
        }
    }

    public static TtsResolved resolveTts() {
        Data d = data;
        TtsProvider p = d.ttsProvider();
        if (p == TtsProvider.CUSTOM) {
            return new TtsResolved(d.ttsCustomStyle(), d.ttsCustomUrl.trim(), d.ttsCustomKey.trim(), d.ttsCustomVoice.trim(),
                    d.ttsCustomModel.trim(), "Custom", true);
        }
        Profile pr = d.ttsProfile(p);
        String voice = pr.voice.isBlank() ? p.defaultVoice : pr.voice.trim();
        String model = pr.model.isBlank() ? p.defaultModel : pr.model.trim();
        return new TtsResolved(p.style, p.baseUrl, pr.apiKey.trim(), voice, model, p.label, false);
    }

    public static SttResolved resolveStt() {
        Data d = data;
        SttProvider p = d.sttProvider();
        String lang = d.sttLanguage.trim();
        if (p == SttProvider.CUSTOM) {
            return new SttResolved(d.sttCustomStyle(), d.sttCustomUrl.trim(), d.sttCustomKey.trim(), d.sttCustomModel.trim(), "Custom", lang, true);
        }
        Profile pr = d.sttProfile(p);
        String model = pr.model.isBlank() ? p.defaultModel : pr.model.trim();
        return new SttResolved(p.style, p.baseUrl, pr.apiKey.trim(), model, p.label, lang, false);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        try {
            return Enum.valueOf(type, value);
        } catch (Exception e) {
            return fallback;
        }
    }
}
