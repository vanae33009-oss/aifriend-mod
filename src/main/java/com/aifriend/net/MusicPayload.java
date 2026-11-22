package com.aifriend.net;

import com.aifriend.AiFriendMod;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Сервер -> клиент: бот хочет включить музыку. query - ссылка либо поисковая фраза ("spotify: Daft Punk").
 * Клиент сам решает, что открыть, и всегда спрашивает подтверждение у игрока.
 */
public record MusicPayload(String query) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<MusicPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AiFriendMod.MODID, "music"));

    public static final StreamCodec<ByteBuf, MusicPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(300), MusicPayload::query,
            MusicPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
