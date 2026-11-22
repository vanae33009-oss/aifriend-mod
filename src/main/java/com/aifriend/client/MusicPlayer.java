package com.aifriend.client;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;

import com.aifriend.AiFriendMod;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Плеер сбоку экрана: крутится виниловая пластинка, в центре обложка (превью трека или видео) и название.
 * Звук играет в твоём браузере или приложении, виджет только показывает, что сейчас включено. Данные берутся из открытых
 * страниц oEmbed (YouTube, Spotify) и страницы Яндекс Музыки; сам звук мод не загружает.
 */
public final class MusicPlayer {
    private static final ResourceLocation VINYL = ResourceLocation.fromNamespaceAndPath(AiFriendMod.MODID, "textures/gui/vinyl.png");
    private static final ResourceLocation DEFAULT_COVER = ResourceLocation.fromNamespaceAndPath(AiFriendMod.MODID, "textures/gui/cover_default.png");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final int SIZE = 72;

    private static boolean active;
    private static boolean spinning = true;
    private static String title = "";
    private static String subtitle = "";
    @Nullable
    private static ResourceLocation cover;
    private static int coverSerial;
    private static float angle;
    private static long lastNs;

    private MusicPlayer() {
    }

    public static boolean isActive() {
        return active;
    }

    /** Показать плеер. url может быть пустым (если названа только песня), тогда обложки нет. */
    public static void show(String fallbackTitle, @Nullable String url) {
        active = true;
        spinning = true;
        title = fallbackTitle;
        subtitle = "";
        releaseCover();
        if (url != null) {
            CompletableFuture.runAsync(() -> fetch(url, fallbackTitle));
        }
    }

    /** Трек из файла: название по имени файла, обложка из картинки рядом (если есть). */
    public static void showLocal(String name, @Nullable java.nio.file.Path coverFile) {
        active = true;
        spinning = true;
        title = name;
        subtitle = "";
        releaseCover();
        if (coverFile != null) {
            CompletableFuture.runAsync(() -> {
                try {
                    BufferedImage src = ImageIO.read(coverFile.toFile());
                    if (src != null) {
                        NativeImage circle = circleCrop(src, 96, false);
                        Minecraft.getInstance().execute(() -> setCover(circle));
                    }
                } catch (Exception e) {
                    AiFriendMod.LOGGER.warn("AI Friend: cannot read cover {}: {}", coverFile.getFileName(), e.toString());
                }
            });
        }
    }

    public static void hide() {
        active = false;
        releaseCover();
    }

    public static void toggleSpin() {
        if (com.aifriend.voice.MusicStreamer.isPlaying()) {
            com.aifriend.voice.MusicStreamer.togglePause();
            spinning = !spinning;
        } else if (active) {
            spinning = !spinning;
        }
    }

    // ------------------------------------------------------------------ данные

    private static void fetch(String url, String fallback) {
        try {
            String host = URI.create(url).getHost();
            host = host == null ? "" : host.toLowerCase(Locale.ROOT);
            String name = null;
            String author = "";
            String image = null;
            boolean youtube = host.contains("youtu");
            if (youtube || host.contains("spotify")) {
                String api = youtube ? "https://www.youtube.com/oembed?format=json&url=" : "https://open.spotify.com/oembed?url=";
                JsonObject o = JsonParser.parseString(get(api + URLEncoder.encode(url, StandardCharsets.UTF_8))).getAsJsonObject();
                name = str(o, "title");
                author = str(o, "author_name");
                image = str(o, "thumbnail_url");
            } else if (host.contains("yandex")) {
                String html = get(url);
                name = meta(html, "og:title");
                image = meta(html, "og:image");
                if (image != null) {
                    image = image.replace("%%", "400x400");
                    if (image.startsWith("//")) {
                        image = "https:" + image;
                    }
                }
            }
            String finalName = name == null || name.isBlank() ? fallback : name;
            String finalAuthor = author == null ? "" : author;
            Minecraft.getInstance().execute(() -> {
                if (active) {
                    title = finalName;
                    subtitle = finalAuthor;
                }
            });
            if (image != null && imageAllowed(image)) {
                byte[] bytes = getBytes(image);
                BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
                if (src != null) {
                    NativeImage circle = circleCrop(src, 96, youtube);
                    Minecraft.getInstance().execute(() -> setCover(circle));
                }
            }
        } catch (Exception e) {
            AiFriendMod.LOGGER.warn("AI Friend: could not load music info: {}", e.toString());
        }
    }

