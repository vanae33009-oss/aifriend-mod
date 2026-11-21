package com.aifriend.entity.goal;

import java.util.EnumSet;
import java.util.Map;

import javax.annotation.Nullable;

import com.aifriend.entity.FriendEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.Vec3;

/**
 * Разведка: бот идёт в выбранном направлении отрезками по ~14 блоков и по пути замечает биомы и постройки
 * (деревни, форпосты, шахты...), но только те, в границах которых он реально оказался. Находки запоминаются.
 * Дальше ~80 блоков от точки старта не уходит: вне загруженной вокруг игрока области сущности «замирают».
 */
public class FriendExploreGoal extends Goal {
    private static final int STEP = 14;
    private static final double MAX_FROM_ORIGIN = 80.0;

    private final FriendEntity friend;
    @Nullable
    private BlockPos waypoint;
    private Vec3 lastPos = Vec3.ZERO;
    private double traveled;
    private int blocked;
    private int scanCooldown;
    private int stuckTicks;
    private String lastBiome = "";
    private boolean nightWarned;

    public FriendExploreGoal(FriendEntity friend) {
        this.friend = friend;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return friend.getTask() == FriendEntity.Task.EXPLORE && friend.job().remaining > 0;
    }

    @Override
    public boolean canContinueToUse() {
        return friend.getTask() == FriendEntity.Task.EXPLORE;
    }

    @Override
    public void start() {
        lastPos = friend.position();
        waypoint = null;
        stuckTicks = 0;
    }

    @Override
    public void stop() {
        friend.getNavigation().stop();
        if (friend.getTask() != FriendEntity.Task.EXPLORE) {
            traveled = 0;
            blocked = 0;
            lastBiome = "";
            nightWarned = false;
        }
    }

    @Override
    public void tick() {
        FriendEntity.Job job = friend.job();
        if (!(friend.level() instanceof ServerLevel level)) {
            return;
        }

        // пройденное расстояние по горизонтали
        Vec3 pos = friend.position();
        traveled += Math.hypot(pos.x - lastPos.x, pos.z - lastPos.z);
        lastPos = pos;
        job.remaining = Math.max(0, job.total - (int) traveled);

        if (--scanCooldown <= 0) {
            scanCooldown = 20;
            scan(level);
        }

        if (job.remaining <= 0) {
            finish("finished scouting (about " + job.total + " blocks walked)");
            return;
        }

        boolean arrived = waypoint != null && friend.distanceToSqr(Vec3.atBottomCenterOf(waypoint)) < 3.0 * 3.0;
        if (waypoint == null || arrived || friend.getNavigation().isDone()) {
            if (waypoint != null && !arrived && ++stuckTicks > 3) {
                stuckTicks = 0;
                turn(55);
                blocked++;
            }
            nextLeg(level);
        }
    }

    // ------------------------------------------------------------------ движение

    private void nextLeg(ServerLevel level) {
        FriendEntity.Job job = friend.job();
        if (blocked >= 6) {
            finish("terrain keeps blocking the way (water, cliffs or walls)");
            return;
        }
        double len = Math.hypot(job.hx, job.hz);
        if (len < 1.0E-4) {
            job.hx = 1;
            job.hz = 0;
            len = 1;
        }
        double jitter = Math.toRadians((friend.getRandom().nextDouble() - 0.5) * 40.0);
        double c = Math.cos(jitter);
        double s = Math.sin(jitter);
        double dx = (job.hx * c - job.hz * s) / len;
        double dz = (job.hx * s + job.hz * c) / len;

        int tx = (int) Math.floor(friend.getX() + dx * STEP);
        int tz = (int) Math.floor(friend.getZ() + dz * STEP);
        if (Math.hypot(tx - job.originX, tz - job.originZ) > MAX_FROM_ORIGIN) {
            finish("reached the farthest point I can scout from you (~" + (int) MAX_FROM_ORIGIN + " blocks)");
            return;
        }
        BlockPos column = new BlockPos(tx, 0, tz);
        if (!level.hasChunkAt(column) || !level.isPositionEntityTicking(column)) {
            finish("the area ahead is not loaded - I'd lose you if I went further");
            return;
        }
        BlockPos target = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
        if (friend.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0)) {
            waypoint = target;
            blocked = 0;
        } else {
            turn(50);
            blocked++;
            waypoint = null;
        }
    }

    private void turn(double degrees) {
        FriendEntity.Job job = friend.job();
        double a = Math.toRadians(friend.getRandom().nextBoolean() ? degrees : -degrees);
        double nx = job.hx * Math.cos(a) - job.hz * Math.sin(a);
        double nz = job.hx * Math.sin(a) + job.hz * Math.cos(a);
        job.hx = nx;
        job.hz = nz;
    }

    // ------------------------------------------------------------------ наблюдения

    private void scan(ServerLevel level) {
        BlockPos here = friend.blockPosition();

        String biome = level.getBiome(here).unwrapKey().map(k -> k.location().getPath()).orElse("");
        if (!biome.isEmpty() && !biome.equals(lastBiome)) {
            if (!lastBiome.isEmpty()) {
                friend.getMind().onEvent("walked into a different area: " + biome.replace('_', ' '));
            }
            lastBiome = biome;
        }

        if (!nightWarned && level.isNight()) {
            nightWarned = true;
            friend.getMind().onEvent("it is getting dark while you are scouting");
        }

        int[][] offsets = { { 0, 0 }, { 8, 0 }, { -8, 0 }, { 0, 8 }, { 0, -8 } };
        for (int[] off : offsets) {
            BlockPos p = here.offset(off[0], 0, off[1]);
            if (!level.hasChunkAt(p)) {
                continue;
            }
            Map<Structure, ?> found = level.structureManager().getAllStructuresAt(p);
            for (Structure st : found.keySet()) {
                var key = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(st);
                if (key == null) {
                    continue;
                }
                String id = key.getPath();
                if (friend.knowsPlace(id)) {
                    continue;
                }
                String where = p.getX() + " " + p.getY() + " " + p.getZ();
                friend.addKnownPlace(id + " at " + where);
                friend.job().found.add(id);
                friend.getMind().onImportantEvent("found a " + id.replace('_', ' ') + " at " + where);
            }
        }
    }

    private void finish(String text) {
        FriendEntity.Job job = friend.job();
        String found = job.found.isEmpty() ? "nothing special found" : "found: " + String.join(", ", job.found);
        friend.endJob(true, text + "; " + found);
    }
}
