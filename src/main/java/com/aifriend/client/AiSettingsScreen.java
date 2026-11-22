package com.aifriend.client;

import java.util.List;
import java.util.concurrent.CompletionException;

import javax.annotation.Nullable;

import com.aifriend.SkinCatalog;
import com.aifriend.ai.AiClient;
import com.aifriend.ai.AiSettings;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Настройки ИИ. Вкладки:
 * «Официальные API» - готовые провайдеры (ключ + модель),
 * «Кастомный API» - любой свой endpoint: формат, название, URL, ключ, модель,
 * «Бот» - имя, характер, пол, скин,
 * «Поведение» - заговаривает первым, самостоятельность, дальность слуха,
 * «Голос» - отдельный экран с микрофоном, озвучкой и распознаванием речи.
 * Изменения применяются сразу и сохраняются в config/aifriend.json при закрытии.
 */
public class AiSettingsScreen extends BaseSettingsScreen {
    private enum Tab {
        OFFICIAL, CUSTOM, BOT, BEHAVIOR
    }

    private static final String[] TAB_KEYS = { "aifriend.tab.official", "aifriend.tab.custom", "aifriend.tab.bot", "aifriend.tab.behavior", "aifriend.tab.voice" };

    private final AiSettings.Data data = AiSettings.get();
    private Tab tab;

    public AiSettingsScreen(@Nullable Screen parent) {
        super(Component.translatable("aifriend.screen.title"), parent);
        this.tab = data.useCustom ? Tab.CUSTOM : Tab.OFFICIAL;
    }

    @Override
    protected void build() {
        addTabs(TAB_KEYS, tab.ordinal(), index -> {
            if (index == 4) {
                AiSettings.save();
                this.minecraft.setScreen(new VoiceSettingsScreen(this));
                return;
            }
            tab = Tab.values()[index];
            if (tab == Tab.OFFICIAL) {
                data.useCustom = false;
            } else if (tab == Tab.CUSTOM) {
                data.useCustom = true;
            }
            status = Component.empty();
            rebuildWidgets();
        });

        switch (tab) {
            case OFFICIAL -> buildOfficial();
            case CUSTOM -> buildCustom();
            case BOT -> buildBot();
            case BEHAVIOR -> buildBehavior();
        }

        Component done = Component.translatable("aifriend.button.done");
        if (tab == Tab.BOT || tab == Tab.BEHAVIOR) {
            addBottomButtons(null, null, done, this::onClose);
        } else {
            addBottomButtons(Component.translatable("aifriend.button.test"), this::runTest, done, this::onClose);
        }
    }

    private void buildOfficial() {
        AiSettings.Provider provider = data.provider();
        AiSettings.Profile profile = data.profile(provider);

        addRenderableWidget(CycleButton.builder((AiSettings.Provider p) -> Component.literal(p.label))
                .withValues(AiSettings.Provider.values())
                .withInitialValue(provider)
                .create(left, row(0), WIDTH, 20, Component.translatable("aifriend.field.provider"), (btn, value) -> {
                    data.provider = value.name();
                    rebuildWidgets();
                }));

        addField(1, "aifriend.field.api_key", profile.apiKey, 512, true, v -> profile.apiKey = v, "");
        addField(2, "aifriend.field.model", profile.model, 128, false, v -> profile.model = v, provider.defaultModel);
        addNote(3, Component.literal("Endpoint: " + provider.baseUrl), 0x808080);
    }

    private void buildCustom() {
        addRenderableWidget(CycleButton.builder((AiSettings.ApiFormat f) -> Component.literal(f.label))
                .withValues(AiSettings.ApiFormat.values())
                .withInitialValue(data.customFormat())
                .create(left, row(0), WIDTH, 20, Component.translatable("aifriend.field.format"),
                        (btn, value) -> data.customFormat = value.name()));

        addField(1, "aifriend.field.custom_name", data.customName, 64, false, v -> data.customName = v, "My provider");
        addField(2, "aifriend.field.base_url", data.customBaseUrl, 256, false, v -> data.customBaseUrl = v, "http://localhost:11434/v1");
        addField(3, "aifriend.field.api_key", data.customApiKey, 512, true, v -> data.customApiKey = v, "");
        addField(4, "aifriend.field.model", data.customModel, 128, false, v -> data.customModel = v, "model name");
    }

