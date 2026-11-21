package com.aifriend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import net.neoforged.fml.loading.FMLPaths;

/**
 * Список скинов бота. Встроенные (ванильные текстуры игрока) и свои: любой png 64x64 в config/aifriend/skins/.
 * Скин с именем, оканчивающимся на «slim» (например «anna_slim.png»), рисуется с тонкими руками.
 * Класс общий (серверный и клиентский): тут нет ничего про рендер.
 */
public final class SkinCatalog {
    /** id -> путь внутри textures/entity/player/ (без .png). */
    private static final Map<String, String> BUILTIN = new LinkedHashMap<>();
    private static final String[] MALE = { "steve", "kai" };
    private static final String[] FEMALE = { "alex", "sunny", "makena" };

    static {
        BUILTIN.put("steve", "wide/steve");
        BUILTIN.put("kai", "wide/kai");
        BUILTIN.put("ari", "wide/ari");
        BUILTIN.put("zuri", "wide/zuri");
        BUILTIN.put("alex", "slim/alex");
        BUILTIN.put("sunny", "slim/sunny");
        BUILTIN.put("makena", "slim/makena");
        BUILTIN.put("efe", "slim/efe");
    }

    private SkinCatalog() {
    }

    public static Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve("aifriend").resolve("skins");
    }

    /** Создаёт папку для своих скинов (чтобы игрок знал, куда класть файлы). */
    public static void ensureDir() {
        try {
            Files.createDirectories(dir());
        } catch (IOException ignored) {
            // не критично
        }
    }

    public static boolean isBuiltin(String id) {
        return BUILTIN.containsKey(normalize(id));
    }

    /** Путь к встроенной текстуре внутри textures/entity/player/, либо null. */
    public static String builtinPath(String id) {
        return BUILTIN.get(normalize(id));
    }

    public static boolean isSlim(String id) {
        String n = normalize(id);
        String b = BUILTIN.get(n);
        if (b != null) {
            return b.startsWith("slim/");
        }
        return n.endsWith("slim");
    }

    /** Имена своих скинов (файлы png без расширения), по алфавиту. */
    public static List<String> customNames() {
        List<String> out = new ArrayList<>();
        Path d = dir();
        if (Files.isDirectory(d)) {
            try (Stream<Path> s = Files.list(d)) {
                s.map(p -> p.getFileName().toString())
                        .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".png"))
                        .map(n -> n.substring(0, n.length() - 4))
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .forEach(out::add);
            } catch (IOException ignored) {
                // пустой список
            }
        }
        return out;
    }

    public static Path customFile(String id) {
        return dir().resolve(id + ".png");
    }

    public static boolean exists(String id) {
        String n = normalize(id);
        if (BUILTIN.containsKey(n)) {
            return true;
        }
        for (String c : customNames()) {
            if (c.equalsIgnoreCase(id.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Точное имя (с учётом регистра) для существующего скина, иначе null. */
    public static String resolve(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String n = normalize(id);
        if (BUILTIN.containsKey(n)) {
            return n;
        }
        for (String c : customNames()) {
            if (c.equalsIgnoreCase(id.trim())) {
                return c;
            }
        }
        return null;
    }

    /** Все доступные id: сначала встроенные, потом свои. */
    public static List<String> all() {
        List<String> out = new ArrayList<>(BUILTIN.keySet());
        out.addAll(customNames());
        return out;
    }

    /** Скин по умолчанию для нового бота: мужской или женский из встроенных. */
    public static String defaultFor(boolean female) {
        String[] list = female ? FEMALE : MALE;
        return list[ThreadLocalRandom.current().nextInt(list.length)];
    }

    private static String normalize(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }
}
