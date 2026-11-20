package com.aifriend;

import com.aifriend.ai.AiSettings;
import com.aifriend.command.FriendCommands;
import com.aifriend.entity.FriendEntity;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

@EventBusSubscriber(modid = AiFriendMod.MODID)
public final class FriendEvents {
    private FriendEvents() {
    }

    /** Любое сообщение в чате слышат боты поблизости (обычный чат при этом не отменяется). */
    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        String text = event.getRawText();
        player.server.execute(() -> FriendChat.route(player, text));
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        FriendCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !AiSettings.isConfigured()) {
            player.sendSystemMessage(Component.literal(
                    "[AI Friend] API key is not set. Press K (or Mods > AI Friend > Config) to set it up, then use /friend spawn."));
        }
    }

    /** Игрок погиб: его боты поблизости это замечают и могут отреагировать. */
    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            String cause = event.getSource().getLocalizedDeathMessage(player).getString();
            for (FriendEntity b : player.serverLevel().getEntitiesOfClass(FriendEntity.class, player.getBoundingBox().inflate(64.0),
                    x -> player.getUUID().equals(x.getOwnerUUID()))) {
                b.getMind().onPlayerDied(player, cause);
            }
        }
    }
}
