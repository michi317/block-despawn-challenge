package com.example;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
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

import java.util.*;

public class ExampleMod implements ModInitializer {
    public static final Set<Block> BANNED_BLOCKS = new HashSet<>();
    private static final Map<UUID, Block> LAST_BLOCKS = new HashMap<>();

    // Ausnahmen: Obsidian und Flüssigkeiten/Luft triggern keinen Bann
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
        // 1. Verhindern, dass verbotene Blöcke platziert werden
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof BlockItem blockItem) {
                if (BANNED_BLOCKS.contains(blockItem.getBlock())) {
                    if (!level.isClientSide()) {
                        player.displayClientMessage(Component.literal("§cDieser Block ist bereits verbannt!"), true);
                    }
                    stack.setCount(0);
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });

        // 2. Kontinuierliche Welt- und Inventarprüfung
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickTimer++;

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                // Inventar leeren, falls verbotene Blöcke darin liegen
                for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                    ItemStack stack = player.getInventory().getItem(i);
                    if (stack.getItem() instanceof BlockItem blockItem) {
                        if (BANNED_BLOCKS.contains(blockItem.getBlock())) {
                            stack.setCount(0);
                        }
                    }
                }

                int px = player.getBlockX();
                int py = player.getBlockY();
                int pz = player.getBlockZ();

                // Untergrund prüfen, wenn der Spieler auf dem Boden steht
                if (player.onGround()) {
                    BlockPos underPos = new BlockPos(px, py - 1, pz);
                    BlockState underState = player.level().getBlockState(underPos);
                    Block currentBlock = underState.getBlock();

                    if (!WHITELIST.contains(currentBlock) && !underState.isAir()) {
                        Block lastBlock = LAST_BLOCKS.get(player.getUUID());

                        if (lastBlock == null) {
                            LAST_BLOCKS.put(player.getUUID(), currentBlock);
                        } else if (!lastBlock.equals(currentBlock)) {
                            if (!BANNED_BLOCKS.contains(lastBlock)) {
                                BANNED_BLOCKS.add(lastBlock);
                                server.getPlayerList().broadcastSystemMessage(
                                    Component.literal("§e[Challenge] §c" + lastBlock.getName().getString() + " ist nun für immer verbannt!"),
                                    false
                                );
                            }
                            LAST_BLOCKS.put(player.getUUID(), currentBlock);
                        }
                    }
                }

                // Despawn-Radius um Spieler (alle 5 Ticks)
                if (tickTimer % 5 == 0 && !BANNED_BLOCKS.isEmpty()) {
                    int hRadius = 16;
                    int vRadius = 10;

                    for (int x = -hRadius; x <= hRadius; x++) {
                        for (int y = -vRadius; y <= vRadius; y++) {
                            for (int z = -hRadius; z <= hRadius; z++) {
                                BlockPos checkPos = new BlockPos(px + x, py + y, pz + z);
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
