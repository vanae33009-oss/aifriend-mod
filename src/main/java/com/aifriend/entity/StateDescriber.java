package com.aifriend.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Короткое текстовое описание того, что бот «видит и знает» прямо сейчас (только он сам и его окружение). */
final class StateDescriber {
    private StateDescriber() {
    }

    static String describe(FriendEntity b, @Nullable Player player) {
        Level level = b.level();
        StringBuilder sb = new StringBuilder();

        long t = level.getDayTime() % 24000L;
        String time = t < 12000 ? "day" : "night";
        String weather = level.isThundering() ? "thunderstorm" : level.isRaining() ? "raining" : "clear";
        BlockPos pos = b.blockPosition();
        String biome = level.getBiome(pos).unwrapKey().map(k -> k.location().getPath()).orElse("unknown");
        String dim = level.dimension().location().getPath();

        sb.append("- world: ").append(dim).append(", ").append(time).append(", weather ").append(weather)
                .append(", biome ").append(biome).append('\n');
        sb.append("- you: health ").append(Math.round(b.getHealth())).append('/').append(Math.round(b.getMaxHealth()))
                .append(", position ").append(pos.getX()).append(' ').append(pos.getY()).append(' ').append(pos.getZ()).append('\n');
        sb.append("- current task: ").append(b.describeTask()).append('\n');
        String plan = b.getMind().getPlan();
        if (!plan.isEmpty()) {
            sb.append("- your plan: ").append(plan).append('\n');
        }
        String mood = b.getMind().getMood();
        if (!mood.isEmpty()) {
            sb.append("- your mood: ").append(mood).append('\n');
        }
        if (!b.getMind().getNotes().isEmpty()) {
            String pn = player != null ? player.getGameProfile().getName() : "the player";
            sb.append("- what you know about ").append(pn).append(" (your own notes): ").append(String.join("; ", b.getMind().getNotes())).append('\n');
        }
        if (!b.getKnownPlaces().isEmpty()) {
            sb.append("- places you found while scouting: ").append(String.join("; ", b.getKnownPlaces())).append('\n');
        }
        sb.append("- inventory: ").append(inventory(b.getInventory())).append('\n');

        List<String> armor = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.isArmor() && !b.getItemBySlot(slot).isEmpty()) {
                armor.add(id(b.getItemBySlot(slot)));
            }
        }
        sb.append("- wearing: ").append(armor.isEmpty() ? "no armor" : String.join(", ", armor)).append('\n');

        if (player != null && player.level() == level) {
            sb.append("- player ").append(player.getGameProfile().getName()).append(": ")
                    .append(Math.round(b.distanceTo(player))).append(" blocks away, health ")
                    .append(Math.round(player.getHealth())).append(", holding ")
                    .append(player.getMainHandItem().isEmpty() ? "nothing" : id(player.getMainHandItem())).append('\n');
            String lang = FriendSocial.languageOf(player);
            if (lang != null && !lang.isEmpty()) {
                sb.append("- player's game language: ").append(lang).append('\n');
            }
        } else {
            sb.append("- player: not nearby\n");
        }

        int shown = 0;
        for (AbstractVillager v : level.getEntitiesOfClass(AbstractVillager.class, b.getBoundingBox().inflate(10.0), x -> x.isAlive())) {
            if (shown++ >= 2) {
                break;
            }
            String kind = v instanceof Villager vv
                    ? String.valueOf(BuiltInRegistries.VILLAGER_PROFESSION.getKey(vv.getVillagerData().getProfession()).getPath())
                    : "wandering trader";
            List<String> offers = new ArrayList<>();
            for (MerchantOffer o : v.getOffers()) {
                if (offers.size() >= 6) {
                    break;
                }
                ItemStack a = o.getCostA();
                ItemStack c = o.getCostB();
                ItemStack r = o.getResult();
                offers.add(a.getCount() + " " + id(a) + (c.isEmpty() ? "" : " + " + c.getCount() + " " + id(c))
                        + " -> " + r.getCount() + " " + id(r) + (o.isOutOfStock() ? " (sold out)" : ""));
            }
            sb.append("- villager (").append(kind).append("), ").append(Math.round(b.distanceTo(v))).append(" blocks away, trades: ")
                    .append(offers.isEmpty() ? "none" : String.join("; ", offers)).append('\n');
        }

        sb.append("- blocks you can see nearby (name xcount, nearest distance): ").append(visibleBlocks(b)).append('\n');

        Map<String, Integer> nearby = new TreeMap<>(Comparator.naturalOrder());
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, b.getBoundingBox().inflate(16.0),
                x -> x != b && x != player && !(x instanceof Player))) {
            String key = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath() + (e instanceof Enemy ? " (hostile)" : "");
            nearby.merge(key, 1, Integer::sum);
        }
        if (nearby.isEmpty()) {
            sb.append("- nearby creatures: none\n");
        } else {
            List<String> parts = new ArrayList<>();
            int n = 0;
            for (Map.Entry<String, Integer> en : nearby.entrySet()) {
                if (n++ >= 8) {
                    break;
                }
                parts.add(en.getKey() + " x" + en.getValue());
            }
            sb.append("- nearby creatures: ").append(String.join(", ", parts)).append('\n');
        }
        return sb.toString().trim();
    }

    /** Что бот реально видит вокруг (радиус 12): деревья, открытые руды, вода, лава, верстаки и т.п. Скрытые под землёй руды не видны. */
    private static String visibleBlocks(FriendEntity b) {
        Level level = b.level();
        BlockPos c = b.blockPosition();
        Map<String, int[]> found = new HashMap<>(); // имя -> {количество, ближайшее расстояние в квадрате}
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-12, -8, -12), c.offset(12, 8, 12))) {
            if (!level.hasChunkAt(p)) {
                continue;
            }
            BlockState s = level.getBlockState(p);
            if (s.isAir()) {
                continue;
            }
            String name = interesting(s, level, p);
            if (name == null) {
                continue;
            }
            int d = (int) p.distSqr(c);
            int[] e = found.computeIfAbsent(name, k -> new int[] { 0, Integer.MAX_VALUE });
            e[0]++;
            e[1] = Math.min(e[1], d);
        }
        if (found.isEmpty()) {
            return "nothing notable (no trees, no exposed ore)";
        }
        List<Map.Entry<String, int[]>> list = new ArrayList<>(found.entrySet());
        list.sort((x, y) -> Integer.compare(y.getValue()[0], x.getValue()[0]));
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(10, list.size()); i++) {
            Map.Entry<String, int[]> en = list.get(i);
            parts.add(en.getKey() + " x" + en.getValue()[0] + " (" + Math.round(Math.sqrt(en.getValue()[1])) + ")");
        }
        return String.join(", ", parts);
    }

    private static String interesting(BlockState s, Level level, BlockPos p) {
        String path = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        if (path.endsWith("_log") && !path.startsWith("stripped_")) {
            return path;
        }
        if (path.endsWith("_ore")) {
            return exposed(level, p) ? path : null;
        }
        if (path.endsWith("_bed")) {
            return "bed";
        }
        switch (path) {
            case "water":
                return s.getFluidState().isSource() ? "water" : null;
            case "lava":
                return s.getFluidState().isSource() ? "lava (danger)" : null;
            case "crafting_table", "furnace", "chest", "sugar_cane", "pumpkin", "melon":
                return path;
            default:
                return null;
        }
    }

    private static boolean exposed(Level level, BlockPos p) {
        for (Direction dir : Direction.values()) {
            BlockPos n = p.relative(dir);
            if (level.hasChunkAt(n) && level.getBlockState(n).isAir()) {
                return true;
            }
        }
        return false;
    }

    private static String inventory(SimpleContainer inv) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) {
                counts.merge(id(s), s.getCount(), Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return "empty";
        }
        List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((x, y) -> Integer.compare(y.getValue(), x.getValue()));
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(14, list.size()); i++) {
            parts.add(list.get(i).getKey() + " x" + list.get(i).getValue());
        }
        return String.join(", ", parts);
    }

    private static String id(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }
}
