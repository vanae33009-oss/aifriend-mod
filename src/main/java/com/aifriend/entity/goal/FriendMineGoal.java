package com.aifriend.entity.goal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.aifriend.entity.FriendEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Шахта для задачи MINE: лестница вниз до нужной высоты, потом прямой туннель 1x2. По пути бот берёт открытую руду нужного вида
 * (только ту, что видна: у неё есть соседний воздух, и рядом нет лавы). Блоки он ломает по-настоящему, с временем копания и только
 * подходящей киркой. Если впереди вода, лава, провал или неразрушаемый блок, бот поворачивает в сторону. В конце возвращается
 * назад по своему же следу. Если ему сказали заняться чем-то другим на глубине, он всё равно сначала выйдет наверх.
 */
public class FriendMineGoal extends Goal {
    private enum Phase {
        NONE, DESCEND, TUNNEL, RETURN
    }

    private static final int[][] DIRS = { { 1, 0 }, { 0, 1 }, { -1, 0 }, { 0, -1 } };
    private static final int MAX_TUNNEL = 72;
    private static final int MAX_TURNS = 10;
    private static final int MAX_TICKS = 20 * 60 * 25;
    private static final double REACH = 4.4;

    private final FriendEntity friend;
    private final List<BlockPos> trail = new ArrayList<>();
    private final Map<Long, Integer> blacklist = new HashMap<>();

    private Phase phase = Phase.NONE;
    @Nullable
    private FriendEntity.Job activeJob;
    private int dirIndex;
    private int targetY;
    private int turns;
    private int tunnelLen;
    private int startTick;
    private String stopReason = "";

    // текущее копание
    @Nullable
    private BlockPos breakPos;
    private boolean breakingOre;
    private int progress;
    private int needed;

    // движение
    @Nullable
    private BlockPos moveTo;
    private int moveTicks;
    @Nullable
    private BlockPos orePos;
    private int oreWalkTicks;
    @Nullable
    private BlockPos lootPos;
    private int lootTicks;
    private int scanCooldown;
    private int returnIndex;

    public FriendMineGoal(FriendEntity friend) {
        this.friend = friend;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (friend.getTask() == FriendEntity.Task.MINE) {
            return true;
        }
        // Дали другое поручение, пока бот под землёй: сначала выходим наверх.
        return friend.getTask() == FriendEntity.Task.FOLLOW && !trail.isEmpty()
                && friend.getY() < trail.get(0).getY() - 3;
    }

    @Override
    public boolean canContinueToUse() {
        if (friend.getTask() == FriendEntity.Task.MINE) {
            return true;
        }
        return phase == Phase.RETURN && friend.getTask() == FriendEntity.Task.FOLLOW;
    }

    @Override
    public void start() {
        if (friend.getTask() == FriendEntity.Task.MINE) {
            if (activeJob != friend.job() || phase == Phase.NONE) {
                beginJob();
            }
        } else {
            beginReturn("");
        }
    }

    @Override
    public void stop() {
        friend.getNavigation().stop();
        breakPos = null;
        orePos = null;
        moveTo = null;
        progress = 0;
        if (phase == Phase.RETURN && returnIndex < 0) {
            phase = Phase.NONE;
        }
    }

    private void beginJob() {
        activeJob = friend.job();
        FriendEntity.Job job = activeJob;
        targetY = job.size;
        int hx = (int) Math.round(job.hx);
        int hz = (int) Math.round(job.hz);
        dirIndex = 0;
        for (int i = 0; i < 4; i++) {
            if (DIRS[i][0] == hx && DIRS[i][1] == hz) {
                dirIndex = i;
            }
        }
        turns = 0;
        tunnelLen = 0;
        startTick = friend.tickCount;
        stopReason = "";
        breakPos = null;
        orePos = null;
        moveTo = null;
        if (trail.isEmpty()) {
            trail.add(friend.blockPosition());
        }
        phase = friend.getBlockY() <= targetY + 1 ? Phase.TUNNEL : Phase.DESCEND;
    }

    private void beginReturn(String reason) {
        if (!reason.isEmpty()) {
            stopReason = reason;
        }
        phase = Phase.RETURN;
        breakPos = null;
        orePos = null;
        moveTo = null;
        progress = 0;
        returnIndex = trail.size() - 1;
        moveTicks = 0;
    }

    // ------------------------------------------------------------------ тик

