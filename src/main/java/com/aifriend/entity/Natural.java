package com.aifriend.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Отличает природные блоки от чужих построек. Бот не должен разбирать деревни и дома игрока ради брёвен и камня:
 * блок считается чужим, если он внутри деревни, рядом с явными постройками (доски, двери, стёкла, факелы, верстаки...)
 * или это бревно, у ствола которого нет живой листвы (у настоящего дерева наверху всегда есть листва).
 */
public final class Natural {
    private Natural() {
    }

    public static boolean isNatural(Level level, BlockPos p, BlockState state) {
        if (level instanceof ServerLevel sl) {
            StructureStart village = sl.structureManager().getStructureWithPieceAt(p, StructureTags.VILLAGE);
            if (village.isValid()) {
                return false;
            }
        }
        if (nearManMade(level, p)) {
            return false;
        }
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (path.endsWith("_wood")) {
            return false; // обтёсанная кора встречается только в постройках
        }
        if (path.endsWith("_log")) {
            return hasLeavesAbove(level, p);
        }
        return true;
    }

    /** Брёвна ствола заканчиваются кроной: смотрим вверх по стволу и ищем непостоянную (природную) листву. */
    private static boolean hasLeavesAbove(Level level, BlockPos p) {
        BlockPos top = p;
        for (int i = 0; i < 24; i++) {
            BlockPos up = top.above();
            if (level.getBlockState(up).getBlock() instanceof net.minecraft.world.level.block.RotatedPillarBlock
                    && BuiltInRegistries.BLOCK.getKey(level.getBlockState(up).getBlock()).getPath().endsWith("_log")) {
                top = up;
            } else {
                break;
            }
        }
        for (BlockPos q : BlockPos.betweenClosed(top.offset(-2, -1, -2), top.offset(2, 2, 2))) {
            BlockState s = level.getBlockState(q);
            if (s.getBlock() instanceof LeavesBlock && !s.getValue(LeavesBlock.PERSISTENT)) {
                return true;
            }
        }
        return false;
    }

    private static boolean nearManMade(Level level, BlockPos p) {
        for (BlockPos q : BlockPos.betweenClosed(p.offset(-1, -1, -1), p.offset(1, 1, 1))) {
            if (q.equals(p)) {
                continue;
            }
            String n = BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).getPath();
            if (manMade(n)) {
                return true;
            }
        }
        return false;
    }

    private static boolean manMade(String n) {
        if (n.endsWith("_planks") || n.endsWith("_stairs") || n.endsWith("_slab") || n.endsWith("_door") || n.endsWith("_fence")
                || n.endsWith("_fence_gate") || n.endsWith("_trapdoor") || n.endsWith("_pane") || n.endsWith("_sign")
                || n.endsWith("_bed") || n.endsWith("_carpet") || n.endsWith("_banner") || n.endsWith("_button")
                || n.endsWith("_pressure_plate") || n.endsWith("_wall")) {
            return true;
        }
        if (n.contains("glass") || n.contains("brick") || n.contains("lantern") || n.contains("torch") || n.contains("rail")) {
            return true;
        }
        switch (n) {
            case "crafting_table", "chest", "trapped_chest", "barrel", "furnace", "blast_furnace", "smoker", "bookshelf", "ladder",
                    "cobblestone", "hay_block", "composter", "bell", "campfire", "lectern", "loom", "grindstone", "smithing_table",
                    "fletching_table", "cartography_table", "brewing_stand", "cauldron", "farmland", "path_blocks", "dirt_path",
                    "scaffolding", "iron_bars", "chain", "flower_pot", "jack_o_lantern", "tnt", "bed":
                return true;
            default:
                return false;
        }
    }
}