    private static boolean imageAllowed(String url) {
        try {
            URI u = URI.create(url);
            String h = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            return "https".equals(u.getScheme()) && (h.endsWith("ytimg.com") || h.endsWith("youtube.com") || h.endsWith("scdn.co")
                    || h.endsWith("spotifycdn.com") || h.endsWith("yandex.net") || h.endsWith("yandex.ru") || h.endsWith("yandex.com"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0 AIFriend").GET().build();
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + r.statusCode());
        }
        return r.body();
    }

    private static byte[] getBytes(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0 AIFriend").GET().build();
        HttpResponse<byte[]> r = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() / 100 != 2 || r.body().length > 4_000_000) {
            throw new IllegalStateException("HTTP " + r.statusCode());
        }
        return r.body();
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    @Nullable
    private static String meta(String html, String property) {
        Matcher m = Pattern.compile("<meta[^>]+property=\"" + Pattern.quote(property) + "\"[^>]+content=\"([^\"]*)\"").matcher(html);
        return m.find() ? m.group(1).replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'") : null;
    }

    /** Квадрат из центра картинки, масштаб до size x size и круглая маска (как центр пластинки). */
    private static NativeImage circleCrop(BufferedImage src, int size, boolean youtube) {
        int w = src.getWidth();
        int h = src.getHeight();
        int side = youtube ? (int) (h * 0.75) : Math.min(w, h); // у превью YouTube сверху и снизу чёрные полосы
        int sx = (w - side) / 2;
        int sy = (h - side) / 2;
        NativeImage out = new NativeImage(size, size, true);
        double r = size / 2.0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double dx = x + 0.5 - r;
                double dy = y + 0.5 - r;
                if (dx * dx + dy * dy > r * r) {
                    out.setPixelRGBA(x, y, 0);
                    continue;
                }
                int argb = src.getRGB(sx + x * side / size, sy + y * side / size);
                int a = 0xFF;
                int rr = (argb >> 16) & 0xFF;
                int gg = (argb >> 8) & 0xFF;
                int bb = argb & 0xFF;
                out.setPixelRGBA(x, y, (a << 24) | (bb << 16) | (gg << 8) | rr); // NativeImage хранит ABGR
            }
        }
        return out;
    }

    private static void setCover(NativeImage image) {
        if (!active) {
            image.close();
            return;
        }
        releaseCover();
        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(AiFriendMod.MODID, "music/cover_" + (coverSerial++));
        Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(image));
        cover = rl;
    }

    private static void releaseCover() {
        if (cover != null) {
            Minecraft.getInstance().getTextureManager().release(cover);
            cover = null;
        }
    }

    // ------------------------------------------------------------------ отрисовка

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!active || mc.options.hideGui) {
            return;
        }
        long now = System.nanoTime();
        if (lastNs != 0 && spinning) {
            angle = (angle + (now - lastNs) / 1.0e9F * 200.0F) % 360.0F; // ~33 оборота в минуту, как у пластинки
        }
        lastNs = now;

        int cx = g.guiWidth() - SIZE / 2 - 10;
        int cy = g.guiHeight() / 2;
        RenderSystem.enableBlend();
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().mulPose(Axis.ZP.rotationDegrees(angle));
        g.blit(VINYL, -SIZE / 2, -SIZE / 2, SIZE, SIZE, 0, 0, 128, 128, 128, 128);
        int c = (int) (SIZE * 0.5);
        if (cover != null) {
            g.blit(cover, -c / 2, -c / 2, c, c, 0, 0, 96, 96, 96, 96);
        } else {
            g.blit(DEFAULT_COVER, -c / 2, -c / 2, c, c, 0, 0, 96, 96, 96, 96);
        }
        g.pose().popPose();
        RenderSystem.disableBlend();

        Font font = mc.font;
        int right = g.guiWidth() - 6;
        int maxW = 130;
        String t = font.plainSubstrByWidth(title, maxW);
        g.drawString(font, t, right - font.width(t), cy + SIZE / 2 + 4, 0xFFFFFF, true);
        if (!subtitle.isEmpty()) {
            String s = font.plainSubstrByWidth(subtitle, maxW);
            g.drawString(font, s, right - font.width(s), cy + SIZE / 2 + 15, 0xAAAAAA, true);
        }
        drawButtons(g, mc);
    }

    // ------------------------------------------------------------------ кнопки

    private static final int BTN = 14;
    private static final int GAP = 3;
    /** Порядок слева направо: предыдущий, пауза, стоп, следующий, повтор. */
    private static final String[] BUTTONS = { "prev", "pause", "stop", "next", "loop" };

    private static int buttonX(int guiWidth, int i) {
        int total = BUTTONS.length * BTN + (BUTTONS.length - 1) * GAP;
        return guiWidth - 6 - total + i * (BTN + GAP);
    }

    private static int buttonY(int guiHeight) {
        return guiHeight / 2 + SIZE / 2 + 28;
    }

    private static void drawButtons(GuiGraphics g, Minecraft mc) {
        boolean stream = com.aifriend.voice.MusicStreamer.isPlaying();
        int mx = -1;
        int my = -1;
        if (mc.screen != null) { // мышь есть только когда открыт чат или меню
            mx = (int) (mc.mouseHandler.xpos() * g.guiWidth() / mc.getWindow().getScreenWidth());
            my = (int) (mc.mouseHandler.ypos() * g.guiHeight() / mc.getWindow().getScreenHeight());
        }
        int y = buttonY(g.guiHeight());
        for (int i = 0; i < BUTTONS.length; i++) {
            String id = BUTTONS[i];
            int x = buttonX(g.guiWidth(), i);
            boolean enabled = stream || id.equals("pause") || id.equals("stop");
            boolean hover = enabled && mx >= x && mx < x + BTN && my >= y && my < y + BTN;
            boolean on = id.equals("loop") && com.aifriend.voice.MusicStreamer.isLoop();
            g.fill(x, y, x + BTN, y + BTN, hover ? 0xCC555566 : on ? 0xCC3A6B3A : 0x99000000);
            int col = enabled ? 0xFFFFFFFF : 0xFF777777;
            boolean paused = stream ? com.aifriend.voice.MusicStreamer.isPaused() : !spinning;
            icon(g, id, x, y, col, paused);
        }
    }

    private static void tri(GuiGraphics g, int x, int y, int h, int col, boolean right) {
        // треугольник высотой h (по вертикали), основание слева (right) или справа
        int half = h / 2;
        for (int r = 0; r < h; r++) {
            int w = 1 + (r < half ? r : h - 1 - r) * 1;
            int x0 = right ? x : x + (half - w);
            g.fill(x0, y + r, x0 + w, y + r + 1, col);
        }
    }

    private static void icon(GuiGraphics g, String id, int x, int y, int col, boolean paused) {
        switch (id) {
            case "pause" -> {
                if (paused) {
                    tri(g, x + 5, y + 3, 8, col, true);
                } else {
                    g.fill(x + 4, y + 3, x + 6, y + 11, col);
                    g.fill(x + 8, y + 3, x + 10, y + 11, col);
                }
            }
            case "stop" -> g.fill(x + 4, y + 4, x + 10, y + 10, col);
            case "next" -> {
                tri(g, x + 3, y + 3, 8, col, true);
                g.fill(x + 10, y + 3, x + 11, y + 11, col);
            }
            case "prev" -> {
                g.fill(x + 3, y + 3, x + 4, y + 11, col);
                tri(g, x + 5, y + 3, 8, col, false);
            }
            default -> { // loop: рамка-кольцо со стрелкой
                g.fill(x + 3, y + 4, x + 11, y + 5, col);
                g.fill(x + 3, y + 9, x + 11, y + 10, col);
                g.fill(x + 3, y + 4, x + 4, y + 10, col);
                g.fill(x + 10, y + 4, x + 11, y + 7, col);
                g.fill(x + 9, y + 6, x + 12, y + 7, col);
            }
        }
    }

    /** Нажатие кнопки мышью (экран открыт). true - клик был по кнопке плеера. */
    public static boolean click(double mx, double my) {
        Minecraft mc = Minecraft.getInstance();
        if (!active || mc.options.hideGui) {
            return false;
        }
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        int y = buttonY(h);
        for (int i = 0; i < BUTTONS.length; i++) {
            int x = buttonX(w, i);
            if (mx >= x && mx < x + BTN && my >= y && my < y + BTN) {
                command(BUTTONS[i]);
                return true;
            }
        }
        return false;
    }

    /** Управление плеером: prev, pause, stop, next, loop. */
    public static void command(String id) {
        boolean stream = com.aifriend.voice.MusicStreamer.isPlaying();
        switch (id) {
            case "stop" -> {
                com.aifriend.voice.MusicStreamer.stop();
                hide();
            }
            case "pause" -> toggleSpin();
            case "next" -> {
                if (stream) {
                    com.aifriend.voice.MusicStreamer.next();
                }
            }
            case "prev" -> {
                if (stream) {
                    com.aifriend.voice.MusicStreamer.previous();
                }
            }
            case "loop" -> {
                if (stream) {
                    com.aifriend.voice.MusicStreamer.toggleLoop();
                }
            }
            default -> { }
        }
    }
}