    private void buildBot() {
        SkinCatalog.ensureDir();
        addField(0, "aifriend.field.bot_name", data.botName, 32, false, v -> data.botName = v, "random");
        addField(1, "aifriend.field.personality", data.personality, 300, false, v -> data.personality = v, "friendly, sarcastic...");

        addRenderableWidget(CycleButton.<String>builder(g -> Component.translatable("aifriend.gender." + g))
                .withValues(List.of("male", "female"))
                .withInitialValue("female".equals(data.botGender) ? "female" : "male")
                .create(left, row(2), WIDTH, 20, Component.translatable("aifriend.field.gender"), (btn, value) -> data.botGender = value));

        java.util.List<String> skins = new java.util.ArrayList<>();
        skins.add("");
        skins.addAll(SkinCatalog.all());
        String current = skins.contains(data.botSkin) ? data.botSkin : "";
        addRenderableWidget(CycleButton.<String>builder(id -> id.isEmpty() ? Component.translatable("aifriend.skin.auto") : Component.literal(id))
                .withValues(skins)
                .withInitialValue(current)
                .create(left, row(3), WIDTH, 20, Component.translatable("aifriend.field.skin"), (btn, value) -> data.botSkin = value));

        addField(4, "aifriend.field.backstory", data.backstory, 600, false, v -> data.backstory = v, "who he is, his past, goals...");
        addNote(5, Component.translatable("aifriend.note.story", "config/aifriend/story.txt"), 0x808080);
        addNote(6, Component.translatable("aifriend.note.skins", "config/aifriend/skins"), 0x808080);
    }

    private void buildBehavior() {
        addRenderableWidget(CycleButton.onOffBuilder(data.proactiveChat)
                .create(left, row(0), WIDTH, 20, Component.translatable("aifriend.field.proactive"), (btn, value) -> data.proactiveChat = value));

        addRenderableWidget(CycleButton.onOffBuilder(data.autonomy)
                .create(left, row(1), WIDTH, 20, Component.translatable("aifriend.field.autonomy"), (btn, value) -> data.autonomy = value));

        // 15..300 секунд
        addSlider(2, (data.autonomySeconds - 15) / 285.0,
                v -> Component.translatable("aifriend.field.autonomy_interval", 15 + (int) Math.round(v * 285)),
                v -> data.autonomySeconds = 15 + (int) Math.round(v * 285));
        // 8..64 блока
        addSlider(3, (data.hearingRadius - 8) / 56.0,
                v -> Component.translatable("aifriend.field.hearing", 8 + (int) Math.round(v * 56)),
                v -> data.hearingRadius = 8 + (int) Math.round(v * 56));

        addRenderableWidget(CycleButton.onOffBuilder(data.autoSpawn)
                .create(left, row(4), WIDTH, 20, Component.translatable("aifriend.field.auto_spawn"), (btn, value) -> data.autoSpawn = value));

        addRenderableWidget(CycleButton.onOffBuilder(data.debugMessages)
                .create(left, row(5), WIDTH, 20, Component.translatable("aifriend.field.debug"), (btn, value) -> data.debugMessages = value));

        addRenderableWidget(CycleButton.onOffBuilder(data.musicEnabled)
                .create(left, row(6), WIDTH, 20, Component.translatable("aifriend.field.music"), (btn, value) -> data.musicEnabled = value));

        java.util.List<String> services = java.util.List.of("spotify", "yandex", "youtube");
        addRenderableWidget(CycleButton.builder((String s) -> Component.translatable("aifriend.music.service." + s))
                .withValues(services)
                .withInitialValue(services.contains(data.musicService) ? data.musicService : "spotify")
                .create(left, row(7), WIDTH, 20, Component.translatable("aifriend.field.music_service"), (btn, value) -> data.musicService = value));
    }

    private void runTest() {
        AiSettings.Resolved api = AiSettings.resolve();
        if (!api.usable()) {
            status = Component.translatable("aifriend.status.not_configured").withStyle(ChatFormatting.RED);
            return;
        }
        status = Component.translatable("aifriend.status.testing", api.label()).withStyle(ChatFormatting.GRAY);
        AiClient.chat(api, "You are a connection test. Reply with one short friendly sentence.",
                List.of(new AiClient.Message("user", "Say hello.")), 0.5, 300)
                .whenComplete((reply, error) -> {
                    if (error != null) {
                        Throwable t = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                        status = Component.literal(AiClient.shorten(String.valueOf(t.getMessage()), 90)).withStyle(ChatFormatting.RED);
                    } else {
                        status = Component.translatable("aifriend.status.ok", AiClient.shorten(reply, 70)).withStyle(ChatFormatting.GREEN);
                    }
                });
    }

    @Override
    protected Component activeLine() {
        AiSettings.Resolved active = AiSettings.resolve();
        return Component.translatable("aifriend.status.active", active.label(), active.model())
                .withStyle(active.usable() ? ChatFormatting.GRAY : ChatFormatting.GOLD);
    }

    @Override
    protected void save() {
        AiSettings.save();
    }
}
