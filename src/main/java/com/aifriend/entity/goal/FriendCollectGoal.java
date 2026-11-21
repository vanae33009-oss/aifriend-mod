package com.aifriend.entity.goal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.aifriend.ai.AiSettings;
import com.aifriend.entity.FriendEntity;
import com.aifriend.entity.Natural;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Добыча блоков для задачи COLLECT: найти ближайший подходящий блок, дойти, "копать" положенное время
 * (зависит от твёрдости и инструмента), выбросить дроп (только если инструмент подходит) и подобрать его.
 * Если рядом ничего нет, бот сам прогуливается по округе (до {@link #MAX_SEARCH_LEGS} отрезков по ~14 блоков)
 * и ищет снова, и только потом сдаётся. Сквозь стены не копает: только то, до чего может дойти.
 */
public class FriendCollectGoal extends Goal {
    private static final double REACH_SQR = 3.5 * 3.5;
    private static final int MAX_SEARCH_LEGS = 5;
    private static final int SEARCH_STEP = 14;
    private static final int MAX_CANDIDATES = 24;
    /** Дальний обзор: бот видит деревья и жилы вокруг на десятки блоков (но сквозь землю не видит). */
    private static final int WIDE_R = 48;
    private static final int WIDE_VY = 24;
    private static final int MAX_VEIN_BONUS = 40;

    private final FriendEntity friend;
    private final Map<Long, Integer> blacklist = new HashMap<>();
    private final DigEffects fx = new DigEffects();

    @Nullable
    private BlockPos target;
    @Nullable
    private BlockPos lootPos;
    @Nullable
    private BlockPos searchTarget;
    private boolean searchStarted;
    private int searchTicks;
    private int searchLegs;
    private int progress;
    private int needed;
    private int walkTicks;
    private int collectWait;
    private int scanCooldown;
    private int failedScans;
    private int wideReadyTick;
    private int walkLimit = 260;
    private int veinBonus;
    private boolean pendingFinish;

    public FriendCollectGoal(FriendEntity friend) {
        this.friend = friend;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (friend.getTask() != FriendEntity.Task.COLLECT || friend.getCollectRemaining() <= 0) {
            searchLegs = 0;
            veinBonus = 0;
            return false;
        }
        if (--scanCooldown > 0) {
            return false;
        }
        scanCooldown = 10;
        BlockPos found = findTarget(14, 8, MAX_CANDIDATES);
        if (found == null && friend.tickCount >= wideReadyTick) {
            found = findTarget(WIDE_R, WIDE_VY, 8); // ближе ничего нет: смотрим дальше
            if (found == null) {
                wideReadyTick = friend.tickCount + 100;
            }
        }
        if (found != null) {
            failedScans = 0;
            searchLegs = 0;
            setTarget(found);
            return true;
        }
        // Рядом ничего нет: идём искать дальше.
        if (searchLegs < MAX_SEARCH_LEGS) {
            BlockPos s = pickSearchTarget();
            if (s != null) {
                searchLegs++;
                searchTarget = s;
                searchStarted = false;
                scanCooldown = 5;
                return true;
            }
        }
        if (++failedScans >= 3) {
            failedScans = 0;
            searchLegs = 0;
            friend.finishCollect(false);
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return friend.getTask() == FriendEntity.Task.COLLECT && (target != null || collectWait > 0 || searchTarget != null);
    }

    @Override
    public void start() {
        progress = 0;
        walkTicks = 0;
        collectWait = 0;
    }

    @Override
    public void stop() {
        if (pendingFinish) {
            pendingFinish = false;
            if (friend.getTask() == FriendEntity.Task.COLLECT) {
                friend.finishCollect(true);
            }
        }
        fx.clear(friend);
        friend.getNavigation().stop();
        target = null;
        lootPos = null;
        searchTarget = null;
        collectWait = 0;
        progress = 0;
        if (friend.getTask() != FriendEntity.Task.COLLECT) {
            searchLegs = 0;
        }
    }

    @Override
    public void tick() {
        Level level = friend.level();

        // Фаза подбора выпавших предметов.
        if (collectWait > 0) {
            collectWait--;
            if (lootPos != null) {
                friend.getNavigation().moveTo(lootPos.getX() + 0.5, lootPos.getY(), lootPos.getZ() + 0.5, 1.0);
            }
            if (collectWait == 0) {
                lootPos = null;
                if (pendingFinish) { // последний блок: сначала подобрали выпавшее, теперь заканчиваем
                    pendingFinish = false;
                    friend.finishCollect(true);
                }
            }
            return;
        }

        // Фаза поиска: идём в сторону, периодически осматриваясь.
        if (target == null) {
            if (searchTarget != null) {
                searchStep();
            }
            return;
        }

        BlockState state = level.getBlockState(target);
        if (state.isAir() || !friend.isCollectTarget(state)) {
            fx.clear(friend);
            target = null; // уже сломан или изменился
            progress = 0;
            return;
        }

        Vec3 center = Vec3.atCenterOf(target);
        friend.getLookControl().setLookAt(center.x, center.y, center.z);

        if (!canMineFromHere(center)) {
            fx.clear(friend);
            progress = 0;
            if (walkTicks++ % 10 == 0) {
                BlockPos stand = findStandPos(target);
                if (stand == null || !friend.getNavigation().moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 1.0)) {
                    blacklist(target);
                    target = null;
                    return;
                }
            }
            if (walkTicks > walkLimit) {
                blacklist(target);
                target = null;
            }
            return;
        }

        // Копаем.
        friend.getNavigation().stop();
        if (progress == 0) {
            friend.equipToolFor(state);
            needed = ticksToBreak(state);
        }
        progress++;
        fx.tick(friend, target, state, progress, needed);
        if (progress >= needed) {
            harvest(state);
        }
    }

    private void setTarget(BlockPos p) {
        target = p;
        walkTicks = 0;
        walkLimit = 260 + (int) (Math.sqrt(friend.distanceToSqr(Vec3.atCenterOf(p))) * 14.0);
    }

    // ------------------------------------------------------------------ поиск по округе

    private void searchStep() {
        BlockPos s = searchTarget;
        if (s == null) {
            return;
        }
        if (!searchStarted) {
            searchStarted = true;
            searchTicks = 0;
            if (!friend.getNavigation().moveTo(s.getX() + 0.5, s.getY(), s.getZ() + 0.5, 1.0)) {
                searchTarget = null; // не пройти в ту сторону - в следующий раз выберем другую
            }
            return;
        }
        searchTicks++;
        if (searchTicks % 20 == 0) {
            BlockPos found = findTarget(14, 8, MAX_CANDIDATES); // по дороге заметили подходящее - переключаемся
            if (found != null) {
                setTarget(found);
                searchTarget = null;
                searchLegs = 0;
                walkTicks = 0;
                return;
            }
        }
        boolean arrived = friend.distanceToSqr(Vec3.atBottomCenterOf(s)) < 2.5 * 2.5;
        if (arrived || (searchTicks > 10 && friend.getNavigation().isDone()) || searchTicks > 240) {
            searchTarget = null;
            friend.getNavigation().stop();
        }
    }

    /** Точка в ~14 блоках в случайную сторону (если ушли далеко от игрока - в его сторону). */
    @Nullable
    private BlockPos pickSearchTarget() {
        if (!(friend.level() instanceof ServerLevel level)) {
            return null;
        }
        double dx;
        double dz;
        Player owner = friend.getOwner();
        if (owner != null && friend.distanceToSqr(owner) > 40.0 * 40.0) {
            dx = owner.getX() - friend.getX();
            dz = owner.getZ() - friend.getZ();
        } else {
            double a = friend.getRandom().nextDouble() * Math.PI * 2.0;
            dx = Math.cos(a);
            dz = Math.sin(a);
        }
        double len = Math.max(1.0E-4, Math.hypot(dx, dz));
        int tx = (int) Math.floor(friend.getX() + dx / len * SEARCH_STEP);
        int tz = (int) Math.floor(friend.getZ() + dz / len * SEARCH_STEP);
        BlockPos column = new BlockPos(tx, 0, tz);
        if (!level.hasChunkAt(column) || !level.isPositionEntityTicking(column)) {
            return null;
        }
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
    }

    // ------------------------------------------------------------------ добыча

    private int ticksToBreak(BlockState state) {
        float hardness = state.getDestroySpeed(friend.level(), target);
        ItemStack tool = friend.getMainHandItem();
        boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        float speed = Math.max(1.0F, tool.getDestroySpeed(state));
        return Math.max(8, (int) Math.ceil(hardness * (correct ? 30.0F : 100.0F) / speed / AiSettings.get().digSpeed));
    }

    private void harvest(BlockState state) {
        if (!(friend.level() instanceof ServerLevel level) || target == null) {
            return;
        }
        fx.clear(friend);
        ItemStack tool = friend.getMainHandItem();
        boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        if (correct) {
            Block.dropResources(state, level, target, level.getBlockEntity(target), friend, tool);
        }
        level.destroyBlock(target, false, friend);
        lootPos = target;
        collectWait = 16;
        target = null;
        progress = 0;
        walkTicks = 0;
        // Жила: настоящий игрок выбирает её всю, а не одну руду. Если это последний блок задачи, но рядом есть такая же руда, продолжаем.
        String ore = oreName(state);
        if (ore != null && friend.getCollectRemaining() <= 1 && veinBonus < MAX_VEIN_BONUS && veinNeighbor(level, lootPos, ore) != null) {
            friend.extendCollect(1);
            veinBonus++;
        }
        if (friend.markCollected() <= 0) {
            pendingFinish = true;
        }
    }

    @Nullable
    private static String oreName(BlockState state) {
        String n = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return n.endsWith("_ore") ? n.replace("deepslate_", "") : null;
    }

    @Nullable
    private BlockPos veinNeighbor(Level level, BlockPos from, String ore) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(from.offset(-2, -2, -2), from.offset(2, 2, 2))) {
            if (p.equals(from) || isBlacklisted(p)) {
                continue;
            }
            BlockState s = level.getBlockState(p);
            if (!ore.equals(oreName(s)) || !friend.canHarvest(s) || !Natural.isNatural(level, p, s)) {
                continue;
            }
            if (!canMineFromHere(Vec3.atCenterOf(p)) && findStandPos(p) == null) {
                continue;
            }
            double d = p.distSqr(from);
            if (d < bd) {
                bd = d;
                best = p.immutable();
            }
        }
        return best;
    }

    private boolean canMineFromHere(Vec3 center) {
        if (friend.getEyePosition().distanceToSqr(center) > REACH_SQR + 1.0) {
            return false;
        }
        BlockHitResult hit = friend.level().clip(new ClipContext(friend.getEyePosition(), center,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, friend));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    // ------------------------------------------------------------------ поиск блока

    @Nullable
    private BlockPos findTarget(int r, int vy, int maxChecks) {
        Level level = friend.level();
        BlockPos origin = friend.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-r, -vy, -r), origin.offset(r, vy, r))) {
            if (!level.hasChunkAt(p) || isBlacklisted(p)) {
                continue;
            }
            BlockState s = level.getBlockState(p);
            if (s.isAir() || !friend.isCollectTarget(s) || level.getBlockEntity(p) != null) {
                continue;
            }
            if (s.getDestroySpeed(level, p) < 0 || !friend.canHarvest(s)) {
                continue;
            }
            candidates.add(p.immutable());
        }
        // Сначала близкие по горизонтали и невысоко (нижние брёвна ствола достать проще всего).
        candidates.sort(Comparator.comparingDouble(p -> Math.hypot(p.getX() - origin.getX(), p.getZ() - origin.getZ())
                + Math.abs(p.getY() - origin.getY()) * 3.0));

        int checked = 0;
        for (BlockPos p : candidates) {
            if (checked >= maxChecks) {
                break;
            }
            if (!Natural.isNatural(level, p, level.getBlockState(p))) {
                blacklist(p); // дом, деревня или постройка игрока: не трогаем
                continue;
            }
            checked++;
            if (isReachable(p)) {
                return p;
            }
            blacklist(p);
        }
        return null;
    }

    private boolean isReachable(BlockPos p) {
        if (canMineFromHere(Vec3.atCenterOf(p))) {
            return true;
        }
        BlockPos stand = findStandPos(p);
        if (stand == null) {
            return false;
        }
        PathNavigation nav = friend.getNavigation();
        Path path = nav.createPath(stand, 1);
        return path != null && path.canReach();
    }

    /**
     * Свободная клетка с твёрдым полом рядом с блоком, откуда можно его копать.
     * Допускается стоять до 3 блоков ниже самого блока (стволы и склоны), главное - чтобы он оказался в досягаемости.
     */
    @Nullable
    private BlockPos findStandPos(BlockPos block) {
        Level level = friend.level();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            for (int dy = 0; dy >= -3; dy--) {
                BlockPos c = block.relative(dir).offset(0, dy, 0);
                boolean free = level.getBlockState(c).getCollisionShape(level, c).isEmpty()
                        && level.getBlockState(c.above()).getCollisionShape(level, c.above()).isEmpty();
                boolean floor = !level.getBlockState(c.below()).getCollisionShape(level, c.below()).isEmpty();
                if (free && floor) {
                    double d = friend.distanceToSqr(Vec3.atBottomCenterOf(c));
                    if (d < bestDist) {
                        bestDist = d;
                        best = c;
                    }
                }
            }
        }
        return best;
    }

    private void blacklist(BlockPos p) {
        blacklist.put(p.asLong(), friend.tickCount + 1200);
    }

    private boolean isBlacklisted(BlockPos p) {
        Integer until = blacklist.get(p.asLong());
        if (until == null) {
            return false;
        }
        if (until <= friend.tickCount) {
            blacklist.remove(p.asLong());
            return false;
        }
        return true;
    }
}
