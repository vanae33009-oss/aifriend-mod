package com.aifriend;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@EventBusSubscriber(modid = AiFriendMod.MODID)
public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AiFriendMod.MODID);

    public static final DeferredItem<Item> FRIEND_SPAWN_EGG = ITEMS.register("friend_spawn_egg",
            () -> new DeferredSpawnEggItem(ModEntities.FRIEND, 0x5B8731, 0x2B2B2B, new Item.Properties()));

    private ModItems() {
    }

    @SubscribeEvent
    static void onCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(FRIEND_SPAWN_EGG);
        }
    }
}
