package com.aifriend.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

import javax.annotation.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** Общий каркас экранов настроек: вкладки, поля ввода (в том числе скрытые для ключей), ползунки, нижние кнопки. */
abstract class BaseSettingsScreen extends Screen {
    protected record Label(Component text, int x, int y, int color) {
    }

    protected static final int WIDTH = 320;
    protected static final int LABEL_W = 96;
    protected static final int ROW_START = 54;
    protected static final int ROW_STEP = 22;

    protected final Screen parent;
    protected final List<Label> labels = new ArrayList<>();
    protected volatile Component status = Component.empty();
    protected int left;

    protected BaseSettingsScreen(Component title, @Nullable Screen parent) {
        super(title);
        this.parent = parent;
    }

    /** Строит содержимое выбранной вкладки (виджеты и подписи). */
    protected abstract void build();

    /** Строка «что сейчас активно» над строкой статуса; null - не показывать. */
    @Nullable
    protected abstract Component activeLine();

    /** Сохранить настройки на диск (вызывается при закрытии). */
    protected abstract void save();

    @Override
    protected void init() {
        labels.clear();
        left = (this.width - WIDTH) / 2;
        build();
    }

    protected static int row(int index) {
        return ROW_START + ROW_STEP * index;
    }

    protected void addTabs(String[] keys, int active, IntConsumer onSelect) {
        int w = WIDTH / keys.length;
        for (int i = 0; i < keys.length; i++) {
            final int index = i;
            Button b = Button.builder(Component.translatable(keys[i]), btn -> onSelect.accept(index))
                    .bounds(left + i * w, 28, w - 2, 20).build();
            b.active = i != active;
            addRenderableWidget(b);
        }
    }

    protected void addBottomButtons(@Nullable Component leftText, @Nullable Runnable leftAction, Component rightText, Runnable rightAction) {
        int by = this.height - 28;
        if (leftText != null && leftAction != null) {
            int half = WIDTH / 2 - 2;
            addRenderableWidget(Button.builder(leftText, b -> leftAction.run()).bounds(left, by, half, 20).build());
            addRenderableWidget(Button.builder(rightText, b -> rightAction.run()).bounds(left + half + 4, by, half, 20).build());
        } else {
            addRenderableWidget(Button.builder(rightText, b -> rightAction.run()).bounds(left, by, WIDTH, 20).build());
        }
    }

    protected EditBox addField(int rowIndex, String labelKey, String value, int maxLen, boolean secret,
            Consumer<String> onChange, String hint) {
        int y = row(rowIndex);
        labels.add(new Label(Component.translatable(labelKey), left, y + 6, 0xE0E0E0));
        EditBox box = new EditBox(this.font, left + LABEL_W, y, WIDTH - LABEL_W, 20, Component.translatable(labelKey));
        box.setMaxLength(maxLen);
        if (secret) {
            box.setFormatter((text, offset) -> FormattedCharSequence.forward("•".repeat(text.length()), Style.EMPTY));
        }
        box.setValue(value == null ? "" : value);
        if (!hint.isEmpty()) {
            box.setHint(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY));
        }
        box.setResponder(onChange);
        addRenderableWidget(box);
        return box;
    }

    /** Ползунок на всю ширину; значение 0..1, текст формирует text. */
    protected void addSlider(int rowIndex, double initial, Function<Double, Component> text, Consumer<Double> onChange) {
        double start = Math.max(0.0, Math.min(1.0, initial));
        addRenderableWidget(new AbstractSliderButton(left, row(rowIndex), WIDTH, 20, Component.empty(), start) {
            {
                updateMessage();
            }

            @Override
            protected void updateMessage() {
                setMessage(text.apply(this.value));
            }

            @Override
            protected void applyValue() {
                onChange.accept(this.value);
            }
        });
    }

    protected void addNote(int rowIndex, Component text, int color) {
        labels.add(new Label(text, left, row(rowIndex) + 6, color));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick); // фон и виджеты
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);
        for (Label l : labels) {
            graphics.drawString(this.font, l.text(), l.x(), l.y(), l.color());
        }
        Component active = activeLine();
        if (active != null) {
            graphics.drawCenteredString(this.font, active, this.width / 2, this.height - 56, 0xA0A0A0);
        }
        graphics.drawCenteredString(this.font, status, this.width / 2, this.height - 42, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        save();
        this.minecraft.setScreen(parent);
    }
}
