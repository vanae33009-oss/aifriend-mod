package com.aifriend;

import java.util.List;
import java.util.Locale;

import com.aifriend.ai.AiSettings;
import com.aifriend.entity.FriendEntity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Единая точка входа "игрок что-то сказал боту". Сейчас её вызывает обычный чат;
 * в следующем этапе сюда же придёт распознанная голосовая речь (speech-to-text).
 */
public final class FriendChat {
    private FriendChat() {
    }

    /** Должно вызываться на серверном потоке. */
    public static void route(ServerPlayer speaker, String text) {
        if (text.isBlank()) {
            return;
        }
        ServerLevel level = speaker.serverLevel();
        double radius = Math.max(4, AiSettings.get().hearingRadius);
        List<FriendEntity> hearing = level.getEntitiesOfClass(FriendEntity.class, speaker.getBoundingBox().inflate(radius),
                b -> b.isAlive() && b.distanceToSqr(speaker) <= radius * radius);
        if (hearing.isEmpty()) {
            return;
        }

        String lower = text.toLowerCase(Locale.ROOT);
        FriendEntity chosen = null;
        for (FriendEntity b : hearing) {
            String name = b.getName().getString().toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && lower.contains(name)) {
                chosen = b;
                break;
            }
        }
        if (chosen == null) {
            chosen = hearing.stream().min((x, y) -> Double.compare(x.distanceToSqr(speaker), y.distanceToSqr(speaker))).orElse(null);
        }
        if (chosen != null) {
            chosen.getMind().onPlayerMessage(speaker, text);
        }
    }
}
