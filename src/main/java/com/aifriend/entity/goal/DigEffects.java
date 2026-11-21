package com.aifriend.entity.goal;

import javax.annotation.Nullable;

import com.aifriend.entity.FriendEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;

/** То, что видит игрок, когда бот копает: взмах руки, трещины на блоке и звук ударов, как у настоящего игрока. */
final class DigEffects {
    @Nullable
    private BlockPos pos;
    private int stage = -1;

    /** Один тик копания (progress от 1 до needed). */
    void tick(FriendEntity friend, BlockPos target, BlockState state, int progress, int needed) {
        if (!(friend.level() instanceof ServerLevel level)) {
            return;
        }
        if (pos != null && !pos.equals(target)) {
            clear(friend);
        }
        pos = target;
        if (progress % 4 == 1) {
            friend.swing(InteractionHand.MAIN_HAND);
        }
        if (progress % 5 == 2) {
            level.playSound(null, target, state.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.6F, 0.8F);
        }
        int s = Math.min(9, progress * 10 / Math.max(1, needed));
        if (s != stage) {
            stage = s;
            level.destroyBlockProgress(friend.getId(), target, s);
        }
    }

    /** Убирает трещины (блок сломан или бот отвлёкся). */
    void clear(FriendEntity friend) {
        if (pos != null && friend.level() instanceof ServerLevel level) {
            level.destroyBlockProgress(friend.getId(), pos, -1);
        }
        pos = null;
        stage = -1;
    }
}
