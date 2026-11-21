package com.aifriend;

import org.slf4j.Logger;

import com.aifriend.ai.AiSettings;
import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(AiFriendMod.MODID)
public class AiFriendMod {
    public static final String MODID = "aifriend";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AiFriendMod(IEventBus modEventBus) {
        AiSettings.load();
        ModEntities.ENTITY_TYPES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
    }
}
