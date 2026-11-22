package com.aifriend.ai;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.aifriend.AiFriendMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.neoforged.fml.loading.FMLPaths;

/**
 * Настройки ИИ. Хранятся в config/aifriend.json (ключи в открытом виде!).
 * Класс общий (не клиентский): в одиночке/LAN клиент и сервер живут в одной JVM и видят одни и те же настройки.
 */
public final class AiSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String[] MALE_NAMES = { "Алекс", "Макс", "Дима", "Саня", "Лёва", "Ник", "Тоха", "Ваня", "Артём", "Егор", "Рома", "Костя" };
    private static final String[] FEMALE_NAMES = { "Кира", "Аня", "Маша", "Лера", "Соня", "Алиса", "Даша", "Ксюша", "Полина", "Варя" };

    /** Формат HTTP-API. */
    public enum ApiFormat {
        OPENAI("OpenAI-compatible"),
        ANTHROPIC("Anthropic-compatible"),
        GEMINI("Gemini-compatible");

        public final String label;

        ApiFormat(String label) {
            this.label = label;
        }
    }

    /** Готовые «официальные» провайдеры. Названия моделей можно менять в настройках. */
    public enum Provider {
        OPENAI("OpenAI", ApiFormat.OPENAI, "https://api.openai.com/v1", "gpt-4o-mini"),
        ANTHROPIC("Anthropic (Claude)", ApiFormat.ANTHROPIC, "https://api.anthropic.com", "claude-haiku-5-5"),
        GEMINI("Google Gemini", ApiFormat.GEMINI, "https://generativelanguage.googleapis.com", "gemini-2.5-flash"),
        DEEPSEEK("DeepSeek", ApiFormat.OPENAI, "https://api.deepseek.com", "deepseek-chat"),
        OPENROUTER("OpenRouter", ApiFormat.OPENAI, "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
        GROQ("Groq", ApiFormat.OPENAI, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile");

        public final String label;
        public final ApiFormat format;
        public final String baseUrl;
        public final String defaultModel;

        Provider(String label, ApiFormat format, String baseUrl, String defaultModel) {
            this.label = label;
            this.format = format;
            this.baseUrl = baseUrl;
            this.defaultModel = defaultModel;
        }
    }

    public static final class Profile {
        public String apiKey = "";
        public String model = "";
    }

    /** Содержимое aifriend.json. Публичные поля, чтобы Gson и экран настроек работали напрямую. */
    public static final class Data {
        /** false = вкладка «Официальные API», true = вкладка «Кастомный API». */
        public boolean useCustom = false;

        public String provider = Provider.OPENAI.name();
        public Map<String, Profile> official = new LinkedHashMap<>();

        public String customName = "Custom";
        public String customFormat = ApiFormat.OPENAI.name();
        public String customBaseUrl = "http://localhost:11434/v1";
        public String customApiKey = "";
        public String customModel = "";

        /** Пусто = случайное имя из списка при создании бота. */
        public String botName = "";
        public String personality = "";
        /** История и роль бота: короткий текст из настроек (дополняется файлом config/aifriend/story.txt). */
        public String backstory = "";
        /** Пол новых ботов: "male" (по умолчанию) или "female". От него зависят имя, скин и речь. */
        public String botGender = "male";
        /** Скин новых ботов: пусто = случайный подходящий по полу; иначе id встроенного скина или имя png из config/aifriend/skins. */
        public String botSkin = "";
        /** Бот сам здоровается, знакомится и заговаривает первым, если долго молчат. */
        public boolean proactiveChat = true;
        public double temperature = 0.8;
        public int maxTokens = 400;
        /** Радиус (в блоках), в котором бот слышит чат и в котором его слышно голосом. */
        public int hearingRadius = 32;
        /** Отладка: показывать в чате серым, что бот решил сделать и чем это кончилось. По умолчанию выключено (всё пишется в latest.log). */
        public boolean debugMessages = false;
        /** Бот сам появляется один раз при первом входе игрока в мир. */
        public boolean autoSpawn = true;
        /** Может ли бот предлагать музыку (открывает ссылку в браузере после подтверждения). */
        public boolean musicEnabled = true;
        /** spotify, yandex или youtube: где искать, если названа только песня. */
        public String musicService = "spotify";
        /** Во сколько раз бот копает и рубит быстрее обычного игрока (1 = как игрок). */
        public double digSpeed = 1.25;
        /** Бот сам принимает решения, когда его ни о чём не просят (развитие, добыча, крафт). */
        public boolean autonomy = true;
        /** Как часто (секунд) бот «думает сам», пока свободен. Каждый раз это один запрос к ИИ. */
        public int autonomySeconds = 60;

        public Provider provider() {
            return parse(Provider.class, provider, Provider.OPENAI);
        }

        public ApiFormat customFormat() {
            return parse(ApiFormat.class, customFormat, ApiFormat.OPENAI);
        }

        public Profile profile(Provider p) {
            return official.computeIfAbsent(p.name(), k -> new Profile());
        }
    }

    /** Итоговые параметры запроса, выбранные по активной вкладке. */
    public record Resolved(ApiFormat format, String baseUrl, String apiKey, String model, String label, boolean custom) {
        public boolean usable() {
            return !baseUrl.isBlank() && !model.isBlank() && (custom || !apiKey.isBlank());
        }
    }

    private static volatile Data data = new Data();

    private AiSettings() {
    }

    public static Data get() {
        return data;
    }

    private static Path path() {
        Path p = FMLPaths.CONFIGDIR.get().resolve("aifriend.json");
        Path legacy = FMLPaths.CONFIGDIR.get().resolve("aibuddy.json"); // настройки из прежнего названия мода (AI Buddy)
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
        if (d.official == null) d.official = new LinkedHashMap<>();
        if (d.provider == null) d.provider = Provider.OPENAI.name();
        if (d.customName == null) d.customName = "Custom";
        if (d.customFormat == null) d.customFormat = ApiFormat.OPENAI.name();
        if (d.customBaseUrl == null) d.customBaseUrl = "";
        if (d.customApiKey == null) d.customApiKey = "";
        if (d.customModel == null) d.customModel = "";
        if (d.musicService == null) d.musicService = "spotify";
        if (d.botName == null) d.botName = "";
        if (d.personality == null) d.personality = "";
        if (d.backstory == null) d.backstory = "";
        if (d.botGender == null || !(d.botGender.equals("male") || d.botGender.equals("female"))) d.botGender = "male";
        if (d.botSkin == null) d.botSkin = "";
        if (d.maxTokens < 50) d.maxTokens = 400;
        if (d.hearingRadius < 4) d.hearingRadius = 32;
        if (d.autonomySeconds < 15) d.autonomySeconds = 60;
        if (d.digSpeed < 0.5 || d.digSpeed > 6.0) d.digSpeed = 1.25;
        for (Provider p : Provider.values()) {
            Profile pr = d.profile(p);
            if (pr.apiKey == null) pr.apiKey = "";
            if (pr.model == null) pr.model = "";
        }
    }

    public static Resolved resolve() {
        Data d = data;
        if (d.useCustom) {
            return new Resolved(d.customFormat(), d.customBaseUrl.trim(), d.customApiKey.trim(), d.customModel.trim(),
                    d.customName.isBlank() ? "Custom" : d.customName.trim(), true);
        }
        Provider p = d.provider();
        Profile pr = d.profile(p);
        String model = pr.model.isBlank() ? p.defaultModel : pr.model.trim();
        return new Resolved(p.format, p.baseUrl, pr.apiKey.trim(), model, p.label, false);
    }

    public static boolean isConfigured() {
        return resolve().usable();
    }

    public static boolean defaultFemale() {
        return "female".equals(data.botGender);
    }

    public static String pickName() {
        return pickName(defaultFemale());
    }

    /** Имя из настроек, а если оно пустое, случайное имя подходящего пола. */
    public static String pickName(boolean female) {
        String n = data.botName.trim();
        if (!n.isEmpty()) {
            return n;
        }
        String[] list = female ? FEMALE_NAMES : MALE_NAMES;
        return list[ThreadLocalRandom.current().nextInt(list.length)];
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        try {
            return Enum.valueOf(type, value);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Файл с длинной историей/промтом бота: config/aifriend/story.txt. */
    public static java.nio.file.Path storyFile() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("aifriend").resolve("story.txt");
    }

    /** Вся история бота: текст из настроек плюс файл story.txt (до 6000 знаков в сумме). Пусто, если не задана. */
    public static String story() {
        StringBuilder sb = new StringBuilder();
        String inline = get().backstory;
        if (inline != null && !inline.isBlank()) {
            sb.append(inline.trim());
        }
        try {
            java.nio.file.Path f = storyFile();
            if (java.nio.file.Files.isRegularFile(f)) {
                String text = java.nio.file.Files.readString(f, java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!text.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(text);
                }
            }
        } catch (Exception ignored) {
            // нет файла или нечитаем: живём без него
        }
        return sb.length() > 6000 ? sb.substring(0, 6000) : sb.toString();
    }
}
