package com.aifriend.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.aifriend.AiFriendMod;
import com.aifriend.voice.VoiceSettings;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

/** Приветственное окно: показывается один раз при первом входе в мир, текст на русском и английском, ссылки кликабельны. */
public final class WelcomeScreen extends Screen {
    private static final ResourceLocation BG = ResourceLocation.fromNamespaceAndPath(AiFriendMod.MODID, "textures/gui/welcome_bg.png");
    private static final String DISCORD = "https://discord.gg/PUdgcp7Nc4";
    private static final String BOOSTY = "https://boosty.to/hordvei_studio/donate";

    private record Line(FormattedCharSequence text, int y) {
    }

    private boolean ru;
    private final List<Line> lines = new ArrayList<>();
    private int contentHeight;
    private double scroll;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    public WelcomeScreen() {
        super(Component.literal("AI Friend"));
        String lang = Minecraft.getInstance().options.languageCode;
        this.ru = lang != null && lang.toLowerCase(Locale.ROOT).startsWith("ru");
    }

    @Override
    protected void init() {
        panelW = Math.min(width - 24, 400);
        panelX = (width - panelW) / 2;
        panelY = 34;
        panelH = Math.max(60, height - panelY - 40);

        addRenderableWidget(Button.builder(Component.literal(ru ? "Прочитал" : "Got it"), b -> onClose())
                .bounds(width / 2 - 80, height - 30, 160, 20).build());
        addRenderableWidget(Button.builder(Component.literal(ru ? "RU / en" : "ru / EN"), b -> {
            ru = !ru;
            scroll = 0;
            rebuildWidgets();
        }).bounds(panelX + panelW - 62, 8, 62, 20).build());

        buildLines();
    }

    private void buildLines() {
        lines.clear();
        int w = panelW - 24;
        int y = 0;
        for (Component para : paragraphs()) {
            for (FormattedCharSequence seq : font.split(para, w)) {
                lines.add(new Line(seq, y));
                y += 11;
            }
            y += 5;
        }
        contentHeight = y;
    }

    // ------------------------------------------------------------------ текст

    private static MutableComponent t(String s) {
        return Component.literal(s);
    }