    @Override
    public void tick() {
        Level level = friend.level();
        if (phase == Phase.RETURN) {
            tickReturn();
            return;
        }
        if (phase == Phase.NONE) {
            return;
        }
        if (activeJob != friend.job()) {
            beginJob();
        }
        FriendEntity.Job job = friend.job();
        if (job.remaining <= 0) {
            beginReturn("");
            return;
        }
        if (friend.tickCount - startTick > MAX_TICKS) {
            beginReturn("it took too long");
            return;
        }
        if (turns > MAX_TURNS) {
            beginReturn("too many obstacles (water, lava, drops)");
            return;
        }
        if (tunnelLen > MAX_TUNNEL) {
            beginReturn("the tunnel got long and found nothing more");
            return;
        }

        // забрать выпавшее
        if (lootTicks > 0) {
            lootTicks--;
            if (lootPos != null) {
                friend.getNavigation().moveTo(lootPos.getX() + 0.5, lootPos.getY(), lootPos.getZ() + 0.5, 1.0);
            }
            if (lootTicks == 0) {
                lootPos = null;
            }
            return;
        }

        // копание текущего блока
        if (breakPos != null) {
            tickBreak(level);
            return;
        }

        // движение к следующей клетке
        if (moveTo != null) {
            tickMove();
            return;
        }

        // руда рядом
        if (--scanCooldown <= 0) {
            scanCooldown = 8;
            if (orePos == null) {
                orePos = findOre(job.item);
                oreWalkTicks = 0;
            }
        }
        if (orePos != null) {
            tickOre(level);
            return;
        }

        planStep(level);
    }

    // ------------------------------------------------------------------ шаг шахты

    private void planStep(Level level) {
        BlockPos p = friend.blockPosition();
        int[] d = DIRS[dirIndex];
        boolean down = phase == Phase.DESCEND;

        if (down && p.getY() <= targetY) {
            phase = Phase.TUNNEL;
            down = false;
        }
        if (!down && tunnelLen % 8 == 3) {
            placeTorch(level, p);
        }

        BlockPos front = p.offset(d[0], 0, d[1]);
        List<BlockPos> cells = new ArrayList<>();
        BlockPos stand; // клетка, где окажутся ноги
        if (down) {
            cells.add(front.above());
            cells.add(front);
            cells.add(front.below());
            stand = front.below();
        } else {
            cells.add(front);
            cells.add(front.above());
            stand = front;
        }

        for (BlockPos c : cells) {
            if (isDanger(level, c)) {
                turn("danger ahead");
                return;
            }
        }
        BlockPos floor = stand.below();
        if (fluid(level, floor) || neighborFluid(level, floor)) {
            turn("fluid below");
            return;
        }
        if (passable(level, floor) && passable(level, floor.below())) {
            turn("a drop");
            return;
        }
        if (level.getBlockState(floor).getDestroySpeed(level, floor) < 0 && !passable(level, floor) && down) {
            turn("bedrock");
            return;
        }

        for (BlockPos c : cells) {
            if (!passable(level, c)) {
                startBreak(level, c, false);
                return;
            }
        }

        // свободно: идём
        moveTo = stand;
        moveTicks = 0;
        if (!down) {
            tunnelLen++;
        }
    }

    private void turn(String reason) {
        turns++;
        dirIndex = (dirIndex + (turns % 2 == 1 ? 1 : 3)) % 4;
        if (turns > MAX_TURNS) {
            stopReason = reason;
        }
    }

    private boolean isDanger(Level level, BlockPos c) {
        BlockState s = level.getBlockState(c);
        if (fluid(level, c) || neighborFluid(level, c)) {
            return true;
        }
        if (!s.isAir() && s.getDestroySpeed(level, c) < 0) {
            return true; // бедрок и подобное
        }
        if (level.getBlockEntity(c) != null) {
            return true; // сундуки, спавнеры: не трогаем
        }
        if (!passable(level, c) && !friend.canHarvest(s)) {
            return true; // нечем ломать
        }
        return false;
    }

    private boolean neighborFluid(Level level, BlockPos c) {
        for (Direction dir : Direction.values()) {
            if (fluid(level, c.relative(dir))) {
                return true;
            }
        }
        return false;
    }

    private boolean fluid(Level level, BlockPos p) {
        return level.hasChunkAt(p) && !level.getBlockState(p).getFluidState().isEmpty();
    }

    private boolean passable(Level level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.getCollisionShape(level, p).isEmpty() && s.getFluidState().isEmpty();
    }

