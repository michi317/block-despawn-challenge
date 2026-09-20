package com.example;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;

public class ExampleMod implements ModInitializer {
    // Verbotene Blöcke
    public static final Set<Block> BANNED_BLOCKS = new HashSet<>();
    // Der aktuell für die Gruppe aktive/sichere Block
    public static Block currentSharedBlock = null;

    // Geschützte Blöcke: Verursachen keinen Wechsel und werden nie verbannt
    private static final Set<Block> WHITELIST = Set.of(
        Blocks.OBSIDIAN,
        Blocks.AIR,
        Blocks.CAVE_AIR,
        Blocks.VOID_AIR,
        Blocks.WATER,
        Blocks.LAVA
    );

    private int tickTimer = 0;

    @Override
    public void onInitialize() {
        // Ingame-Befehl: /challenge
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("challenge")
                .executes(context -> {
                    var source = context.getSource();
                    source.sendSuccess(() -> Component.literal("§6=== Challenge Info ==="), false);
                    String current = (currentSharedBlock != null) ? currentSharedBlock.getName().getString() : "Noch keiner";
                    source.sendSuccess(() -> Component.literal("§eAktueller Block: §a" + current), false);
                    source.sendSuccess(() -> Component.literal("§eWhitelist (Sicher): §bObsidian, Wasser, Lava"), false);
                    source.sendSuccess(() -> Component.literal("§eVerbannte Blöcke: §c" + BANNED_BLOCKS.size()), false);
                    return 1;
                })
            );
        });

        // 1. Platzieren von verbannten Blöcken abfangen
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof BlockItem blockItem) {
                if (BANNED_BLOCKS.contains(blockItem.getBlock())) {
                    if (!level.isClientSide()) {
                        player.displayClientMessage(Component.literal("§cDieser Block ist bereits verbannt!"), true);
                    }
                    stack.setCount(0); // Block wird direkt vernichtet
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });

        // 2. Kontinuierlicher Server-Tick
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickTimer++;

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                // Inventare aller Spieler von verbannten Items bereinigen
                for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                    ItemStack stack = player.getInventory().getItem(i);
                    if (stack.getItem() instanceof BlockItem blockItem) {
                        if (BANNED_BLOCKS.contains(blockItem.getBlock())) {
                            stack.setCount(0);
                        }
                    }
                }

                // Nur prüfen, wenn der Spieler wirklich fest auf dem Boden steht (nicht beim Springen)
                if (player.onGround()) {
                    int px = player.getBlockX();
                    int py = player.getBlockY();
                    int pz = player.getBlockZ();

                    BlockPos underPos = new BlockPos(px, py - 1, pz);
                    BlockState underState = player.level().getBlockState(underPos);
                    Block currentBlock = underState.getBlock();

                    if (!WHITELIST.contains(currentBlock) && !underState.isAir()) {
                        if (currentSharedBlock == null) {
                            // Erster Spawn-Block wird zum Startblock
                            currentSharedBlock = currentBlock;
                            server.getPlayerList().broadcastSystemMessage(
                                Component.literal("§a[Challenge gestartet] §eStartblock ist: §f" + currentBlock.getName().getString()),
                                false
                            );
                        } else if (!currentBlock.equals(currentSharedBlock)) {
                            // Ein Spieler hat einen NEUEN Block betreten!
                            Block oldBlock = currentSharedBlock;
                            if (!BANNED_BLOCKS.contains(oldBlock)) {
                                BANNED_BLOCKS.add(oldBlock);
                                server.getPlayerList().broadcastSystemMessage(
                                    Component.literal("§c[Challenge] §e" + player.getName().getString() + " hat gewechselt! §c" 
                                        + oldBlock.getName().getString() + " ist verbannt! §aNeuer Block: " + currentBlock.getName().getString()),
                                    false
                                );
                            }
                            currentSharedBlock = currentBlock;
                        }
                    }
                }

                // Alle 5 Ticks: Verbannte Blöcke um alle Spieler despawnen
                if (tickTimer % 5 == 0 && !BANNED_BLOCKS.isEmpty()) {
                    BlockPos center = player.blockPosition();
                    int hRadius = 16;
                    int vRadius = 10;

                    for (int x = -hRadius; x <= hRadius; x++) {
                        for (int y = -vRadius; y <= vRadius; y++) {
                            for (int z = -hRadius; z <= hRadius; z++) {
                                BlockPos checkPos = center.offset(x, y, z);
                                BlockState state = player.level().getBlockState(checkPos);
                                if (BANNED_BLOCKS.contains(state.getBlock())) {
                                    player.level().setBlock(checkPos, Blocks.AIR.defaultBlockState(), 3);
                                }
                            }
                        }
                    }
                }
            }
        });
    }
}
