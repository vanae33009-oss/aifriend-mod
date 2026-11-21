package com.aifriend.command;

import java.util.List;

import com.aifriend.ModEntities;
import com.aifriend.SkinCatalog;
import com.aifriend.ai.AiSettings;
import com.aifriend.entity.FriendEntity;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** /friend spawn [имя] | dismiss | name <имя> | gender male|female | skin <id|list> | status | reload */
public final class FriendCommands {
    private FriendCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("friend")
                .then(Commands.literal("spawn")
                        .executes(ctx -> spawn(ctx, null))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> spawn(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("dismiss").executes(FriendCommands::dismiss))
                .then(Commands.literal("name")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> rename(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("gender")
                        .then(Commands.literal("male").executes(ctx -> gender(ctx, false)))
                        .then(Commands.literal("female").executes(ctx -> gender(ctx, true))))
                .then(Commands.literal("skin")
                        .then(Commands.literal("list").executes(FriendCommands::skinList))
                        .then(Commands.argument("id", StringArgumentType.greedyString())
                                .executes(ctx -> skin(ctx, StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("status").executes(FriendCommands::status))
                .then(Commands.literal("reload").executes(ctx -> {
                    AiSettings.load();
                    ctx.getSource().sendSuccess(() -> Component.literal("AI Friend: settings reloaded"), false);
                    return 1;
                })));
    }

    private static List<FriendEntity> myBuddies(ServerPlayer player) {
        return player.serverLevel().getEntitiesOfClass(FriendEntity.class, player.getBoundingBox().inflate(128.0),
                b -> player.getUUID().equals(b.getOwnerUUID()));
    }

    /** Создаёт друга рядом с игроком: пол, скин и имя из настроек. Возвращает null, если не получилось. */
    public static FriendEntity spawnFriend(ServerPlayer player, String name) {
        ServerLevel level = player.serverLevel();
        FriendEntity friend = ModEntities.FRIEND.get().create(level);
        if (friend == null) {
            return null;
        }
        Vec3 look = player.getLookAngle();
        friend.moveTo(player.getX() + look.x * 1.5, player.getY(), player.getZ() + look.z * 1.5, player.getYRot() + 180.0F, 0.0F);
        friend.setOwner(player);
        boolean female = AiSettings.defaultFemale();
        friend.setFemale(female);
        String wanted = SkinCatalog.resolve(AiSettings.get().botSkin);
        friend.setSkin(wanted != null ? wanted : SkinCatalog.defaultFor(female));
        friend.setFriendName(name != null && !name.isBlank() ? name.trim() : AiSettings.pickName(female));
        level.addFreshEntity(friend);
        return friend;
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        FriendEntity friend = spawnFriend(player, name);
        if (friend == null) {
            return 0;
        }
        String shown = friend.getName().getString();
        ctx.getSource().sendSuccess(() -> Component.literal("AI Friend: " + shown + " joined the world. Just talk in chat."), false);
        if (!AiSettings.isConfigured()) {
            ctx.getSource().sendSuccess(() -> Component.literal("AI Friend: no API key yet - press K to open the settings."), false);
        }
        return 1;
    }

    private static int dismiss(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        List<FriendEntity> list = myBuddies(player);
        for (FriendEntity b : list) {
            b.discard();
        }
        int n = list.size();
        ctx.getSource().sendSuccess(() -> Component.literal("AI Friend: dismissed " + n), false);
        return n;
    }

    private static int rename(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        List<FriendEntity> list = myBuddies(player);
        if (list.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("AI Friend: you have no friend nearby"));
            return 0;
        }
        list.get(0).setFriendName(name.trim());
        return 1;
    }

    private static int gender(CommandContext<CommandSourceStack> ctx, boolean female) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        List<FriendEntity> list = myBuddies(player);
        if (list.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("AI Friend: you have no friend nearby"));
            return 0;
        }
        FriendEntity b = list.get(0);
        b.setFemale(female);
        b.setSkin(SkinCatalog.defaultFor(female));
        ctx.getSource().sendSuccess(() -> Component.literal("AI Friend: " + b.getName().getString() + " is now "
                + (female ? "female" : "male") + " (skin " + b.getSkin() + "). Change it with /friend skin <id>, rename with /friend name <name>."), false);
        return 1;
    }

    private static int skin(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        List<FriendEntity> list = myBuddies(player);
        if (list.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("AI Friend: you have no friend nearby"));
            return 0;
        }
        String resolved = SkinCatalog.resolve(id);
        if (resolved == null) {
            ctx.getSource().sendFailure(Component.literal("AI Friend: no skin '" + id.trim() + "'. See /friend skin list. Put your own png (64x64) into "
                    + SkinCatalog.dir()));
            return 0;
        }
        list.get(0).setSkin(resolved);
        ctx.getSource().sendSuccess(() -> Component.literal("AI Friend: skin set to " + resolved), false);
        return 1;
    }

    private static int skinList(CommandContext<CommandSourceStack> ctx) {
        SkinCatalog.ensureDir();
        ctx.getSource().sendSuccess(() -> Component.literal("Skins: " + String.join(", ", SkinCatalog.all())
                + ". Your own: png 64x64 in " + SkinCatalog.dir() + " (a name ending in 'slim' = thin arms)"), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        List<FriendEntity> list = myBuddies(player);
        AiSettings.Resolved api = AiSettings.resolve();
        ctx.getSource().sendSuccess(() -> Component.literal("AI Friend: provider " + api.label() + ", model " + api.model()
                + (api.usable() ? "" : " (NOT configured)")), false);
        for (FriendEntity b : list) {
            String line = b.getName().getString() + ": " + b.describeTask() + ", hp " + Math.round(b.getHealth());
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        if (list.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No buddies nearby. Use /friend spawn"), false);
        }
        return list.size();
    }
}
