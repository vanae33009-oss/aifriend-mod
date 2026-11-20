package com.aifriend.client;

import org.lwjgl.glfw.GLFW;

import com.aifriend.AiFriendMod;
import com.aifriend.ModEntities;
import com.aifriend.voice.VoiceManager;
import com.aifriend.voice.VoiceSettings;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// Этот класс не загружается на выделенных серверах - здесь можно безопасно трогать клиентский код.
@Mod(value = AiFriendMod.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = AiFriendMod.MODID, value = Dist.CLIENT)
public class AiFriendClient {
    public static final KeyMapping OPEN_SETTINGS = new KeyMapping("key.aifriend.settings",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.aifriend");
    public static final KeyMapping TALK = new KeyMapping("key.aifriend.talk",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, "key.categories.aifriend");

    public AiFriendClient(ModContainer container) {
        VoiceSettings.load();
        // Кнопка "Config" в списке модов открывает наш экран настроек.
        container.registerExtensionPoint(IConfigScreenFactory.class, (modContainer, parent) -> new AiSettingsScreen(parent));
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.FRIEND.get(), FriendRenderer::new);
    }

    @SubscribeEvent
    static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_SETTINGS);
        event.register(TALK);
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (OPEN_SETTINGS.consumeClick()) {
            if (mc.screen == null) {
                mc.setScreen(new AiSettingsScreen(null));
            }
        }
        VoiceManager.tick(mc, TALK.isDown() && mc.screen == null);
    }
}