    // ------------------------------------------------------------------ движение

    private void tickMove() {
        BlockPos m = moveTo;
        if (m == null) {
            return;
        }
        double cx = m.getX() + 0.5;
        double cz = m.getZ() + 0.5;
        friend.getLookControl().setLookAt(cx, m.getY() + 1.0, cz);
        boolean arrived = Math.abs(friend.getX() - cx) < 0.45 && Math.abs(friend.getZ() - cz) < 0.45 && friend.getY() - m.getY() < 1.2;
        if (arrived) {
            moveTo = null;
            trail.add(friend.blockPosition());
            return;
        }
        if (moveTicks++ % 10 == 0) {
            friend.getNavigation().moveTo(cx, m.getY(), cz, 1.0);
        }
        if (moveTicks > 60) {
            // застряли: переставляем бота на клетку, куда он шёл (она уже свободна и проверена)
            friend.moveTo(cx, m.getY(), cz, friend.getYRot(), friend.getXRot());
        }
    }

    private void tickReturn() {
        if (returnIndex < 0 || trail.isEmpty()) {
            finishReturn();
            return;
        }
        BlockPos t = trail.get(Math.max(0, returnIndex - 1));
        double cx = t.getX() + 0.5;
        double cz = t.getZ() + 0.5;
        friend.getLookControl().setLookAt(cx, t.getY() + 1.0, cz);
        boolean arrived = friend.distanceToSqr(cx, t.getY(), cz) < 1.6 * 1.6;
        if (arrived) {
            returnIndex = Math.max(0, returnIndex - 1) - 1;
            if (returnIndex < 0) {
                finishReturn();
                return;
            }
            moveTicks = 0;
            return;
        }
        if (moveTicks++ % 10 == 0) {
            friend.getNavigation().moveTo(cx, t.getY(), cz, 1.1);
        }
        if (moveTicks > 80) {
            friend.moveTo(cx, t.getY(), cz, friend.getYRot(), friend.getXRot());
            moveTicks = 0;
        }
    }

    private void finishReturn() {
        FriendEntity.Job job = friend.job();
        int got = job.smelted;
        String key = job.item;
        boolean wasMine = friend.getTask() == FriendEntity.Task.MINE;
        trail.clear();
        phase = Phase.NONE;
        returnIndex = -1;
        blacklist.clear();
        if (wasMine) {
            if (job.remaining <= 0) {
                friend.endJob(true, "finished the mine: got " + got + " " + key + " ore, and you are back on the surface");
            } else {
                friend.endJob(got > 0, "stopped the mine and came back up with " + got + " of " + job.total + " " + key + " ore"
                        + (stopReason.isEmpty() ? "" : " (" + stopReason + ")"));
            }
        }
    }

    // ------------------------------------------------------------------ копание

    private void startBreak(Level level, BlockPos pos, boolean ore) {
        BlockState s = level.getBlockState(pos);
        if (!friend.canHarvest(s)) {
            if (ore) {
                blacklist(pos);
                orePos = null;
            } else {
                turn("no suitable tool");
            }
            return;
        }
        breakPos = pos;
        breakingOre = ore;
        progress = 0;
        friend.equipToolFor(s);
        needed = ticksToBreak(level, s, pos);
    }

    private void tickBreak(Level level) {
        BlockPos pos = breakPos;
        if (pos == null) {
            return;
        }
        BlockState s = level.getBlockState(pos);
        if (s.isAir() || !s.getFluidState().isEmpty()) {
            breakPos = null;
            return;
        }
        Vec3 center = Vec3.atCenterOf(pos);
        friend.getLookControl().setLookAt(center.x, center.y, center.z);
        friend.getNavigation().stop();
        if (friend.getEyePosition().distanceToSqr(center) > (REACH + 0.6) * (REACH + 0.6)) {
            breakPos = null; // отошли слишком далеко
            return;
        }
        progress++;
        if (progress % 6 == 1) {
            friend.swing(InteractionHand.MAIN_HAND);
        }
        if (progress < needed) {
            return;
        }
        if (!(level instanceof ServerLevel sl)) {
            return;
        }
        ItemStack tool = friend.getMainHandItem();
        boolean correct = !s.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(s);
        if (correct) {
            Block.dropResources(s, sl, pos, sl.getBlockEntity(pos), friend, tool);
        }
        sl.destroyBlock(pos, false, friend);
        if (breakingOre) {
            friend.onOreMined();
            lootPos = pos;
            lootTicks = 20;
            orePos = null;
        }
        breakPos = null;
        progress = 0;
    }