    private static MutableComponent head(String s) {
        return Component.literal(s).withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD);
    }

    private static MutableComponent link(String url) {
        return Component.literal(url).withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)));
    }

    private static MutableComponent studio(String s) {
        return Component.literal(s).withStyle(ChatFormatting.LIGHT_PURPLE);
    }

    private List<Component> paragraphs() {
        List<Component> p = new ArrayList<>();
        if (ru) {
            p.add(t("Спасибо, что скачали мой мод! Это начальная версия, её делает ").append(studio("HORDVEI STUDIO"))
                    .append(t(". В будущем я планирую его дорабатывать, либо брошу, как пойдёт)")));
            p.add(head("Краткая инструкция: что такое мод «AI Friend»"));
            p.add(t("Это ИИ-бот, которого я создал, чтобы людям было интереснее проводить время и не так одиноко. По данным ВОЗ, примерно каждый восьмой человек "
                    + "в мире (около 970 млн) живёт с психическим расстройством, и дружеская компания порой поддерживает. Бот не заменяет врача или специалиста. "
                    + "Я буду дорабатывать его, пока мне будет что в нём улучшать. Сейчас в нём есть:"));
            p.add(t("1. ").withStyle(ChatFormatting.LIGHT_PURPLE).append(t("Музыка: по ссылке Spotify, Яндекс Музыки или YouTube (открывается в браузере, а в игре появляется "
                    + "пластинка с обложкой), а также из своих файлов .mp3, .ogg и .wav: положи их в папку «config/aifriend/music», и они заиграют прямо в игре.")));
            p.add(t("2. ").withStyle(ChatFormatting.LIGHT_PURPLE).append(t("Нажми «K»: откроется меню бота. Там подключается API-ключ нейросети и настраиваются голос и озвучка текста.")));
            p.add(t("3. ").withStyle(ChatFormatting.LIGHT_PURPLE).append(t("Можно задать боту личность и поведение: расскажи ему о себе и о том, каким должен быть он сам, "
                    + "чтобы ему было проще подстроиться под тебя и общаться с тобой. Длинную историю можно положить в файл «config/aifriend/story.txt».")));
            p.add(t("4. ").withStyle(ChatFormatting.LIGHT_PURPLE).append(t("Озвучку текста можно подключить любым голосом через ElevenLabs, Fish Audio или свой адрес: "
                    + "он настраивается так же, как свой API-ключ, URL и Voice ID.")));
            p.add(t("5. ").withStyle(ChatFormatting.LIGHT_PURPLE).append(t("Пока это всё, в будущем список будет пополняться. Поддержать мои проекты можно, подписавшись на Discord: "))
                    .append(link(DISCORD)).append(t(" и помочь развитию мода донатом на Boosty: ")).append(link(BOOSTY)).append(t("  :)")));
        } else {
            p.add(t("Thank you for downloading my mod! This is its early version, made by ").append(studio("HORDVEI STUDIO"))
                    .append(t(". I plan to keep improving it, or I may drop it, we'll see)")));
            p.add(head("Quick guide: what \"AI Friend\" is"));
            p.add(t("It's an AI companion I made so that people can have a more fun and less lonely time. According to the WHO, about one in eight people in the world "
                    + "(around 970 million) live with a mental disorder, and friendly company can sometimes help. The bot is not a substitute for a doctor or a professional. "
                    + "I'll keep working on it for as long as there is something to improve. Right now it can do this:"));
            p.add(t("1. ").withStyle(ChatFormatting.LIGHT_PURPLE).append(t("Music: by a Spotify, Yandex Music or YouTube link (opens in your browser, and a record with cover art "
                    + "appears in the game), or from your own .mp3, .ogg and .wav files: drop them into \"config/aifriend/music\" and they play right in the game.")));
            p.add(t("2. ").withStyle(ChatFormatting.LIGHT_PURPLE).append(t("Press \"K\" to open the bot menu. There you add your AI API key and set up the voice and text-to-speech.")));
            p.add(t("3. ").withStyle(ChatFormatting.LIGHT_PURPLE).append(t("You can give the bot a personality and behaviour: tell it about yourself and what it should be like, "
                    + "so it can adapt to you and talk to you more easily. A longer backstory can go into the file \"config/aifriend/story.txt\".")));
            p.add(t("4. ").withStyle(ChatFormatting.LIGHT_PURPLE).append(t("You can connect text-to-speech with any voice through ElevenLabs, Fish Audio or a custom address: "
                    + "it is set up just like a custom API key, URL and Voice ID.")));
            p.add(t("5. ").withStyle(ChatFormatting.LIGHT_PURPLE).append(t("That's all for now, and the list will grow. You can support my projects by joining the Discord: "))
                    .append(link(DISCORD)).append(t(" and help the mod grow with a donation on Boosty: ")).append(link(BOOSTY)).append(t("  :)")));
        }
        return p;
    }

    // ------------------------------------------------------------------ отрисовка и ввод

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.blit(BG, 0, 0, width, height, 0, 0, 480, 269, 480, 269);
        g.fill(0, 0, width, height, 0x66000000);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.pose().pushPose();
        g.pose().translate(panelX, 10, 0);
        g.pose().scale(2.0F, 2.0F, 1.0F);
        g.drawString(font, "AI Friend", 0, 0, 0xFFC9A7FF, true);
        g.pose().popPose();

        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xB0101018);
        g.renderOutline(panelX, panelY, panelW, panelH, 0x66FFFFFF);
        g.enableScissor(panelX, panelY + 2, panelX + panelW, panelY + panelH - 2);
        int top = panelY + 8 - (int) scroll;
        for (Line l : lines) {
            int y = top + l.y;
            if (y > panelY - 12 && y < panelY + panelH) {
                g.drawString(font, l.text, panelX + 12, y, 0xFFFFFF, true);
            }
        }
        g.disableScissor();
        int max = maxScroll();
        if (max > 0) { // полоса прокрутки
            int barH = Math.max(16, panelH * panelH / (contentHeight + 16));
            int barY = panelY + (int) ((panelH - barH) * (scroll / max));
            g.fill(panelX + panelW - 4, barY, panelX + panelW - 2, barY + barH, 0x99FFFFFF);
        }
    }

    private int maxScroll() {
        return Math.max(0, contentHeight + 16 - panelH);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        scroll = Mth.clamp(scroll - dy * 14, 0, maxScroll());
        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        if (button == 0 && mx >= panelX && mx < panelX + panelW && my >= panelY && my < panelY + panelH) {
            int top = panelY + 8 - (int) scroll;
            for (Line l : lines) {
                int y = top + l.y;
                if (my >= y && my < y + 10) {
                    Style style = font.getSplitter().componentStyleAtWidth(l.text, (int) (mx - (panelX + 12)));
                    if (style != null && handleComponentClicked(style)) { // откроется окно подтверждения ссылки
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        markSeen();
    }

    // ------------------------------------------------------------------ показывается один раз

    private static int pending;

    private static void markSeen() {
        VoiceSettings.get().welcomeSeen = true;
        VoiceSettings.save();
    }

    /** Вызывается при входе в мир. */
    static void onWorldJoin() {
        if (!VoiceSettings.get().welcomeSeen) {
            pending = 60;
        }
    }

    /** Каждый тик клиента: открывает окно, когда игрок уже в мире и нет других экранов. */
    static void tick(Minecraft mc) {
        if (pending <= 0) {
            return;
        }
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (--pending > 0) {
            return;
        }
        if (mc.screen != null) {
            pending = 20; // что-то уже открыто, подождём
            return;
        }
        pending = 0;
        mc.setScreen(new WelcomeScreen());
    }
}
