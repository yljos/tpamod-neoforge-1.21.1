package com.tpa.yljos;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collections;

@Mod(TpaMod.MODID)
@EventBusSubscriber(modid = TpaMod.MODID)
public class TpaMod {
    public static final String MODID = "tpamod";
    private static final String PERSISTED_TAG = "PlayerPersisted";
    private static final String HOME_TAG = "HomeLocation";

    public TpaMod(ModContainer modContainer) {
        // Register server configuration
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
    }

    // Play teleport sound and portal particles around the player
    public static void playTeleportEffects(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        level.playSound(
                null,
                player.getX(),
                player.getY(),
                player.getZ(),
                SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.PLAYERS,
                1.0F,
                1.0F
        );
        level.sendParticles(
                ParticleTypes.PORTAL,
                player.getX(),
                player.getY() + 1.0D,
                player.getZ(),
                32,
                0.5D,
                1.0D,
                0.5D,
                0.1D
        );
    }

    // Retain persisted NBT data across player deaths
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath()) {
            return;
        }

        CompoundTag originalTag = event.getOriginal().getPersistentData();
        if (originalTag.contains(PERSISTED_TAG)) {
            event.getEntity().getPersistentData().put(PERSISTED_TAG, originalTag.getCompound(PERSISTED_TAG).copy());
        }
    }

    // Login restriction for Monday through Thursday
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!Config.RESTRICT_DAYS_ENABLED.get()) {
            return;
        }

        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        // Bypass check if the player has operator permission
        if (player.hasPermissions(2)) {
            return;
        }

        // Check current day of week
        DayOfWeek day = LocalDate.now().getDayOfWeek();
        if (day == DayOfWeek.MONDAY || day == DayOfWeek.TUESDAY || day == DayOfWeek.WEDNESDAY || day == DayOfWeek.THURSDAY) {
            player.connection.disconnect(Component.literal("§6§l周一到周四不准玩"));
        }
    }

    // Register server commands
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        // 1. /restrictdays <on|off|status>
        dispatcher.register(
                Commands.literal("restrictdays")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("on").executes(ctx -> {
                            Config.RESTRICT_DAYS_ENABLED.set(true);
                            ctx.getSource().sendSuccess(() -> Component.literal("Weekday join restriction enabled."), true);
                            return 1;
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            Config.RESTRICT_DAYS_ENABLED.set(false);
                            ctx.getSource().sendSuccess(() -> Component.literal("Weekday join restriction disabled."), true);
                            return 1;
                        }))
                        .then(Commands.literal("status").executes(ctx -> {
                            String state = Config.RESTRICT_DAYS_ENABLED.get() ? "ENABLED" : "DISABLED";
                            ctx.getSource().sendSuccess(() -> Component.literal("Weekday join restriction is currently: " + state), false);
                            return 1;
                        }))
        );

        // 2. /tpa <player> OR /tpa <x y z>
        dispatcher.register(
                Commands.literal("tpa")
                        .then(Commands.argument("target", EntityArgument.player()).executes(ctx -> {
                            ServerPlayer sourcePlayer = ctx.getSource().getPlayerOrException();
                            ServerPlayer targetPlayer = EntityArgument.getPlayer(ctx, "target");

                            playTeleportEffects(sourcePlayer);
                            sourcePlayer.teleportTo(
                                    targetPlayer.serverLevel(),
                                    targetPlayer.getX(),
                                    targetPlayer.getY(),
                                    targetPlayer.getZ(),
                                    Collections.emptySet(),
                                    targetPlayer.getYRot(),
                                    targetPlayer.getXRot()
                            );
                            playTeleportEffects(sourcePlayer);

                            sourcePlayer.sendSystemMessage(Component.literal("Teleported to " + targetPlayer.getScoreboardName()));
                            return 1;
                        }))
                        .then(Commands.argument("pos", Vec3Argument.vec3()).executes(ctx -> {
                            ServerPlayer sourcePlayer = ctx.getSource().getPlayerOrException();
                            Coordinates coords = Vec3Argument.getCoordinates(ctx, "pos");
                            Vec3 targetVec = coords.getPosition(ctx.getSource());

                            playTeleportEffects(sourcePlayer);
                            sourcePlayer.teleportTo(
                                    sourcePlayer.serverLevel(),
                                    targetVec.x(),
                                    targetVec.y(),
                                    targetVec.z(),
                                    Collections.emptySet(),
                                    sourcePlayer.getYRot(),
                                    sourcePlayer.getXRot()
                            );
                            playTeleportEffects(sourcePlayer);

                            sourcePlayer.sendSystemMessage(Component.literal(String.format("Teleported to %.1f, %.1f, %.1f", targetVec.x(), targetVec.y(), targetVec.z())));
                            return 1;
                        }))
        );

        // 3. /sethome
        dispatcher.register(
                Commands.literal("sethome").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    CompoundTag persistentData = player.getPersistentData();
                    CompoundTag persisted = persistentData.getCompound(PERSISTED_TAG);

                    CompoundTag homeData = new CompoundTag();
                    homeData.putDouble("x", player.getX());
                    homeData.putDouble("y", player.getY());
                    homeData.putDouble("z", player.getZ());
                    homeData.putString("dim", player.level().dimension().location().toString());

                    persisted.put(HOME_TAG, homeData);
                    persistentData.put(PERSISTED_TAG, persisted);

                    player.sendSystemMessage(Component.literal("Home location set successfully."));
                    return 1;
                })
        );

        // 4. /home
        dispatcher.register(
                Commands.literal("home").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    CompoundTag persistentData = player.getPersistentData();
                    CompoundTag persisted = persistentData.getCompound(PERSISTED_TAG);

                    if (!persisted.contains(HOME_TAG)) {
                        player.sendSystemMessage(Component.literal("You have not set a home yet. Use /sethome."));
                        return 0;
                    }

                    CompoundTag homeData = persisted.getCompound(HOME_TAG);
                    double x = homeData.getDouble("x");
                    double y = homeData.getDouble("y");
                    double z = homeData.getDouble("z");
                    String dimStr = homeData.getString("dim");

                    ResourceLocation dimLocation = ResourceLocation.tryParse(dimStr);
                    ResourceKey<Level> dimKey = dimLocation != null
                            ? ResourceKey.create(Registries.DIMENSION, dimLocation)
                            : Level.OVERWORLD;

                    ServerLevel targetLevel = player.getServer().getLevel(dimKey);
                    if (targetLevel == null) {
                        targetLevel = player.serverLevel();
                    }

                    playTeleportEffects(player);
                    player.teleportTo(
                            targetLevel,
                            x,
                            y,
                            z,
                            Collections.emptySet(),
                            player.getYRot(),
                            player.getXRot()
                    );
                    playTeleportEffects(player);

                    player.sendSystemMessage(Component.literal("Teleported home."));
                    return 1;
                })
        );
    }
}