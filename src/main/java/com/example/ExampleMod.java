package com.example;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public class ExampleMod implements ModInitializer {
    public static final Set<Block> BANNED_BLOCKS = new HashSet<>();
    public static Block currentSharedBlock = null;
    public static boolean showBroadcasts = true;

    // Veränderbare Whitelist
    public static final Set<Block> WHITELIST = new HashSet<>(Set.of(
        Blocks.OBSIDIAN,
        Blocks.AIR,
        Blocks.CAVE_AIR,
        Blocks.VOID_AIR,
        Blocks.WATER,
        Blocks.LAVA
    ));

    private int tickTimer = 0;
    private static final Random RANDOM = new Random();

    @Override
    public void onInitialize() {
        // Befehle registrieren
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("challenge")
                // /challenge (Info)
                .executes(context -> {
                    var src = context.getSource();
                    src.sendSuccess(() -> Component.literal("§8§m--------------------------------"), false);
                    src.sendSuccess(() -> Component.literal("§8[§6Challenge§8] §eStatus-Übersicht"), false);
                    String current = (currentSharedBlock != null) ? currentSharedBlock.getName().getString() : "Keiner";
                    src.sendSuccess(() -> Component.literal("§8» §7Aktueller Block: §a" + current), false);
                    src.sendSuccess(() -> Component.literal("§8» §7Verbannte Blöcke: §c" + BANNED_BLOCKS.size()), false);
                    src.sendSuccess(() -> Component.literal("§8» §7Wasserstatus: " + (WHITELIST.contains(Blocks.WATER) ? "§aErlaubt" : "§cVerbannt/Gelöscht")), false);
                    src.sendSuccess(() -> Component.literal("§8» §7Nachrichten: " + (showBroadcasts ? "§aAktiviert" : "§cStumm")), false);
                    src.sendSuccess(() -> Component.literal("§8§m--------------------------------"), false);
                    return 1;
                })
                // /challenge menu (Interaktives Klick-Menü)
                .then(Commands.literal("menu").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    openChallengeMenu(player);
                    return 1;
                }))
                // /challenge water (Schnellbefehl)
                .then(Commands.literal("water").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayer();
                    if (player != null) toggleWater((ServerLevel) player.level(), player);
                    return 1;
                }))
                // /challenge lava (Schnellbefehl)
                .then(Commands.literal("lava").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayer();
                    if (player != null) toggleLava((ServerLevel) player.level(), player);
                    return 1;
                }))
                // /challenge messages (Broadcasts umschalten)
                .then(Commands.literal("messages").executes(context -> {
                    showBroadcasts = !showBroadcasts;
                    context.getSource().sendSuccess(() -> Component.literal(
                        "§8[§6Challenge§8] §8» §7Chat-Meldungen: " + (showBroadcasts ? "§aAktiviert" : "§cDeaktiviert")
                    ), false);
                    return 1;
                }))
                // /challenge whitelist (Item in der Hand schützen/entfernen)
                .then(Commands.literal("whitelist").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayer();
                    if (player == null) return 0;

                    ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
                    if (held.getItem() instanceof BlockItem blockItem) {
                        Block b = blockItem.getBlock();
                        if (WHITELIST.contains(b)) {
                            WHITELIST.remove(b);
                            player.sendSystemMessage(Component.literal("§8[§6Challenge§8] §8» §c" + b.getName().getString() + " §7von Whitelist entfernt!"));
                        } else {
                            WHITELIST.add(b);
                            BANNED_BLOCKS.remove(b);
                            player.sendSystemMessage(Component.literal("§8[§6Challenge§8] §8» §a" + b.getName().getString() + " §7zur Whitelist hinzugefügt!"));
                        }
                    } else {
                        player.sendSystemMessage(Component.literal("§8[§6Challenge§8] §8» §cDu musst einen Block in der Hand halten!"));
                    }
                    return 1;
                }))
            );
        });

        // 1. Platzieren blockieren + Flammeneffekt
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof BlockItem blockItem) {
                if (BANNED_BLOCKS.contains(blockItem.getBlock())) {
                    if (level instanceof ServerLevel serverLevel) {
                        BlockPos targetPos = hitResult.getBlockPos().relative(hitResult.getDirection());

                        serverLevel.sendParticles(ParticleTypes.FLAME, targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5, 20, 0.25, 0.25, 0.25, 0.05);
                        serverLevel.sendParticles(ParticleTypes.SMOKE, targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5, 15, 0.2, 0.2, 0.2, 0.02);
                        serverLevel.playSound(null, targetPos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.8f, 1.2f);

                        player.sendSystemMessage(Component.literal("§8[§6Challenge§8] §8» §cDu kannst verbanntes §e" + blockItem.getBlock().getName().getString() + " §cnicht platzieren!"));
                    }

                    if (!player.isCreative()) {
                        stack.shrink(1);
                    }
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });

        // 2. Kontinuierlicher Server-Tick
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickTimer++;

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                // Bodenprüfung (nur bei festem Stand, nicht beim Springen)
                if (player.onGround()) {
                    int px = player.getBlockX();
                    int py = player.getBlockY();
                    int pz = player.getBlockZ();

                    BlockPos underPos = new BlockPos(px, py - 1, pz);
                    BlockState underState = player.level().getBlockState(underPos);
                    Block currentBlock = underState.getBlock();

                    if (!WHITELIST.contains(currentBlock) && !underState.isAir()) {
                        if (currentSharedBlock == null) {
                            currentSharedBlock = currentBlock;
                            if (showBroadcasts) {
                                server.getPlayerList().broadcastSystemMessage(
                                    Component.literal("§8[§6Challenge§8] §8» §aStartblock: §f" + currentBlock.getName().getString()),
                                    false
                                );
                            }
                        } else if (!currentBlock.equals(currentSharedBlock)) {
                            Block oldBlock = currentSharedBlock;
                            if (!BANNED_BLOCKS.contains(oldBlock)) {
                                BANNED_BLOCKS.add(oldBlock);

                                if (showBroadcasts) {
                                    server.getPlayerList().broadcastSystemMessage(
                                        Component.literal("§8[§6Challenge§8] §8» §e" + player.getName().getString() + 
                                            " §7wechselte! §c" + oldBlock.getName().getString() + 
                                            " §7ist verbannt §8» §aNeuer Block: " + currentBlock.getName().getString()),
                                        false
                                    );
                                }

                                // Großflächen-Despawn mit Partikeln & Screen-Shake
                                if (player.level() instanceof ServerLevel serverLevel) {
                                    clearAreaWithEffects(serverLevel, player.blockPosition(), oldBlock, 52);
                                    // Schockwelle ohne Schaden für sanftes Kamera-Wackeln
                                    serverLevel.explode(null, player.getX(), player.getY(), player.getZ(), 0.0F, Level.ExplosionInteraction.NONE);
                                }
                            }
                            currentSharedBlock = currentBlock;
                        }
                    }
                }

                // Laufender Despawn-Radius (32 Blöcke horizontal) alle 6 Ticks
                if (tickTimer % 6 == 0 && !BANNED_BLOCKS.isEmpty()) {
                    int px = player.getBlockX();
                    int py = player.getBlockY();
                    int pz = player.getBlockZ();
                    int hRadius = 32;
                    int vRadius = 14;

                    for (int x = -hRadius; x <= hRadius; x++) {
                        for (int y = -vRadius; y <= vRadius; y++) {
                            for (int z = -hRadius; z <= hRadius; z++) {
                                BlockPos checkPos = new BlockPos(px + x, py + y, pz + z);
                                BlockState state = player.level().getBlockState(checkPos);
                                if (BANNED_BLOCKS.contains(state.getBlock())) {
                                    player.level().setBlock(checkPos, Blocks.AIR.defaultBlockState(), 2);
                                }
                            }
                        }
                    }
                }
            }
        });
    }

    // Interaktives Kisten-Menü öffnen
    private static void openChallengeMenu(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider((syncId, playerInv, p) -> {
            SimpleContainer container = new SimpleContainer(27);
            updateMenuIcons(container);

            return new ChestMenu(MenuType.GENERIC_9x3, syncId, playerInv, container, 3) {
                @Override
                public void clicked(int slotId, int button, ClickType clickType, net.minecraft.world.entity.player.Player clicker) {
                    if (slotId >= 0 && slotId < 27) {
                        ServerPlayer sp = (ServerPlayer) clicker;
                        ServerLevel sl = (ServerLevel) sp.level();

                        if (slotId == 11) { // Wasser Toggle & Purge
                            toggleWater(sl, sp);
                            updateMenuIcons(container);
                        } else if (slotId == 13) { // Lava Toggle & Purge
                            toggleLava(sl, sp);
                            updateMenuIcons(container);
                        } else if (slotId == 15) { // Obsidian Toggle
                            toggleBlockWhitelist(Blocks.OBSIDIAN, sp);
                            updateMenuIcons(container);
                        } else if (slotId == 22) { // Nachrichten Toggle
                            showBroadcasts = !showBroadcasts;
                            sp.sendSystemMessage(Component.literal("§8[§6Challenge§8] §8» §7Chat-Meldungen: " + (showBroadcasts ? "§aAktiviert" : "§cDeaktiviert")));
                            updateMenuIcons(container);
                        }
                        return; // Klick abfangen, Items bleiben im Menü
                    }
                    super.clicked(slotId, button, clickType, clicker);
                }
            };
        }, Component.literal("§8» §6Challenge Menü")));
    }

    private static void updateMenuIcons(SimpleContainer container) {
        container.setItem(11, new ItemStack(Items.WATER_BUCKET));
        container.setItem(13, new ItemStack(Items.LAVA_BUCKET));
        container.setItem(15, new ItemStack(Items.OBSIDIAN));
        container.setItem(22, new ItemStack(showBroadcasts ? Items.LIME_DYE : Items.GRAY_DYE));
    }

    private static void toggleWater(ServerLevel level, ServerPlayer player) {
        if (WHITELIST.contains(Blocks.WATER)) {
            WHITELIST.remove(Blocks.WATER);
            BANNED_BLOCKS.add(Blocks.WATER);
            purgeFluid(level, player.blockPosition(), Blocks.WATER, 64);
            player.sendSystemMessage(Component.literal("§8[§6Challenge§8] §8» §cWasser wurde verbannt und in 64 Blöcken Umkreis verdampft!"));
        } else {
            WHITELIST.add(Blocks.WATER);
            BANNED_BLOCKS.remove(Blocks.WATER);
            player.sendSystemMessage(Component.literal("§8[§6Challenge§8] §8» §aWasser ist nun wieder auf der Whitelist!"));
        }
    }

    private static void toggleLava(ServerLevel level, ServerPlayer player) {
        if (WHITELIST.contains(Blocks.LAVA)) {
            WHITELIST.remove(Blocks.LAVA);
            BANNED_BLOCKS.add(Blocks.LAVA);
            purgeFluid(level, player.blockPosition(), Blocks.LAVA, 64);
            player.sendSystemMessage(Component.literal("§8[§6Challenge§8] §8» §cLava wurde verbannt und in 64 Blöcken Umkreis gelöscht!"));
        } else {
            WHITELIST.add(Blocks.LAVA);
            BANNED_BLOCKS.remove(Blocks.LAVA);
            player.sendSystemMessage(Component.literal("§8[§6Challenge§8] §8» §aLava ist nun wieder auf der Whitelist!"));
        }
    }

    private static void toggleBlockWhitelist(Block block, ServerPlayer player) {
        if (WHITELIST.contains(block)) {
            WHITELIST.remove(block);
            player.sendSystemMessage(Component.literal("§8[§6Challenge§8] §8» §c" + block.getName().getString() + " ist nicht mehr geschützt!"));
        } else {
            WHITELIST.add(block);
            BANNED_BLOCKS.remove(block);
            player.sendSystemMessage(Component.literal("§8[§6Challenge§8] §8» §a" + block.getName().getString() + " ist nun geschützt!"));
        }
    }

    // Flüssigkeiten großflächig verdampfen
    private static void purgeFluid(ServerLevel level, BlockPos center, Block fluidBlock, int radius) {
        int minY = Math.max(level.getMinY(), center.getY() - 30);
        int maxY = Math.min(level.getMaxY(), center.getY() + 40);

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (x * x + z * z <= radius * radius) {
                    for (int y = minY; y <= maxY; y++) {
                        BlockPos pos = new BlockPos(center.getX() + x, y, center.getZ() + z);
                        if (level.getBlockState(pos).is(fluidBlock)) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                }
            }
        }
    }

    // Großflächen-Despawn mit authentischen Block-Abbau-Partikeln
    private static void clearAreaWithEffects(ServerLevel level, BlockPos center, Block targetBlock, int radius) {
        int minY = Math.max(level.getMinY(), center.getY() - 25);
        int maxY = Math.min(level.getMaxY(), center.getY() + 35);

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (x * x + z * z <= radius * radius) {
                    for (int y = minY; y <= maxY; y++) {
                        BlockPos pos = new BlockPos(center.getX() + x, y, center.getZ() + z);
                        BlockState state = level.getBlockState(pos);

                        if (state.is(targetBlock)) {
                            // Im Nahbereich (14 Blöcke) Abbau-Partikel und Geräusche abspielen
                            if (pos.closerThan(center, 14) && RANDOM.nextFloat() < 0.25f) {
                                level.levelEvent(2001, pos, Block.getId(state));
                            }
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                        }
                    }
                }
            }
        }
    }
}
