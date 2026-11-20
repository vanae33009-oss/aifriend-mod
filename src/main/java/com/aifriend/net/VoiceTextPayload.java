package com.aifriend.net;

import com.aifriend.AiFriendMod;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Клиент -> сервер: распознанная речь игрока (распознавание происходит у игрока, ключи не покидают его компьютер). */
public record VoiceTextPayload(String text) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<VoiceTextPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AiFriendMod.MODID, "voice_text"));

    public static final StreamCodec<ByteBuf, VoiceTextPayload> CODEC =
            ByteBufCodecs.stringUtf8(500).map(VoiceTextPayload::new, VoiceTextPayload::text);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
