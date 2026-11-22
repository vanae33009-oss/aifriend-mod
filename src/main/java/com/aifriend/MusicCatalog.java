package com.aifriend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import net.neoforged.fml.loading.FMLPaths;

/** Твоя музыка для игры: файлы .ogg и .wav в config/aifriend/music. */
public final class MusicCatalog {
    private MusicCatalog() {
    }

    public static Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve("aifriend").resolve("music");
    }

    public static void ensureDir() {
        try {
            Files.createDirectories(dir());
        } catch (IOException ignored) {
            // нет доступа: список просто будет пустым
        }
    }

    public static boolean isTrack(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".ogg") || n.endsWith(".wav");
    }

    /** Все треки, по имени. */
    public static List<Path> tracks() {
        List<Path> out = new ArrayList<>();
        Path d = dir();
        if (!Files.isDirectory(d)) {
            return out;
        }
        try (Stream<Path> s = Files.list(d)) {
            s.filter(Files::isRegularFile).filter(MusicCatalog::isTrack).sorted().forEach(out::add);
        } catch (IOException ignored) {
            // пусто
        }
        return out;
    }

    public static String title(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** Трек, в имени которого есть текст (без учёта регистра), либо null. */
    public static Path find(String text) {
        String q = text.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return null;
        }
        Path partial = null;
        for (Path p : tracks()) {
            String t = title(p).toLowerCase(Locale.ROOT);
            if (t.equals(q)) {
                return p;
            }
            if (partial == null && t.contains(q)) {
                partial = p;
            }
        }
        return partial;
    }

    /** Картинка рядом с треком с тем же именем (png/jpg) для центра пластинки, либо null. */
    public static Path cover(Path track) {
        String base = title(track);
        for (String ext : new String[] { ".png", ".jpg", ".jpeg" }) {
            Path c = track.resolveSibling(base + ext);
            if (Files.isRegularFile(c)) {
                return c;
            }
        }
        return null;
    }
}
