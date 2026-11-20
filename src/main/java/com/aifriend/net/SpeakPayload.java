package com.aifriend.net;

import com.aifriend.AiFriendMod;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Сервер -> клиент: бот сказал реплику. Клиент сам решает, озвучивать ли её (его ключи, его настройки). */
public record SpeakPayload(int entityId, String text, int radius) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SpeakPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AiFriendMod.MODID, "speak"));

    public static final StreamCodec<ByteBuf, SpeakPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SpeakPayload::entityId,
            ByteBufCodecs.stringUtf8(500), SpeakPayload::text,
            ByteBufCodecs.VAR_INT, SpeakPayload::radius,
            SpeakPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