    private int ticksToBreak(Level level, BlockState state, BlockPos pos) {
        float hardness = state.getDestroySpeed(level, pos);
        ItemStack tool = friend.getMainHandItem();
        boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        float speed = Math.max(1.0F, tool.getDestroySpeed(state));
        return Math.max(5, (int) Math.ceil(hardness * (correct ? 30.0F : 100.0F) / speed));
    }

    // ------------------------------------------------------------------ руда

    private void tickOre(Level level) {
        BlockPos o = orePos;
        if (o == null) {
            return;
        }
        BlockState s = level.getBlockState(o);
        if (s.isAir() || !isTargetOre(s, friend.job().item)) {
            orePos = null;
            return;
        }
        Vec3 center = Vec3.atCenterOf(o);
        friend.getLookControl().setLookAt(center.x, center.y, center.z);
        if (canReach(level, o, center)) {
            startBreak(level, o, true);
            return;
        }
        // подойти к соседней свободной клетке
        if (oreWalkTicks++ % 10 == 0) {
            BlockPos stand = standNear(level, o);
            if (stand == null || !friend.getNavigation().moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 1.0)) {
                blacklist(o);
                orePos = null;
                return;
            }
        }
        if (oreWalkTicks > 160) {
            blacklist(o);
            orePos = null;
        }
    }

    private boolean canReach(Level level, BlockPos o, Vec3 center) {
        if (friend.getEyePosition().distanceToSqr(center) > REACH * REACH) {
            return false;
        }
        BlockHitResult hit = level.clip(new ClipContext(friend.getEyePosition(), center, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, friend));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(o);
    }

    @Nullable
    private BlockPos standNear(Level level, BlockPos o) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (Direction dir : Direction.values()) {
            BlockPos c = o.relative(dir);
            boolean free = passable(level, c) && passable(level, c.above());
            boolean floor = !passable(level, c.below());
            if (!free) {
                // может быть и клетка на уровне ног ниже
                continue;
            }
            if (!floor && !passable(level, c.below()) ) {
                continue;
            }
            double d = friend.distanceToSqr(Vec3.atBottomCenterOf(c));
            if (floor && d < bd) {
                bd = d;
                best = c;
            }
        }
        return best;
    }

    private boolean isTargetOre(BlockState s, String key) {
        String path = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        return path.endsWith("_ore") && path.contains(key);
    }

    @Nullable
    private BlockPos findOre(String key) {
        Level level = friend.level();
        BlockPos c = friend.blockPosition();
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-6, -4, -6), c.offset(6, 4, 6))) {
            if (!level.hasChunkAt(p) || isBlacklisted(p)) {
                continue;
            }
            BlockState s = level.getBlockState(p);
            if (s.isAir() || !isTargetOre(s, key)) {
                continue;
            }
            if (!exposed(level, p) || neighborLava(level, p) || !friend.canHarvest(s)) {
                continue;
            }
            found.add(p.immutable());
        }
        found.sort(Comparator.comparingDouble(p -> p.distSqr(c)));
        return found.isEmpty() ? null : found.get(0);
    }

    private boolean exposed(Level level, BlockPos p) {
        for (Direction dir : Direction.values()) {
            BlockPos n = p.relative(dir);
            if (level.hasChunkAt(n) && level.getBlockState(n).getCollisionShape(level, n).isEmpty()
                    && level.getBlockState(n).getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean neighborLava(Level level, BlockPos p) {
        for (Direction dir : Direction.values()) {
            if (level.getBlockState(p.relative(dir)).getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    private void placeTorch(Level level, BlockPos p) {
        if (level.getMaxLocalRawBrightness(p) >= 7 || !level.getBlockState(p).isAir() || passable(level, p.below())) {
            return;
        }
        for (int i = 0; i < friend.getInventory().getContainerSize(); i++) {
            ItemStack s = friend.getInventory().getItem(i);
            if (s.is(Items.TORCH)) {
                level.setBlock(p, Blocks.TORCH.defaultBlockState(), 3);
                friend.getInventory().removeItem(i, 1);
                return;
            }
        }
    }

    private void blacklist(BlockPos p) {
        blacklist.put(p.asLong(), friend.tickCount + 2400);
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
