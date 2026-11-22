package com.aifriend.client;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.Set;

import javax.annotation.Nullable;

import com.aifriend.MusicCatalog;
import com.aifriend.ai.AiSettings;
import com.aifriend.voice.MusicStreamer;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.network.chat.Component;

/**
 * Музыка по просьбе бота. Мод ничего не скачивает и не "вытаскивает" из сервисов: он только открывает в твоём браузере
 * или приложении ссылку/поиск на Spotify, Яндекс Музыке или YouTube, предварительно спросив подтверждение.
 */
public final class MusicLinks {
    private static final Set<String> HOSTS = Set.of(
            "open.spotify.com", "spotify.link",
            "music.yandex.ru", "music.yandex.com", "music.yandex.by", "music.yandex.kz", "music.yandex.uz",
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be");

    private static long lastOpenMs;

    private MusicLinks() {
    }

    public static void handle(String query) {
        handle(query, null);
    }

    /** back - экран, на который вернуться после окна подтверждения (например плеер). */
    public static void handle(String query, @Nullable net.minecraft.client.gui.screens.Screen back) {
        handle(query, back, false);
    }

    /**
     * user - игрок сам ввёл ссылку в плеере: открываем сразу, без окна подтверждения и без паузы между запросами,
     * и показываем в плеере, что получилось. Если просит бот, сначала спрашиваем подтверждение.
     */
    public static void handle(String query, @Nullable net.minecraft.client.gui.screens.Screen back, boolean user) {
        Minecraft mc = Minecraft.getInstance();
        if (!AiSettings.get().musicEnabled || query == null || query.isBlank()) {
            return;
        }
        String low = query.trim().toLowerCase(Locale.ROOT);
        if (low.equals("stop") || low.equals("стоп") || low.equals("off")) {
            MusicStreamer.stop();
            MusicPlayer.hide();
            return;
        }
        if (low.equals("next") || low.equals("skip") || low.equals("дальше")) {
            MusicStreamer.next();
            return;
        }
        if (low.equals("prev") || low.equals("previous") || low.equals("назад")) {
            MusicStreamer.previous();
            return;
        }
        if (low.equals("loop") || low.equals("повтор")) {
            MusicStreamer.toggleLoop();
            return;
        }
        if (low.equals("pause") || low.equals("пауза")) {
            MusicPlayer.toggleSpin();
            return;
        }
        if (low.equals("list") || low.equals("список")) {
            List<Path> all = MusicCatalog.tracks();
            if (mc.player != null) {
                mc.player.displayClientMessage(all.isEmpty()
                        ? Component.translatable("aifriend.music.no_files", MusicCatalog.dir().toString())
                        : Component.literal(all.stream().map(MusicCatalog::title).collect(Collectors.joining(", "))), false);
            }
            return;
        }
        if (!low.startsWith("http")) {
            String name = low.startsWith("file:") ? query.trim().substring(5).trim() : query.trim();
            String lname = name.toLowerCase(Locale.ROOT);
            if (lname.equals("all") || lname.equals("playlist") || lname.equals("все") || lname.equals("плейлист")) {
                List<Path> all = MusicCatalog.tracks();
                if (!all.isEmpty()) {
                    MusicStreamer.play(all, true, null, false);
                    return;
                }
            } else {
                Path track = MusicCatalog.find(titleOf(name));
                if (track != null) {
                    MusicStreamer.play(MusicCatalog.tracks(), false, track, true);
                    return;
                }
            }
            if (low.startsWith("file:") && mc.player != null) {
                mc.player.displayClientMessage(Component.translatable("aifriend.music.no_file", name), false);
                return;
            }
        }
        query = query.trim().replaceAll("[\\s.,;)]+$", "");
        long now = System.currentTimeMillis();
        if (!user && (now - lastOpenMs < 8000 || mc.screen instanceof ConfirmLinkScreen)) {
            return; // не заваливаем игрока окнами
        }
        String url = resolve(query.trim());
        if (url == null) {
            MusicPlayer.setStatus(Component.translatable("aifriend.music.blocked").getString(), true);
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.translatable("aifriend.music.blocked").withStyle(ChatFormatting.GRAY), false);
            }
            return;
        }
        lastOpenMs = now;
        final boolean isLink = query.toLowerCase(Locale.ROOT).startsWith("http");
        final String shownTitle = isLink ? "" : titleOf(query);
        final String linkForInfo = isLink ? url : null;
        if (user) {
            Util.getPlatform().openUri(url);
            MusicPlayer.show(shownTitle.isEmpty() ? url : shownTitle, linkForInfo);
            return;
        }
        mc.execute(() -> mc.setScreen(new ConfirmLinkScreen(yes -> {
            if (yes) {
                Util.getPlatform().openUri(url);
                MusicPlayer.show(shownTitle.isEmpty() ? url : shownTitle, linkForInfo);
            }
            mc.setScreen(back);
        }, url, true)));
    }

    private static String titleOf(String q) {
        int colon = q.indexOf(':');
        return colon > 0 && colon < 10 ? q.substring(colon + 1).trim() : q;
    }

    /** Ссылка из списка разрешённых сервисов либо поиск на выбранном сервисе. null - не открываем. */
    @Nullable
    static String resolve(String q) {
        String lower = q.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            try {
                URI u = URI.create(q);
                String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
                return "https".equals(u.getScheme()) && HOSTS.contains(host) ? q : null;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        String service = AiSettings.get().musicService;
        String text = q;
        int colon = q.indexOf(':');
        if (colon > 0 && colon < 10) {
            String prefix = q.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (prefix.equals("spotify") || prefix.equals("yandex") || prefix.equals("youtube")) {
                service = prefix;
                text = q.substring(colon + 1).trim();
            }
        }
        if (text.isEmpty()) {
            return null;
        }
        String enc = URLEncoder.encode(text, StandardCharsets.UTF_8);
        return switch (service) {
            case "yandex" -> "https://music.yandex.ru/search?text=" + enc;
            case "youtube" -> "https://www.youtube.com/results?search_query=" + enc;
            default -> "https://open.spotify.com/search/" + enc;
        };
    }
}
