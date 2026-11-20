package com.aifriend.entity.goal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.aifriend.entity.FriendEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Строительство из блоков в инвентаре. Постройки: shelter (домик, size = внутренняя сторона 2..7),
 * wall (стена высотой 3, size = длина), platform (площадка/мост по земле, size = сторона).
 * Блоки ставятся по одному, расходуют предметы бота. Материал выбирается автоматически (доски, булыжник, камень, земля)
 * или задаётся явно (job.material). Дорогие блоки (руды, железо и т.п.) сами не используются.
 */
public class FriendBuildGoal extends Goal {
    private static final double REACH_SQR = 5.0 * 5.0;

    private final FriendEntity friend;
    private final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
    private final Map<Long, Integer> retries = new HashMap<>();
    private boolean planned;
    private int placed;
    private int cooldown;
    private int walkTicks;

    public FriendBuildGoal(FriendEntity friend) {
        this.friend = friend;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return friend.getTask() == FriendEntity.Task.BUILD;
    }

    @Override
    public boolean canContinueToUse() {
        return friend.getTask() == FriendEntity.Task.BUILD;
    }

    @Override
    public void start() {
        if (!planned) {
            plan();
        }
    }

    @Override
    public void stop() {
        friend.getNavigation().stop();
        if (friend.getTask() != FriendEntity.Task.BUILD) {
            planned = false;
            queue.clear();
            retries.clear();
            placed = 0;
        }
    }

    // ------------------------------------------------------------------ план постройки

    private void plan() {
        planned = true;
        placed = 0;
        queue.clear();
        retries.clear();
        FriendEntity.Job job = friend.job();
        Level level = friend.level();

        Direction f = Direction.fromYRot(friend.getYRot());
        Direction r = f.getClockWise();
        BlockPos o = friend.blockPosition().relative(f, 2);
        List<BlockPos> blocks = new ArrayList<>();

        switch (job.kind) {
            case "wall" -> {
                int len = clamp(job.size <= 0 ? 6 : job.size, 1, 32);
                for (int h = 0; h < 3; h++) {
                    for (int ri = 0; ri < len; ri++) {
                        blocks.add(at(o, f, r, 0, ri, h));
                    }
                }
            }
            case "platform", "floor", "bridge" -> {
                int n = clamp(job.size <= 0 ? 4 : job.size, 2, 10);
                for (int fi = 0; fi < n; fi++) {
                    for (int ri = 0; ri < n; ri++) {
                        blocks.add(at(o, f, r, fi, ri, -1));
                    }
                }
            }
            default -> { // shelter
                job.kind = "shelter";
                int n = clamp(job.size <= 0 ? 3 : job.size, 2, 7);
                int w = n + 2;
                int rMin = -(w / 2);
                int rMax = rMin + w - 1;
                int doorR = rMin + w / 2;
                for (int h = 0; h <= 3; h++) {
                    for (int fi = 0; fi < w; fi++) {
                        for (int ri = rMin; ri <= rMax; ri++) {
                            boolean edge = fi == 0 || fi == w - 1 || ri == rMin || ri == rMax;
                            if (h < 3 && !edge) {
                                continue;
                            }
                            if (h < 2 && fi == 0 && ri == doorR) {
                                continue; // дверной проём
                            }
                            blocks.add(at(o, f, r, fi, ri, h));
                        }
                    }
                }
            }
        }

        blocks.removeIf(p -> !level.getBlockState(p).canBeReplaced());
        blocks.sort(Comparator.comparingInt(BlockPos::getY));
        queue.addAll(blocks);
        job.total = queue.size();
        job.remaining = queue.size();

        if (queue.isEmpty()) {
            friend.endJob(false, "nothing to build there: the spot is already occupied");
        } else if (pickMaterial() < 0) {
            friend.endJob(false, "cannot build a " + job.kind + ": no suitable blocks in the inventory (needs about "
                    + job.total + " planks/cobblestone/stone/dirt)");
        }
    }

    private static BlockPos at(BlockPos o, Direction f, Direction r, int fi, int ri, int h) {
        return o.relative(f, fi).relative(r, ri).above(h);
    }

