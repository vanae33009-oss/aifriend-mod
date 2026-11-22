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

    public static final KeyMapping PLAYER = new KeyMapping("key.aifriend.player",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.aifriend");

    public static final KeyMapping MUSIC_PREV = new KeyMapping("key.aifriend.music_prev",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_COMMA, "key.categories.aifriend");
    public static final KeyMapping MUSIC_NEXT = new KeyMapping("key.aifriend.music_next",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_PERIOD, "key.categories.aifriend");
    public static final KeyMapping MUSIC_STOP = new KeyMapping("key.aifriend.music_stop",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_SEMICOLON, "key.categories.aifriend");
    public static final KeyMapping MUSIC_LOOP = new KeyMapping("key.aifriend.music_loop",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_APOSTROPHE, "key.categories.aifriend");

    public AiFriendClient(ModContainer container) {
        VoiceSettings.load();
        com.aifriend.MusicCatalog.ensureDir();
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
        event.register(PLAYER);
        event.register(MUSIC_PREV);
        event.register(MUSIC_NEXT);
        event.register(MUSIC_STOP);
        event.register(MUSIC_LOOP);
    }

    @SubscribeEvent
    static void onRegisterGui(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) {
        event.registerAboveAll(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(AiFriendMod.MODID, "music_player"), MusicPlayer::render);
    }

    /** Клик по кнопкам плеера, когда открыт чат или меню (мышь свободна). */
    @SubscribeEvent
    static void onScreenClick(net.neoforged.neoforge.client.event.ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() == 0 && MusicPlayer.click(event.getMouseX(), event.getMouseY())) {
            event.setCanceled(true);
        }
    }

    /** /aimusic <ссылка или название | stop>: включить музыку самому, то же, что просит бот. */
    @SubscribeEvent
    static void onClientCommands(net.neoforged.neoforge.client.event.RegisterClientCommandsEvent event) {
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("aiwelcome").executes(ctx -> {
            Minecraft.getInstance().tell(() -> Minecraft.getInstance().setScreen(new WelcomeScreen()));
            return 1;
        }));
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("aimusic")
                .then(net.minecraft.commands.Commands.argument("query", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                        .executes(ctx -> {
                            MusicLinks.handle(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "query"));
                            return 1;
                        })));
    }

    @SubscribeEvent
    static void onWorldJoin(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) {
        WelcomeScreen.onWorldJoin();
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        WelcomeScreen.tick(mc);
        while (OPEN_SETTINGS.consumeClick()) {
            if (mc.screen == null) {
                mc.setScreen(new AiSettingsScreen(null));
            }
        }
        while (PLAYER.consumeClick()) {
            MusicPlayer.toggleSpin();
        }
        while (MUSIC_PREV.consumeClick()) {
            MusicPlayer.command("prev");
        }
        while (MUSIC_NEXT.consumeClick()) {
            MusicPlayer.command("next");
        }
        while (MUSIC_STOP.consumeClick()) {
            MusicPlayer.command("stop");
        }
        while (MUSIC_LOOP.consumeClick()) {
            MusicPlayer.command("loop");
        }
        VoiceManager.tick(mc, TALK.isDown() && mc.screen == null);
    }
}
