package com.aifriend.entity;

import javax.annotation.Nullable;

import com.aifriend.ai.AiSettings;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Социальное поведение бота: знакомство при появлении, приветствие, когда игрок вернулся, и
 * реплика по собственной инициативе, если долго молчат. Сам ничего не говорит: он только сообщает «мозгу» событие,
 * а что именно сказать, решает ИИ (с учётом характера, настроения и того, что бот знает об игроке).
 */
final class FriendSocial {
    /** Столько игрока нет рядом, чтобы считать, что он уходил. */
    static final long AWAY_MS = 120_000;
    /** Дальше этого расстояния игрок «ушёл» (в блоках). */
    private static final double NEAR = 40.0;
    private static final double INTRO_NEAR = 24.0;
    private static final long QUIET_BASE_MS = 4 * 60_000L;
    private static final long QUIET_MAX_MS = 25 * 60_000L;

    private boolean introduced;
    /** Когда игрок в последний раз был рядом (мс, время системы). 0 = неизвестно. */
    private long lastNearMs;
    /** Когда в последний раз шёл разговор (любая реплика игрока или бота). */
    private long lastChatMs = System.currentTimeMillis();
    /** Сколько раз подряд бот заговаривал сам без ответа: интервал растёт, чтобы не надоедать. */
    private int unansweredNudges;
    private long nextNudgeJitterMs;

    void onPlayerSpoke() {
        lastChatMs = System.currentTimeMillis();
        unansweredNudges = 0;
    }

    void onFriendSpoke() {
        lastChatMs = System.currentTimeMillis();
    }

    long quietMs() {
        return System.currentTimeMillis() - lastChatMs;
    }

    /** Раз в секунду. */
    void tick(FriendEntity friend, FriendMind mind) {
        AiSettings.Data d = AiSettings.get();
        if (!d.proactiveChat || !AiSettings.isConfigured() || mind.isBusy()) {
            return;
        }
        long now = System.currentTimeMillis();
        Player owner = friend.getOwner();
        boolean near = owner != null && owner.level() == friend.level() && friend.distanceTo(owner) <= NEAR;

        if (!near) {
            return; // lastNearMs остаётся временем, когда игрок был рядом в последний раз
        }

        if (!introduced) {
            if (friend.distanceTo(owner) > INTRO_NEAR) {
                return;
            }
            introduced = true;
            lastNearMs = now;
            lastChatMs = now;
            mind.onImportantEvent("you have just joined " + owner.getGameProfile().getName()
                    + "'s world and are meeting them for the first time. Introduce yourself by your name in a friendly way "
                    + "and ask what they would like to start with (like \"so, where do we start?\"). One or two short sentences.");
            return;
        }

        long away = lastNearMs == 0 ? 0 : now - lastNearMs;
        lastNearMs = now;
        if (away >= AWAY_MS) {
            lastChatMs = now;
            unansweredNudges = 0;
            long minutes = Math.max(1, away / 60_000);
            mind.onImportantEvent(owner.getGameProfile().getName() + " is back near you after being away for about " + minutes
                    + (minutes == 1 ? " minute" : " minutes") + (minutes >= 10 ? " (they probably left the game or went far away)" : "")
                    + ". Greet them like a friend would, briefly, and maybe say what you were up to. Don't ask many questions.");
            return;
        }

        // Долгая тишина: бот сам заговаривает, но всё реже, если ему не отвечают.
        if (unansweredNudges < 4) {
            long interval = Math.min(QUIET_MAX_MS, QUIET_BASE_MS << Math.min(unansweredNudges, 3));
            if (nextNudgeJitterMs == 0) {
                nextNudgeJitterMs = (long) (friend.getRandom().nextDouble() * 60_000L);
            }
            if (now - lastChatMs >= interval + nextNudgeJitterMs) {
                lastChatMs = now;
                nextNudgeJitterMs = 0;
                unansweredNudges++;
                long minutes = Math.max(1, (interval) / 60_000);
                mind.onImportantEvent("nobody has said anything for a few minutes (about " + minutes + "). You may start a small, natural conversation: "
                        + "ask " + owner.getGameProfile().getName() + " something (how they are, what they want to do next, an opinion about what is "
                        + "going on, something about themselves that you don't know yet) or share a short thought about what is around you. "
                        + "One short line, nothing forced. If you just asked something earlier and got no answer, don't repeat it, say something different "
                        + "or stay quiet (empty say).");
            }
        }
    }

    /** Игрок погиб: бот это видит и реагирует. */
    static String deathEvent(ServerPlayer player, String cause) {
        return player.getGameProfile().getName() + " just died (" + cause + "). React like a friend would: short, human, maybe offer to help get their things back.";
    }

    void save(CompoundTag tag) {
        tag.putBoolean("FriendIntroduced", introduced);
        tag.putLong("FriendLastNear", lastNearMs);
        tag.putInt("FriendNudges", unansweredNudges);
    }

    void load(CompoundTag tag) {
        introduced = tag.getBoolean("FriendIntroduced");
        lastNearMs = tag.getLong("FriendLastNear");
        unansweredNudges = tag.getInt("FriendNudges");
        lastChatMs = System.currentTimeMillis();
    }

    @Nullable
    static String languageOf(Player p) {
        if (p instanceof ServerPlayer sp) {
            try {
                return sp.clientInformation().language();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