    // ------------------------------------------------------------------ работа

    @Override
    public void tick() {
        if (!planned) {
            plan();
            return;
        }
        FriendEntity.Job job = friend.job();
        if (queue.isEmpty()) {
            friend.endJob(true, "finished building the " + job.kind + " (" + placed + " blocks placed)");
            return;
        }
        if (--cooldown > 0) {
            return;
        }

        Level level = friend.level();
        BlockPos p = queue.peek();
        if (!level.getBlockState(p).canBeReplaced()) {
            queue.poll();
            job.remaining = queue.size();
            return;
        }

        Vec3 c = Vec3.atCenterOf(p);
        friend.getLookControl().setLookAt(c.x, c.y, c.z);
        if (friend.getEyePosition().distanceToSqr(c) > REACH_SQR) {
            if (walkTicks++ % 10 == 0) {
                friend.getNavigation().moveTo(c.x, c.y, c.z, 1.0);
            }
            if (walkTicks > 200) {
                walkTicks = 0;
                postpone(p);
            }
            return;
        }
        walkTicks = 0;

        // не ставим блок на живое существо (в том числе на самого бота)
        AABB box = new AABB(p);
        List<Entity> inside = level.getEntities((Entity) null, box, e -> e.isAlive() && !(e instanceof ItemEntity));
        if (!inside.isEmpty()) {
            postpone(p);
            cooldown = 4;
            return;
        }

        int slot = pickMaterial();
        if (slot < 0) {
            friend.endJob(false, "ran out of building blocks after placing " + placed + " of " + job.total);
            return;
        }
        SimpleContainer inv = friend.getInventory();
        BlockItem item = (BlockItem) inv.getItem(slot).getItem();
        BlockState state = item.getBlock().defaultBlockState();
        level.setBlock(p, state, 3);
        level.playSound(null, p, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F);
        inv.removeItem(slot, 1);
        friend.swing(InteractionHand.MAIN_HAND);
        queue.poll();
        placed++;
        job.remaining = queue.size();
        cooldown = 5;
    }

    /** Откладывает блок в конец очереди; после нескольких неудач отказывается от него. */
    private void postpone(BlockPos p) {
        int n = retries.merge(p.asLong(), 1, Integer::sum);
        queue.poll();
        if (n <= 6) {
            queue.addLast(p);
        }
        friend.job().remaining = queue.size();
    }

    // ------------------------------------------------------------------ материал

    /** Слот с лучшим подходящим блоком или -1. */
    private int pickMaterial() {
        FriendEntity.Job job = friend.job();
        SimpleContainer inv = friend.getInventory();
        int best = -1;
        int bestScore = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) {
                continue;
            }
            String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            boolean explicit = !job.material.isEmpty();
            if (explicit && !path.equals(job.material)) {
                continue;
            }
            if (!isBuildable(bi.getBlock(), friend.level())) {
                continue;
            }
            int score = explicit ? 10 : score(path);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private static boolean isBuildable(Block block, Level level) {
        BlockState st = block.defaultBlockState();
        return !st.hasBlockEntity()
                && Block.isShapeFullBlock(st.getCollisionShape(level, BlockPos.ZERO))
                && !(block instanceof FallingBlock)
                && !(block instanceof LeavesBlock)
                && block != Blocks.TNT
                && block != Blocks.CRAFTING_TABLE;
    }

    private static int score(String path) {
        if (path.contains("ore") || path.startsWith("raw_") || path.contains("iron") || path.contains("gold")
                || path.contains("diamond") || path.contains("emerald") || path.contains("netherite") || path.contains("lapis")
                || path.contains("redstone") || path.contains("coal") || path.contains("copper") || path.contains("amethyst")
                || path.contains("quartz")) {
            return 0; // ценные блоки без прямого указания не тратим
        }
        if (path.endsWith("_planks")) return 6;
        if (path.equals("cobblestone")) return 5;
        if (path.equals("stone")) return 4;
        if (path.equals("dirt") || path.contains("deepslate") || path.contains("sandstone") || path.contains("bricks")) return 3;
        return 1;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
