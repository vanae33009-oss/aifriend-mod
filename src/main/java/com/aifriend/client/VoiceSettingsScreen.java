package com.aifriend.client;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.aifriend.voice.Mic;
import com.aifriend.voice.VoiceManager;
import com.aifriend.voice.VoiceSettings;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Настройки голоса. Вкладки:
 * «Звук» - режим микрофона, устройство, чувствительность с индикатором уровня, громкость озвучки;
 * «Озвучка» - ElevenLabs, Fish Audio или свой API (формат, URL, ключ, голос, модель);
 * «Распознавание» - OpenAI Whisper, Groq, ElevenLabs Scribe или свой API.
 * Всё хранится только на этом компьютере в config/aifriend-voice.json.
 */
public class VoiceSettingsScreen extends BaseSettingsScreen {
    private enum Tab {
        AUDIO, SPEECH, RECOGNITION
    }

    private static final String[] TAB_KEYS = { "aifriend.tab.audio", "aifriend.tab.speech", "aifriend.tab.recognition" };
    private static final double THR_MIN = 0.005;
    private static final double THR_MAX = VoiceManager.METER_MAX;

    private final VoiceSettings.Data data = VoiceSettings.get();
    private Tab tab = Tab.AUDIO;

    public VoiceSettingsScreen(@Nullable Screen parent) {
        super(Component.translatable("aifriend.screen.voice_title"), parent);
    }

    @Override
    protected void build() {
        addTabs(TAB_KEYS, tab.ordinal(), index -> {
            tab = Tab.values()[index];
            status = Component.empty();
            rebuildWidgets();
        });
        switch (tab) {
            case AUDIO -> buildAudio();
            case SPEECH -> buildSpeech();
            case RECOGNITION -> buildRecognition();
        }

        Component done = Component.translatable("aifriend.button.done");
        if (tab == Tab.SPEECH) {
            addBottomButtons(Component.translatable("aifriend.button.test_voice"), this::testSpeech, done, this::onClose);
        } else {
            addBottomButtons(Component.translatable("aifriend.button.test_mic"), this::testRecognition, done, this::onClose);
        }
    }

    // ------------------------------------------------------------------ вкладка «Звук»

    private void buildAudio() {
        addRenderableWidget(CycleButton.builder((VoiceSettings.MicMode m) -> Component.literal(m.label))
                .withValues(VoiceSettings.MicMode.values())
                .withInitialValue(data.micMode())
                .create(left, row(0), WIDTH, 20, Component.translatable("aifriend.field.mic_mode"), (btn, value) -> data.micMode = value.name()));

        List<String> devices = new ArrayList<>();
        devices.add("");
        devices.addAll(Mic.devices());
        String current = devices.contains(data.micDevice) ? data.micDevice : "";
        addRenderableWidget(CycleButton.builder((String s) -> s.isEmpty()
                        ? Component.translatable("aifriend.mic.default") : Component.literal(shorten(s, 34)))
                .withValues(devices)
                .withInitialValue(current)
                .create(left, row(1), WIDTH, 20, Component.translatable("aifriend.field.device"), (btn, value) -> data.micDevice = value));

        addSlider(2, (data.vadThreshold - THR_MIN) / (THR_MAX - THR_MIN),
                v -> Component.translatable("aifriend.field.threshold", String.format("%.1f", (THR_MIN + v * (THR_MAX - THR_MIN)) * 100.0)),
                v -> data.vadThreshold = THR_MIN + v * (THR_MAX - THR_MIN));

        addNote(3, Component.translatable("aifriend.field.mic_level"), 0xE0E0E0); // сам индикатор рисуется в render()

        addSlider(4, data.outputVolume,
                v -> Component.translatable("aifriend.field.volume", Math.round(v * 100)),
                v -> data.outputVolume = v);

        addNote(5, Component.translatable("aifriend.note.talk_key", AiFriendClient.TALK.getTranslatedKeyMessage()), 0x808080);
    }

    // ------------------------------------------------------------------ вкладка «Озвучка»

    private void buildSpeech() {
        VoiceSettings.TtsProvider provider = data.ttsProvider();

        addRenderableWidget(CycleButton.builder((VoiceSettings.TtsProvider p) -> Component.literal(p.label))
                .withValues(VoiceSettings.TtsProvider.values())
                .withInitialValue(provider)
                .create(left, row(0), WIDTH - 76, 20, Component.translatable("aifriend.field.provider"), (btn, value) -> {
                    data.ttsProvider = value.name();
                    rebuildWidgets();
                }));
        addRenderableWidget(CycleButton.onOffBuilder(data.ttsEnabled).displayOnlyValue()
                .create(left + WIDTH - 72, row(0), 72, 20, Component.translatable("aifriend.field.enabled"), (btn, value) -> data.ttsEnabled = value));

        if (provider == VoiceSettings.TtsProvider.CUSTOM) {
            addRenderableWidget(CycleButton.builder((VoiceSettings.ApiStyle s) -> Component.literal(s.label))
                    .withValues(VoiceSettings.ApiStyle.values())
                    .withInitialValue(data.ttsCustomStyle())
                    .create(left, row(1), WIDTH, 20, Component.translatable("aifriend.field.format"), (btn, value) -> data.ttsCustomStyle = value.name()));
            addField(2, "aifriend.field.base_url", data.ttsCustomUrl, 256, false, v -> data.ttsCustomUrl = v, "http://localhost:8880/v1");
            addField(3, "aifriend.field.api_key", data.ttsCustomKey, 512, true, v -> data.ttsCustomKey = v, "");
            addField(4, "aifriend.field.voice_id", data.ttsCustomVoice, 128, false, v -> data.ttsCustomVoice = v, "alloy / voice id");
            addField(5, "aifriend.field.model", data.ttsCustomModel, 128, false, v -> data.ttsCustomModel = v, "tts-1");
        } else {
            VoiceSettings.Profile pr = data.ttsProfile(provider);
            addField(1, "aifriend.field.api_key", pr.apiKey, 512, true, v -> pr.apiKey = v, "");
            addField(2, "aifriend.field.voice_id", pr.voice, 128, false, v -> pr.voice = v,
                    provider.defaultVoice.isEmpty() ? "optional (reference_id)" : provider.defaultVoice);
            addField(3, "aifriend.field.model", pr.model, 128, false, v -> pr.model = v, provider.defaultModel);
            addNote(4, Component.literal("Endpoint: " + provider.baseUrl), 0x808080);
        }
    }

