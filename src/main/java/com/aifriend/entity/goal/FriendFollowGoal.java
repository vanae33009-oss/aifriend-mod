package com.aifriend.entity.goal;

import java.util.EnumSet;

import com.aifriend.entity.FriendEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/** Следует за владельцем (задачи FOLLOW и GUARD). Если сильно отстал - телепортируется, как волк. */
public class FriendFollowGoal extends Goal {
    private static final double TELEPORT_DIST_SQR = 24.0 * 24.0;

    private final FriendEntity friend;
    private final double speed;
    private final float startDist;
    private final float stopDist;
    private int recalc;

    public FriendFollowGoal(FriendEntity friend, double speed, float startDist, float stopDist) {
        this.friend = friend;
        this.speed = speed;
        this.startDist = startDist;
        this.stopDist = stopDist;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private boolean active() {
        return friend.getTask() == FriendEntity.Task.FOLLOW || friend.getTask() == FriendEntity.Task.GUARD;
    }

    @Override
    public boolean canUse() {
        Player owner = friend.getOwner();
        return active() && owner != null && owner.isAlive() && !owner.isSpectator()
                && friend.distanceToSqr(owner) > (double) (startDist * startDist);
    }

    @Override
    public boolean canContinueToUse() {
        Player owner = friend.getOwner();
        return active() && owner != null && owner.isAlive() && !friend.getNavigation().isDone()
                && friend.distanceToSqr(owner) > (double) (stopDist * stopDist);
    }

    @Override
    public void start() {
        recalc = 0;
    }

    @Override
    public void stop() {
        friend.getNavigation().stop();
    }

    @Override
    public void tick() {
        Player owner = friend.getOwner();
        if (owner == null) {
            return;
        }
        friend.getLookControl().setLookAt(owner, 10.0F, (float) friend.getMaxHeadXRot());
        if (--recalc <= 0) {
            recalc = adjustedTickDelay(10);
            if (friend.isLeashed() || friend.isPassenger()) {
                return;
            }
            if (friend.distanceToSqr(owner) >= TELEPORT_DIST_SQR) {
                teleportNear(owner);
            } else {
                friend.getNavigation().moveTo(owner, speed);
            }
        }
    }

    private void teleportNear(Player owner) {
        BlockPos base = owner.blockPosition();
        for (int i = 0; i < 12; i++) {
            int dx = friend.getRandom().nextInt(7) - 3;
            int dz = friend.getRandom().nextInt(7) - 3;
            if (Math.abs(dx) < 2 && Math.abs(dz) < 2) {
                continue;
            }
            BlockPos p = base.offset(dx, 0, dz);
            for (int dy = 2; dy >= -2; dy--) {
                BlockPos q = p.above(dy);
                boolean solidBelow = !friend.level().getBlockState(q.below()).isAir();
                AABB box = friend.getBoundingBox().move(q.getX() + 0.5 - friend.getX(), q.getY() - friend.getY(), q.getZ() + 0.5 - friend.getZ());
                if (solidBelow && friend.level().noCollision(friend, box)) {
                    friend.moveTo(q.getX() + 0.5, q.getY(), q.getZ() + 0.5, friend.getYRot(), friend.getXRot());
                    friend.getNavigation().stop();
                    return;
                }
            }
        }
    }
}
