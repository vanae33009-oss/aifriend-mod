package com.aifriend.net;

import com.aifriend.AiFriendMod;
import com.aifriend.FriendChat;
import com.aifriend.voice.VoiceManager;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = AiFriendMod.MODID)
public final class ModNetwork {
    private ModNetwork() {
    }

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(VoiceTextPayload.TYPE, VoiceTextPayload.CODEC, ModNetwork::onVoiceText);
        registrar.playToClient(SpeakPayload.TYPE, SpeakPayload.CODEC, ModNetwork::onSpeak);
    }

    private static void onVoiceText(VoiceTextPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                String text = payload.text().trim();
                if (text.isEmpty()) {
                    return;
                }
                // Показываем игроку, что именно распознано (удобно ловить ошибки распознавания).
                player.sendSystemMessage(Component.literal("[mic] " + text).withStyle(ChatFormatting.GRAY));
                FriendChat.route(player, text);
            }
        });
    }

    private static void onSpeak(SpeakPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                VoiceManager.onFriendSpeech(payload.entityId(), payload.text(), payload.radius());
            }
        });
    }
}