    // ------------------------------------------------------------------ вкладка «Распознавание»

    private void buildRecognition() {
        VoiceSettings.SttProvider provider = data.sttProvider();

        addRenderableWidget(CycleButton.builder((VoiceSettings.SttProvider p) -> Component.literal(p.label))
                .withValues(VoiceSettings.SttProvider.values())
                .withInitialValue(provider)
                .create(left, row(0), WIDTH, 20, Component.translatable("aifriend.field.provider"), (btn, value) -> {
                    data.sttProvider = value.name();
                    rebuildWidgets();
                }));
        addField(1, "aifriend.field.language", data.sttLanguage, 16, false, v -> data.sttLanguage = v.trim(), "auto (ru, en, ...)");

        if (provider == VoiceSettings.SttProvider.CUSTOM) {
            addRenderableWidget(CycleButton.builder((VoiceSettings.ApiStyle s) -> Component.literal(s.label))
                    .withValues(VoiceSettings.ApiStyle.OPENAI, VoiceSettings.ApiStyle.ELEVENLABS)
                    .withInitialValue(data.sttCustomStyle())
                    .create(left, row(2), WIDTH, 20, Component.translatable("aifriend.field.format"), (btn, value) -> data.sttCustomStyle = value.name()));
            addField(3, "aifriend.field.base_url", data.sttCustomUrl, 256, false, v -> data.sttCustomUrl = v, "http://localhost:8000/v1");
            addField(4, "aifriend.field.api_key", data.sttCustomKey, 512, true, v -> data.sttCustomKey = v, "");
            addField(5, "aifriend.field.model", data.sttCustomModel, 128, false, v -> data.sttCustomModel = v, "whisper-1");
        } else {
            VoiceSettings.Profile pr = data.sttProfile(provider);
            addField(2, "aifriend.field.api_key", pr.apiKey, 512, true, v -> pr.apiKey = v, "");
            addField(3, "aifriend.field.model", pr.model, 128, false, v -> pr.model = v, provider.defaultModel);
            addNote(4, Component.literal("Endpoint: " + provider.baseUrl), 0x808080);
        }
    }

    // ------------------------------------------------------------------ тесты

    private void testSpeech() {
        status = Component.translatable("aifriend.status.testing", VoiceSettings.resolveTts().label()).withStyle(ChatFormatting.GRAY);
        VoiceManager.testSpeech(c -> status = c.copy().withStyle(ChatFormatting.WHITE));
    }

    private void testRecognition() {
        VoiceManager.testRecognition(c -> status = c.copy().withStyle(ChatFormatting.WHITE));
    }

    // ------------------------------------------------------------------ отрисовка

    @Override
    public void tick() {
        super.tick();
        VoiceManager.requestMeter(); // включает микрофон, пока открыт этот экран
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (tab == Tab.AUDIO) {
            int x = left + LABEL_W;
            int w = WIDTH - LABEL_W;
            int y = row(3) + 3;
            graphics.fill(x - 1, y - 1, x + w + 1, y + 13, 0xFF000000);
            graphics.fill(x, y, x + w, y + 12, 0xFF2A2A2A);
            double frac = Math.min(1.0, VoiceManager.level() / VoiceManager.METER_MAX);
            boolean over = data.vadThreshold < VoiceManager.level();
            graphics.fill(x, y, x + (int) (w * frac), y + 12, over ? 0xFF55CC55 : 0xFF3A8A3A);
            int marker = x + (int) (w * Math.min(1.0, data.vadThreshold / VoiceManager.METER_MAX));
            graphics.fill(marker, y - 2, marker + 2, y + 14, 0xFFFFD040);
        }
    }

    @Override
    protected Component activeLine() {
        VoiceSettings.TtsResolved tts = VoiceSettings.resolveTts();
        VoiceSettings.SttResolved stt = VoiceSettings.resolveStt();
        boolean ok = tts.usable() && stt.usable();
        return Component.translatable("aifriend.status.voice_active", tts.label(), stt.label())
                .withStyle(ok ? ChatFormatting.GRAY : ChatFormatting.GOLD);
    }

    @Override
    protected void save() {
        VoiceSettings.save();
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
