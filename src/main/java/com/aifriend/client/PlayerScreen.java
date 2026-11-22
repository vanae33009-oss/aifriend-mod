package com.aifriend.client;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.lwjgl.glfw.GLFW;

import com.aifriend.MusicCatalog;
import com.aifriend.voice.MusicStreamer;
import com.aifriend.voice.VoiceSettings;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Окно плеера сбоку экрана: пластинка с обложкой, перемотка и таймер, перемешивание, предыдущий / пауза / следующий, повтор,
 * громкость, строка для ссылки и кнопка папки с музыкой. Игра за ним не ставится на паузу.
 */
public final class PlayerScreen extends Screen {
    private static final int W = 208;
    private static final int ICON = 0xFFD8D8D8;
    private static final int DIM = 0xFF6E6E6E;
    private static final int GREEN = 0xFF7BD88F;

    private int x0;
    private int y0;
    private int d; // диаметр пластинки
    private int h;
    private int progY;
    private int ctrlY;
    private int volY;
    private int urlY;

    private EditBox url;
    private boolean showList;
    private int listScroll;
    private boolean dragSeek;
    private boolean dragVolume;
    private float seekFrac;

    public PlayerScreen() {
        super(Component.translatable("aifriend.player.title"));
    }

    @Override
    protected void init() {
        int avail = height - 8;
        d = Mth.clamp(avail - 158, 56, 100);
        h = d + 158;
        x0 = width - W - 8;
        y0 = Math.max(4, (height - h) / 2);
        progY = y0 + 28 + d + 32;
        ctrlY = y0 + 28 + d + 60;
        volY = y0 + 28 + d + 86;
        urlY = y0 + 28 + d + 106;

        url = new EditBox(font, x0 + 12, urlY, W - 12 - 12 - 22, 18, Component.translatable("aifriend.player.url"));
        url.setMaxLength(300);
        url.setHint(Component.translatable("aifriend.player.url_hint").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        addRenderableWidget(url);
    }

    // ------------------------------------------------------------------ отрисовка

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        // игра остаётся видна за плеером
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static void round(GuiGraphics g, int x, int y, int w, int hh, int c) {
        g.fill(x + 3, y, x + w - 3, y + hh, c);
        g.fill(x + 1, y + 1, x + w - 1, y + hh - 1, c);
        g.fill(x, y + 3, x + w, y + hh - 3, c);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        round(g, x0 - 1, y0 - 1, W + 2, h + 2, 0xFF2A2A2A);
        round(g, x0, y0, W, h, 0xF2080808);

        icChevron(g, x0 + 17, y0 + 13, ICON);
        icDots(g, x0 + W - 15, y0 + 13, showList ? GREEN : ICON);

        MusicPlayer.tickAngle();
        MusicPlayer.drawDisc(g, x0 + W / 2, y0 + 28 + d / 2, d);

        boolean stream = MusicStreamer.isPlaying();
        String title = MusicPlayer.isActive() ? MusicPlayer.title() : "";
        if (title.isEmpty()) {
            title = Component.translatable("aifriend.player.idle").getString();
        }
        int ty = y0 + 28 + d + 6;
        String t = font.plainSubstrByWidth(title, W - 24);
        g.drawString(font, t, x0 + (W - font.width(t)) / 2, ty, 0xFFEFEFEF, false);
        String sub = MusicPlayer.isActive() ? MusicPlayer.subtitle() : "";
        if (!sub.isEmpty()) {
            String s = font.plainSubstrByWidth(sub, W - 24);
            g.drawString(font, s, x0 + (W - font.width(s)) / 2, ty + 11, 0xFF8A8A8A, false);
        }

        // перемотка и таймер
        int bx = x0 + 12;
        int bw = W - 24;
        double dur = MusicStreamer.durationSec();
        double pos = MusicStreamer.positionSec();
        float frac = dragSeek ? seekFrac : (dur > 0 ? (float) Mth.clamp(pos / dur, 0, 1) : 0);
        g.fill(bx, progY, bx + bw, progY + 2, 0xFF2E2E2E);
        g.fill(bx, progY, bx + Math.round(bw * frac), progY + 2, stream ? 0xFFCFCFCF : 0xFF444444);
        int kx = bx + Math.round(bw * frac);
        g.fill(kx - 2, progY - 2, kx + 3, progY + 4, stream ? 0xFFE6E6E6 : 0xFF555555);
        String time = clock(dragSeek ? dur * seekFrac : pos) + " / " + clock(dur);
        g.drawString(font, time, bx, progY + 8, 0xFF8A8A8A, false);

        // кнопки
        boolean playing = stream ? !MusicStreamer.isPaused() : (MusicPlayer.isActive() && MusicPlayer.isSpinning());
        icShuffle(g, x0 + 22, ctrlY, MusicStreamer.isShuffle() ? GREEN : ICON);
        icPrev(g, x0 + W / 2 - 46, ctrlY, stream ? ICON : DIM);
        circle(g, x0 + W / 2, ctrlY, 13, 0xFF6A6A6A);
        if (playing) {
            g.fill(x0 + W / 2 - 4, ctrlY - 5, x0 + W / 2 - 1, ctrlY + 6, ICON);
            g.fill(x0 + W / 2 + 2, ctrlY - 5, x0 + W / 2 + 5, ctrlY + 6, ICON);
        } else {
            tri(g, x0 + W / 2 - 3, ctrlY - 6, 12, ICON, true);
        }
        icNext(g, x0 + W / 2 + 46, ctrlY, stream ? ICON : DIM);
        icLoop(g, x0 + W - 22, ctrlY, MusicStreamer.isLoop() ? GREEN : ICON);

        // громкость
        float vol = (float) VoiceSettings.get().musicVolume;
        icSpeaker(g, x0 + W - 90, volY, ICON);
        int vx = x0 + W - 76;
        int vw = 62;
        g.fill(vx, volY, vx + vw, volY + 2, 0xFF2E2E2E);
        g.fill(vx, volY, vx + Math.round(vw * vol), volY + 2, 0xFFCFCFCF);
        int vk = vx + Math.round(vw * vol);
        g.fill(vk - 2, volY - 2, vk + 3, volY + 4, 0xFFE6E6E6);

        // строка ссылки и папка
        icFolder(g, x0 + W - 21, urlY + 3, MusicLinkHover(mx, my) ? GREEN : ICON);
        super.render(g, mx, my, pt);

        if (showList) {
            drawList(g, mx, my);
        }
        // подсказка под курсором
        if (inFolder(mx, my)) {
            g.renderTooltip(font, Component.translatable("aifriend.player.folder"), mx, my);
        }
    }

    private boolean MusicLinkHover(int mx, int my) {
        return inFolder(mx, my);
    }

    private boolean inFolder(double mx, double my) {
        return mx >= x0 + W - 24 && mx < x0 + W - 8 && my >= urlY && my < urlY + 18;
    }

    private static String clock(double sec) {
        int s = (int) Math.max(0, Math.round(sec));
        return (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }

    private int listTop() {
        return y0 + 28;
    }

    private int listRows() {
        return Math.max(3, (d + 70) / 12);
    }

    private void drawList(GuiGraphics g, int mx, int my) {
        int top = listTop();
        int rows = listRows();
        g.fill(x0 + 6, top, x0 + W - 6, top + rows * 12 + 6, 0xF0101010);
        g.renderOutline(x0 + 6, top, W - 12, rows * 12 + 6, 0xFF333333);
        List<Path> all = MusicCatalog.tracks();
        if (all.isEmpty()) {
            g.drawString(font, font.plainSubstrByWidth(Component.translatable("aifriend.player.empty").getString(), W - 28), x0 + 12, top + 8, 0xFF8A8A8A, false);
            return;
        }
        String now = MusicStreamer.isPlaying() ? MusicStreamer.current() : "";
        for (int i = 0; i < rows; i++) {
            int idx = listScroll + i;
            if (idx >= all.size()) {
                break;
            }
            String name = MusicCatalog.title(all.get(idx));
            int y = top + 4 + i * 12;
            boolean hover = mx >= x0 + 8 && mx < x0 + W - 8 && my >= y && my < y + 12;
            if (hover) {
                g.fill(x0 + 8, y, x0 + W - 8, y + 12, 0xFF262626);
            }
            g.drawString(font, font.plainSubstrByWidth(name, W - 32), x0 + 12, y + 2, name.equals(now) ? GREEN : 0xFFDDDDDD, false);
        }
    }

    // ------------------------------------------------------------------ ввод

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (showList && handleListClick(mx, my)) {
                return true;
            }
            if (in(mx, my, x0 + 6, y0 + 4, 30, 18)) { // свернуть
                onClose();
                return true;
            }
            if (in(mx, my, x0 + W - 30, y0 + 4, 26, 18)) {
                showList = !showList;
                listScroll = 0;
                return true;
            }
            if (inFolder(mx, my)) {
                MusicCatalog.ensureDir();
                Util.getPlatform().openFile(MusicCatalog.dir().toFile());
                return true;
            }
            if (in(mx, my, x0 + 12, progY - 5, W - 24, 12) && MusicStreamer.isPlaying()) {
                dragSeek = true;
                seekFrac = (float) Mth.clamp((mx - (x0 + 12)) / (W - 24), 0, 1);
                return true;
            }
            if (in(mx, my, x0 + W - 80, volY - 5, 70, 12)) {
                dragVolume = true;
                setVolume(mx);
                return true;
            }
            if (in(mx, my, x0 + 12, ctrlY - 12, 22, 24)) {
                MusicStreamer.toggleShuffle();
                return true;
            }
            if (in(mx, my, x0 + W / 2 - 58, ctrlY - 12, 24, 24)) {
                MusicPlayer.command("prev");
                return true;
            }
            if (Math.hypot(mx - (x0 + W / 2), my - ctrlY) <= 14) {
                playPause();
                return true;
            }
            if (in(mx, my, x0 + W / 2 + 34, ctrlY - 12, 24, 24)) {
                MusicPlayer.command("next");
                return true;
            }
            if (in(mx, my, x0 + W - 34, ctrlY - 12, 24, 24)) {
                MusicPlayer.command("loop");
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private boolean handleListClick(double mx, double my) {
        int top = listTop();
        int rows = listRows();
        if (!in(mx, my, x0 + 6, top, W - 12, rows * 12 + 6)) {
            return false;
        }
        List<Path> all = MusicCatalog.tracks();
        int idx = listScroll + (int) ((my - (top + 4)) / 12);
        if (idx >= 0 && idx < all.size() && my >= top + 4) {
            MusicStreamer.play(all, MusicStreamer.isShuffle(), all.get(idx), MusicStreamer.isLoop());
            showList = false;
        }
        return true;
    }

    private void playPause() {
        if (MusicStreamer.isPlaying()) {
            MusicStreamer.togglePause();
            MusicPlayer.toggleSpin();
        } else if (MusicPlayer.isActive()) {
            MusicPlayer.toggleSpin(); // ссылка из браузера: управлять звуком мы не можем, крутится только пластинка
        } else {
            List<Path> all = MusicCatalog.tracks();
            if (!all.isEmpty()) {
                MusicStreamer.play(all, MusicStreamer.isShuffle(), null, MusicStreamer.isLoop());
            } else {
                showList = true;
            }
        }
    }

    private void setVolume(double mx) {
        VoiceSettings.get().musicVolume = Mth.clamp((mx - (x0 + W - 76)) / 62.0, 0, 1);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragSeek) {
            seekFrac = (float) Mth.clamp((mx - (x0 + 12)) / (W - 24), 0, 1);
            return true;
        }
        if (dragVolume) {
            setVolume(mx);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragSeek) {
            dragSeek = false;
            MusicStreamer.seek(seekFrac);
            return true;
        }
        if (dragVolume) {
            dragVolume = false;
            VoiceSettings.save();
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (showList) {
            int max = Math.max(0, MusicCatalog.tracks().size() - listRows());
            listScroll = Mth.clamp(listScroll - (int) Math.signum(sy), 0, max);
            return true;
        }
        if (in(mx, my, x0, y0, W, h)) { // колесо над плеером меняет громкость
            VoiceSettings.get().musicVolume = Mth.clamp(VoiceSettings.get().musicVolume + Math.signum(sy) * 0.05, 0, 1);
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && url.isFocused()) {
            String q = url.getValue().trim();
            if (!q.isEmpty()) {
                MusicLinks.handle(q, this);
                url.setValue("");
            }
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void removed() {
        VoiceSettings.save();
    }

    private static boolean in(double mx, double my, int x, int y, int w, int hh) {
        return mx >= x && mx < x + w && my >= y && my < y + hh;
    }

    // ------------------------------------------------------------------ значки

    private static void px(GuiGraphics g, int x, int y, int c) {
        g.fill(x, y, x + 1, y + 1, c);
    }

    private static void line(GuiGraphics g, int x1, int y1, int x2, int y2, int c) {
        int dx = Math.abs(x2 - x1);
        int dy = -Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            px(g, x1, y1, c);
            if (x1 == x2 && y1 == y2) {
                break;
            }
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x1 += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y1 += sy;
            }
        }
    }

    private static void circle(GuiGraphics g, int cx, int cy, int r, int c) {
        for (int deg = 0; deg < 360; deg += 3) {
            double a = Math.toRadians(deg);
            px(g, cx + (int) Math.round(Math.cos(a) * r), cy + (int) Math.round(Math.sin(a) * r), c);
        }
    }

    /** Треугольник высотой hh: вершина справа (right) или слева. Ширина основания = hh / 2. */
    private static void tri(GuiGraphics g, int x, int y, int hh, int c, boolean right) {
        int baseW = Math.max(2, hh / 2 + 1);
        int half = hh / 2;
        for (int r = 0; r < hh; r++) {
            double t = 1.0 - Math.abs(r + 0.5 - half) / half; // 0 по краям, 1 в середине
            int w = Math.max(1, (int) Math.round(t * baseW));
            int xs = right ? x : x + baseW - w;
            g.fill(xs, y + r, xs + w, y + r + 1, c);
        }
    }

    private static void icChevron(GuiGraphics g, int cx, int cy, int c) {
        line(g, cx - 6, cy - 2, cx, cy + 3, c);
        line(g, cx, cy + 3, cx + 6, cy - 2, c);
        line(g, cx - 6, cy - 1, cx, cy + 4, c);
        line(g, cx, cy + 4, cx + 6, cy - 1, c);
    }

    private static void icDots(GuiGraphics g, int cx, int cy, int c) {
        for (int i = -1; i <= 1; i++) {
            g.fill(cx - 1, cy + i * 5 - 1, cx + 1, cy + i * 5 + 1, c);
        }
    }

    private static void icPrev(GuiGraphics g, int cx, int cy, int c) {
        g.fill(cx - 6, cy - 6, cx - 4, cy + 6, c);
        tri(g, cx - 3, cy - 6, 12, c, false);
    }

    private static void icNext(GuiGraphics g, int cx, int cy, int c) {
        tri(g, cx - 5, cy - 6, 12, c, true);
        g.fill(cx + 5, cy - 6, cx + 7, cy + 6, c);
    }

    private static void icShuffle(GuiGraphics g, int cx, int cy, int c) {
        line(g, cx - 7, cy + 4, cx + 5, cy - 4, c);
        line(g, cx - 7, cy - 4, cx + 5, cy + 4, c);
        g.fill(cx + 4, cy - 6, cx + 8, cy - 2, c);
        g.fill(cx + 4, cy + 2, cx + 8, cy + 6, c);
    }

    private static void icLoop(GuiGraphics g, int cx, int cy, int c) {
        g.fill(cx - 7, cy - 4, cx + 4, cy - 3, c);
        g.fill(cx - 7, cy + 3, cx + 7, cy + 4, c);
        g.fill(cx - 7, cy - 4, cx - 6, cy + 4, c);
        g.fill(cx + 6, cy - 1, cx + 7, cy + 4, c);
        g.fill(cx + 3, cy - 6, cx + 4, cy - 1, c);
        g.fill(cx + 4, cy - 5, cx + 5, cy - 2, c);
        g.fill(cx + 5, cy - 4, cx + 6, cy - 3, c);
    }

    private static void icSpeaker(GuiGraphics g, int cx, int cy, int c) {
        g.fill(cx - 5, cy - 2, cx - 2, cy + 3, c);
        tri(g, cx - 2, cy - 5, 10, c, false);
        line(g, cx + 3, cy - 2, cx + 3, cy + 3, c);
        line(g, cx + 5, cy - 4, cx + 5, cy + 5, c);
    }

    private static void icFolder(GuiGraphics g, int x, int y, int c) {
        g.fill(x, y, x + 6, y + 2, c);
        g.fill(x, y + 2, x + 14, y + 12, c);
        g.fill(x + 1, y + 4, x + 13, y + 11, 0xFF080808);
    }
}
