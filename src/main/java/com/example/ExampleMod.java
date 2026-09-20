package com.example;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;

import java.util.*;

public class ExampleMod implements ModInitializer {
    // Gesperrte Blöcke
    public static final Set<Block> BANNED_BLOCKS = new HashSet<>();
    // Zuletzt betretener Block pro Spieler
    private static final Map<UUID, Block> LAST_BLOCKS = new HashMap<>();

    // Erlaubte Ausnahmen: Auf diesen Blöcken darf man stehen, ohne dass der Vorblock gelöscht wird
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
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            ItemStack stack = player.getStackInHand(hand);
            if (stack.getItem() instanceof BlockItem blockItem) {
                if (BANNED_BLOCKS.contains(blockItem.getBlock())) {
                    if (!world.isClient()) {
                        player.sendMessage(Text.literal("§cDieser Block ist bereits verbannt!"), true);
                    }
                    stack.setCount(0); // Item sofort vernichten
                    return ActionResult.FAIL;
                }
            }
            return ActionResult.PASS;
        });

        // 2. Kontinuierlicher Server-Tick
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickTimer++;

            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                // Inventar bereinigen: Verbotene Items sofort entfernen
                for (int i = 0; i < player.getInventory().size(); i++) {
                    ItemStack stack = player.getInventory().getStack(i);
                    if (stack.getItem() instanceof BlockItem blockItem) {
                        if (BANNED_BLOCKS.contains(blockItem.getBlock())) {
                            stack.setCount(0);
                        }
                    }
                }

                // Prüfen, ob der Spieler auf einem soliden Block steht
                if (player.isOnGround()) {
                    BlockPos underPos = player.getBlockPos().down();
                    BlockState underState = player.getWorld().getBlockState(underPos);
                    Block currentBlock = underState.getBlock();

                    if (!WHITELIST.contains(currentBlock) && !underState.isAir()) {
                        Block lastBlock = LAST_BLOCKS.get(player.getUuid());

                        if (lastBlock == null) {
                            // Erster Spawn-Block wird registriert
                            LAST_BLOCKS.put(player.getUuid(), currentBlock);
                        } else if (!lastBlock.equals(currentBlock)) {
                            // Spieler hat einen neuen Block betreten!
                            if (!BANNED_BLOCKS.contains(lastBlock)) {
                                BANNED_BLOCKS.add(lastBlock);
                                server.getPlayerManager().broadcast(
                                    Text.literal("§e[Challenge] §c" + lastBlock.getName().getString() + " ist nun für immer verbannt!"),
                                    false
                                );
                            }
                            LAST_BLOCKS.put(player.getUuid(), currentBlock);
                        }
                    }
                }

                // Performance-optimiertes Despawnen: Alle 5 Ticks (4x pro Sekunde) im Umkreis löschen
                if (tickTimer % 5 == 0 && !BANNED_BLOCKS.isEmpty()) {
                    BlockPos center = player.getBlockPos();
                    int hRadius = 16;
                    int vRadius = 10;

                    for (int x = -hRadius; x <= hRadius; x++) {
                        for (int y = -vRadius; y <= vRadius; y++) {
                            for (int z = -hRadius; z <= hRadius; z++) {
                                BlockPos checkPos = center.add(x, y, z);
                                BlockState state = player.getWorld().getBlockState(checkPos);
                                if (BANNED_BLOCKS.contains(state.getBlock())) {
                                    player.getWorld().setBlockState(checkPos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                                }
                            }
                        }
                    }
                }
            }
        });
    }
}
