package com.example;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.*;

public class ExampleMod implements ModInitializer {
    public static final Set<Block> BANNED_BLOCKS = new HashSet<>();
    public static boolean showBroadcasts = true;

    // Challenge-Status
    public static boolean isRunning = false;
    public static boolean isPaused = false;
    public static int timerSeconds = 0;
    private static int timerTicks = 0;
    private static int actionbarTicks = 0;

    // Challenge-Modi: Geteilte Herzen & UHC
    public static boolean sharedHearts = false;
    public static float currentSharedHealth = 20.0f;
    private static boolean syncingHealth = false;
    public static boolean uhcMode = false;

    // Tracking pro Spieler
    private static final Map<UUID, Block> PLAYER_CURRENT_BLOCKS = new HashMap<>();

    // Schnelle Schockwellen-Queue
    private static final Queue<ChunkPos> PURGE_QUEUE = new LinkedList<>();
    private static Block purgeTargetBlock = null;
    private static ServerLevel purgeLevel = null;

    // Host-System
    public static final Set<UUID> HOSTS = new HashSet<>();

    // Whitelist
    public static final Set<Block> WHITELIST = new HashSet<>(Set.of(
        Blocks.OBSIDIAN,
        Blocks.AIR,
        Blocks.CAVE_AIR,
        Blocks.VOID_AIR,
        Blocks.WATER,
        Blocks.LAVA
    ));

    private static final Random RANDOM = new Random();

    public static boolean isHost(ServerPlayer player) {
        if (HOSTS.isEmpty()) {
            HOSTS.add(player.getUUID());
            return true;
        }
        return HOSTS.contains(player.getUUID());
    }

    public static Component createGradient(String text, int startRgb, int endRgb, boolean bold) {
        MutableComponent comp = Component.empty();
        int len = text.length();
        if (len <= 1) {
            return Component.literal(text).setStyle(Style.EMPTY.withColor(TextColor.fromRgb(startRgb)).withBold(bold));
        }

        int r1 = (startRgb >> 16) & 0xFF, g1 = (startRgb >> 8) & 0xFF, b1 = startRgb & 0xFF;
        int r2 = (endRgb >> 16) & 0xFF, g2 = (endRgb >> 8) & 0xFF, b2 = endRgb & 0xFF;

        for (int i = 0; i < len; i++) {
            float ratio = (float) i / (float) (len - 1);
            int r = (int) (r1 + ratio * (r2 - r1));
            int g = (int) (g1 + ratio * (g2 - g1));
            int b = (int) (b1 + ratio * (b2 - b1));
            int rgb = (r << 16) | (g << 8) | b;
            comp.append(Component.literal(String.valueOf(text.charAt(i)))
                    .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)).withBold(bold)));
        }
        return comp;
    }

    public static final Component PREFIX = Component.empty()
        .append(createGradient("Challenge", 0xFF3838, 0xFFA800, true))
        .append(Component.literal(" §8» "));

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("challenge")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayer();
                    if (player != null && isHost(player)) {
                        openChallengeMenu(player);
                    } else {
                        var src = context.getSource();
                        src.sendSuccess(() -> Component.empty().append(PREFIX).append(Component.literal("§7Status: " + (isRunning ? (isPaused ? "§ePausiert" : "§aLäuft") : "§cNicht aktiv"))), false);
                        src.sendSuccess(() -> Component.empty().append(PREFIX).append(Component.literal("§7Verbannte Blöcke: §c" + BANNED_BLOCKS.size())), false);
                        src.sendSuccess(() -> Component.empty().append(PREFIX).append(Component.literal("§7Geteilte Herzen: " + (sharedHearts ? "§aAktiviert" : "§cDeaktiviert"))), false);
                        src.sendSuccess(() -> Component.empty().append(PREFIX).append(Component.literal("§7UHC-Modus: " + (uhcMode ? "§aAktiviert" : "§cDeaktiviert"))), false);
                    }
                    return 1;
                })
                .then(Commands.literal("menu").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    if (!isHost(player)) {
                        player.sendSystemMessage(Component.empty().append(PREFIX).append(Component.literal("§cNur der Host darf Einstellungen verändern!")));
                        return 0;
                    }
                    openChallengeMenu(player);
                    return 1;
                }))
                .then(Commands.literal("start").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayer();
                    if (player != null && !isHost(player)) {
                        player.sendSystemMessage(Component.empty().append(PREFIX).append(Component.literal("§cNur der Host darf Einstellungen verändern!")));
                        return 0;
                    }
                    if (player != null) {
                        startChallengeWithHost(player, context.getSource().getServer());
                    }
                    return 1;
                }))
                .then(Commands.literal("pause").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayer();
                    if (player != null && !isHost(player)) {
                        player.sendSystemMessage(Component.empty().append(PREFIX).append(Component.literal("§cNur der Host darf Einstellungen verändern!")));
                        return 0;
                    }
                    if (isRunning) {
                        isPaused = !isPaused;
                        String status = isPaused ? "§eChallenge pausiert!" : "§aChallenge fortgesetzt!";
                        context.getSource().sendSuccess(() -> Component.empty().append(PREFIX).append(Component.literal(status)), false);
                    }
                    return 1;
                }))
                .then(Commands.literal("whitelist").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayer();
                    if (player == null) return 0;
                    if (!isHost(player)) {
                        player.sendSystemMessage(Component.empty().append(PREFIX).append(Component.literal("§cNur der Host darf Blöcke whitelisten!")));
                        return 0;
                    }

                    ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
                    if (held.getItem() instanceof BlockItem blockItem) {
                        Block b = blockItem.getBlock();
                        if (WHITELIST.contains(b)) {
                            WHITELIST.remove(b);
                            player.sendSystemMessage(Component.empty().append(PREFIX).append(Component.literal("§c" + b.getName().getString() + " §7von der Whitelist entfernt!")));
                        } else {
                            WHITELIST.add(b);
                            BANNED_BLOCKS.remove(b);
                            player.sendSystemMessage(Component.empty().append(PREFIX).append(Component.literal("§a" + b.getName().getString() + " §7zur Whitelist hinzugefügt!")));
                        }
                    } else {
                        player.sendSystemMessage(Component.empty().append(PREFIX).append(Component.literal("§cHalte einen Block in der Hand!")));
                    }
                    return 1;
                }))
            );
        });

        // 1. Ghost-Item freies Platzieren verhinderter Blöcke
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof BlockItem blockItem) {
                if (BANNED_BLOCKS.contains(blockItem.getBlock())) {
                    if (level instanceof ServerLevel serverLevel) {
                        BlockPos targetPos = hitResult.getBlockPos().relative(hitResult.getDirection());

                        serverLevel.playSound(null, targetPos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.9f, 1.1f);
                        serverLevel.sendParticles(ParticleTypes.LAVA, targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5, 6, 0.2, 0.2, 0.2, 0.05);
                        serverLevel.sendParticles(ParticleTypes.FLAME, targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5, 14, 0.25, 0.25, 0.25, 0.05);
                        serverLevel.sendParticles(ParticleTypes.SMOKE, targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5, 10, 0.2, 0.2, 0.2, 0.02);

                        player.sendSystemMessage(Component.empty().append(PREFIX).append(Component.literal("§c" + blockItem.getBlock().getName().getString() + " §7ist verbannt!")));
                    }

                    if (!player.isCreative()) {
                        stack.shrink(1);
                        if (player instanceof ServerPlayer sp) {
                            sp.containerMenu.broadcastChanges();
                            sp.inventoryMenu.broadcastChanges();
                        }
                    }
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });

        // 2. Kontinuierlicher Server-Tick
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (HOSTS.isEmpty() && !server.getPlayerList().getPlayers().isEmpty()) {
                HOSTS.add(server.getPlayerList().getPlayers().get(0).getUUID());
            }

            if (isRunning && !isPaused) {
                timerTicks++;
                if (timerTicks >= 20) {
                    timerTicks = 0;
                    timerSeconds++;
                }

                // Geteilte Herzen synchronisieren
                if (sharedHearts && !syncingHealth) {
                    handleSharedHearts(server);
                }
            }

            actionbarTicks++;
            if (actionbarTicks >= 5) {
                actionbarTicks = 0;
                updateActionBar(server);
            }

            // Schnelle Schockwellen-Verarbeitung
            if (!PURGE_QUEUE.isEmpty() && purgeLevel != null && purgeTargetBlock != null) {
                for (int i = 0; i < 16 && !PURGE_QUEUE.isEmpty(); i++) {
                    ChunkPos cp = PURGE_QUEUE.poll();
                    LevelChunk chunk = purgeLevel.getChunkSource().getChunk(cp.x(), cp.z(), false);
                    if (chunk != null) {
                        clearChunkDirect(purgeLevel, chunk, purgeTargetBlock);
                    }
                }
            }

            if (!isRunning || isPaused) return;

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.onGround()) {
                    Block currentBlock = getBlockUnderPlayer(player);

                    if (currentBlock != null && !WHITELIST.contains(currentBlock)) {
                        Block lastBlock = PLAYER_CURRENT_BLOCKS.get(player.getUUID());

                        if (lastBlock == null) {
                            PLAYER_CURRENT_BLOCKS.put(player.getUUID(), currentBlock);
                        } else if (!currentBlock.equals(lastBlock)) {
                            if (!WHITELIST.contains(lastBlock) && !BANNED_BLOCKS.contains(lastBlock)) {
                                BANNED_BLOCKS.add(lastBlock);

                                if (showBroadcasts) {
                                    server.getPlayerList().broadcastSystemMessage(
                                        Component.empty().append(PREFIX)
                                            .append(Component.literal("§f" + player.getName().getString() + " §7hat §a" + currentBlock.getName().getString()))
                                            .append(Component.literal(" §7betreten §8— §c" + lastBlock.getName().getString() + " §7wurde verbannt!")),
                                        false
                                    );
                                }

                                if (player.level() instanceof ServerLevel serverLevel) {
                                    BlockPos pPos = player.blockPosition();
                                    for (int dx = -5; dx <= 5; dx++) {
                                        for (int dy = -3; dy <= 3; dy++) {
                                            for (int dz = -5; dz <= 5; dz++) {
                                                BlockPos nearPos = pPos.offset(dx, dy, dz);
                                                if (lastBlock.equals(serverLevel.getBlockState(nearPos).getBlock()) && RANDOM.nextFloat() < 0.20f) {
                                                    serverLevel.levelEvent(2001, nearPos, Block.getId(lastBlock.defaultBlockState()));
                                                }
                                            }
                                        }
                                    }

                                    startFastRadialPurge(serverLevel, player.chunkPosition(), lastBlock, 16);
                                }
                            }
                            PLAYER_CURRENT_BLOCKS.put(player.getUUID(), currentBlock);
                        }
                    }
                }
            }
        });
    }

    private static void handleSharedHearts(net.minecraft.server.MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;

        float minHealth = currentSharedHealth;
        boolean damageTaken = false;

        for (ServerPlayer p : players) {
            if (p.isAlive() && p.getHealth() < currentSharedHealth) {
                if (p.getHealth() < minHealth) {
                    minHealth = p.getHealth();
                }
                damageTaken = true;
            }
        }

        if (damageTaken) {
            currentSharedHealth = Math.max(0.0f, minHealth);
            syncingHealth = true;
            for (ServerPlayer p : players) {
                if (p.isAlive()) {
                    p.setHealth(currentSharedHealth);
                    if (currentSharedHealth <= 0.0f) {
                        p.hurt(p.damageSources().generic(), Float.MAX_VALUE);
                    }
                }
            }
            syncingHealth = false;
        } else {
            float maxHealth = currentSharedHealth;
            boolean healed = false;

            for (ServerPlayer p : players) {
                if (p.isAlive() && p.getHealth() > currentSharedHealth) {
                    if (p.getHealth() > maxHealth) {
                        maxHealth = p.getHealth();
                    }
                    healed = true;
                }
            }

            if (healed) {
                currentSharedHealth = Math.min(20.0f, maxHealth);
                syncingHealth = true;
                for (ServerPlayer p : players) {
                    if (p.isAlive()) {
                        p.setHealth(currentSharedHealth);
                    }
                }
                syncingHealth = false;
            }
        }

        boolean anyAlive = false;
        for (ServerPlayer p : players) {
            if (p.isAlive()) {
                anyAlive = true;
                if (currentSharedHealth <= 0.0f && p.getHealth() > 0.0f) {
                    currentSharedHealth = p.getHealth();
                }
                break;
            }
        }
        if (!anyAlive) {
            currentSharedHealth = 20.0f;
        }
    }

    private static Block getBlockUnderPlayer(ServerPlayer player) {
        BlockPos pPos = player.blockPosition();
        BlockState state = player.level().getBlockState(pPos.below());
        if (!state.isAir()) return state.getBlock();

        BlockState deep = player.level().getBlockState(pPos.below(2));
        if (!deep.isAir()) return deep.getBlock();

        return null;
    }

    public static void startChallengeWithHost(ServerPlayer host, net.minecraft.server.MinecraftServer server) {
        if (host == null || server == null) return;
        isRunning = true;
        isPaused = false;

        Block hostBlock = getBlockUnderPlayer(host);
        if (hostBlock == null || WHITELIST.contains(hostBlock)) {
            hostBlock = Blocks.GRASS_BLOCK;
        }

        PLAYER_CURRENT_BLOCKS.clear();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            PLAYER_CURRENT_BLOCKS.put(p.getUUID(), hostBlock);
        }

        currentSharedHealth = host.getHealth();

        server.getPlayerList().broadcastSystemMessage(
            Component.empty().append(PREFIX).append(Component.literal("§aChallenge gestartet! §7Startblock: §f§l" + hostBlock.getName().getString())),
            false
        );
    }

    private static void updateActionBar(net.minecraft.server.MinecraftServer server) {
        int s = timerSeconds % 60;
        int m = (timerSeconds / 60) % 60;
        int h = timerSeconds / 3600;
        String timeStr = (h > 0) ? String.format("%02d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Component actionText;
            if (!isRunning) {
                actionText = Component.literal("§7§oChallenge nicht gestartet §8(/challenge)");
            } else if (isPaused) {
                actionText = Component.literal("§7§oTimer pausiert §8(§e" + timeStr + "§8)");
            } else {
                Block myBlock = PLAYER_CURRENT_BLOCKS.get(player.getUUID());
                String bName = (myBlock != null) ? myBlock.getName().getString() : "Warten...";
                actionText = Component.literal("§e§l" + timeStr + " §8| §7Block: §a§l" + bName);
            }

            if (player.connection != null) {
                player.connection.send(new ClientboundSetActionBarTextPacket(actionText));
            }
        }
    }

    private static void openChallengeMenu(ServerPlayer player) {
        Component menuTitle = Component.empty().append(createGradient("Challenge Menü", 0xFF3838, 0xFFA800, true));

        player.openMenu(new SimpleMenuProvider((syncId, playerInv, p) -> {
            SimpleContainer container = new SimpleContainer(27);
            updateMenuIcons(container);

            return new ChestMenu(MenuType.GENERIC_9x3, syncId, playerInv, container, 3) {
                @Override
                public void clicked(int slotId, int button, ContainerInput containerInput, Player clicker) {
                    if (slotId >= 0 && slotId < 27) {
                        ServerPlayer sp = (ServerPlayer) clicker;
                        if (!isHost(sp)) return;

                        ServerLevel sl = (ServerLevel) sp.level();

                        if (slotId == 4) {
                            if (!isRunning) {
                                startChallengeWithHost(sp, sl.getServer());
                            } else {
                                isPaused = !isPaused;
                                sp.sendSystemMessage(Component.empty().append(PREFIX).append(Component.literal(isPaused ? "§eChallenge pausiert!" : "§aChallenge fortgesetzt!")));
                            }
                            updateMenuIcons(container);
                        } else if (slotId == 10) {
                            toggleWater(sl, sp);
                            updateMenuIcons(container);
                        } else if (slotId == 11) {
                            toggleSharedHearts(sl.getServer(), sp);
                            updateMenuIcons(container);
                        } else if (slotId == 12) {
                            toggleLava(sl, sp);
                            updateMenuIcons(container);
                        } else if (slotId == 13) {
                            toggleUhc(sl, sp);
                            updateMenuIcons(container);
                        } else if (slotId == 14) {
                            toggleBlockWhitelist(Blocks.OBSIDIAN, sp);
                            updateMenuIcons(container);
                        } else if (slotId == 16) {
                            openWhitelistMenu(sp);
                        } else if (slotId == 22) {
                            showBroadcasts = !showBroadcasts;
                            sp.sendSystemMessage(Component.empty().append(PREFIX).append(Component.literal("§7Chat-Meldungen: " + (showBroadcasts ? "§aAktiviert" : "§cDeaktiviert"))));
                            updateMenuIcons(container);
                        }
                        return;
                    }
                    super.clicked(slotId, button, containerInput, clicker);
                }
            };
        }, menuTitle));
    }

    private static void updateMenuIcons(SimpleContainer container) {
        ItemStack ctrlItem;
        if (!isRunning) {
            ctrlItem = new ItemStack(Items.CLOCK);
            ctrlItem.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lChallenge starten"));
            ctrlItem.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("§7Nimmt den Block unter dem Host"),
                Component.literal(""),
                Component.literal("§8» §eKlick: Challenge & Timer starten!")
            )));
        } else if (isPaused) {
            ctrlItem = new ItemStack(Items.REPEATER);
            ctrlItem.set(DataComponents.CUSTOM_NAME, Component.literal("§a§lChallenge fortsetzen"));
            ctrlItem.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("§7Status: §ePausiert"),
                Component.literal(""),
                Component.literal("§8» §eKlick: Fortsetzen")
            )));
        } else {
            ctrlItem = new ItemStack(Items.REDSTONE_TORCH);
            ctrlItem.set(DataComponents.CUSTOM_NAME, Component.literal("§e§lChallenge pausieren"));
            ctrlItem.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("§7Status: §aAktiv & läuft"),
                Component.literal(""),
                Component.literal("§8» §eKlick: Pausieren")
            )));
        }
        container.setItem(4, ctrlItem);

        boolean waterOk = WHITELIST.contains(Blocks.WATER);
        ItemStack waterItem = new ItemStack(Items.WATER_BUCKET);
        waterItem.set(DataComponents.CUSTOM_NAME, Component.literal("§b§lWasser-System"));
        waterItem.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("§7Status: " + (waterOk ? "§a§lErlaubt" : "§c§lVerbannt")),
            Component.literal(""),
            Component.literal("§8» §eKlick: " + (waterOk ? "§cIn allen Chunks löschen & verbannen" : "§aWieder zur Whitelist hinzufügen"))
        )));
        container.setItem(10, waterItem);

        ItemStack heartsItem = new ItemStack(Items.GOLDEN_APPLE);
        heartsItem.set(DataComponents.CUSTOM_NAME, Component.literal(sharedHearts ? "§c§lGeteilte Herzen: AN" : "§7§lGeteilte Herzen: AUS"));
        heartsItem.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("§7Status: " + (sharedHearts ? "§aAktiviert" : "§cDeaktiviert")),
            Component.literal(""),
            Component.literal("§8» §7Nimmt ein Spieler Schaden, verlieren alle dieselben Herzen."),
            Component.literal("§8» §eKlick: " + (sharedHearts ? "§cDeaktivieren" : "§aAktivieren"))
        )));
        container.setItem(11, heartsItem);

        boolean lavaOk = WHITELIST.contains(Blocks.LAVA);
        ItemStack lavaItem = new ItemStack(Items.LAVA_BUCKET);
        lavaItem.set(DataComponents.CUSTOM_NAME, Component.literal("§6§lLava-System"));
        lavaItem.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("§7Status: " + (lavaOk ? "§a§lErlaubt" : "§c§lVerbannt")),
            Component.literal(""),
            Component.literal("§8» §eKlick: " + (lavaOk ? "§cIn allen Chunks löschen & verbannen" : "§aWieder zur Whitelist hinzufügen"))
        )));
        container.setItem(12, lavaItem);

        ItemStack uhcItem = new ItemStack(Items.GOLDEN_CARROT);
        uhcItem.set(DataComponents.CUSTOM_NAME, Component.literal(uhcMode ? "§6§lUltra Hardcore (UHC): AN" : "§7§lUltra Hardcore (UHC): AUS"));
        uhcItem.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("§7Status: " + (uhcMode ? "§aAktiviert" : "§cDeaktiviert")),
            Component.literal(""),
            Component.literal("§8» §7Keine natürliche Lebensregeneration durch Essen."),
            Component.literal("§8» §7Heilung nur über Tränke, Goldäpfel etc."),
            Component.literal("§8» §eKlick: " + (uhcMode ? "§cDeaktivieren" : "§aAktivieren"))
        )));
        container.setItem(13, uhcItem);

        boolean obsOk = WHITELIST.contains(Blocks.OBSIDIAN);
        ItemStack obsItem = new ItemStack(Blocks.OBSIDIAN.asItem());
        obsItem.set(DataComponents.CUSTOM_NAME, Component.literal("§5§lObsidian-Schutz"));
        obsItem.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("§7Status: " + (obsOk ? "§a§lGeschützt" : "§c§lNicht geschützt")),
            Component.literal(""),
            Component.literal("§8» §eKlick: " + (obsOk ? "§cSchutz aufheben" : "§aSchutz aktivieren"))
        )));
        container.setItem(14, obsItem);

        ItemStack bookItem = new ItemStack(Items.BOOK);
        bookItem.set(DataComponents.CUSTOM_NAME, Component.literal("§d§lWhitelist Übersicht"));
        bookItem.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("§7Aktuell geschützt: §e" + WHITELIST.size() + " Blöcke"),
            Component.literal(""),
            Component.literal("§8» §eKlick: Blöcke ansehen & per Klick entfernen")
        )));
        container.setItem(16, bookItem);

        ItemStack msgItem = new ItemStack(Items.NAME_TAG);
        msgItem.set(DataComponents.CUSTOM_NAME, Component.literal(showBroadcasts ? "§a§lChat-Meldungen: AN" : "§c§lChat-Meldungen: AUS"));
        msgItem.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("§7Status: " + (showBroadcasts ? "§aAktiviert" : "§cStumm")),
            Component.literal(""),
            Component.literal("§8» §eKlick: " + (showBroadcasts ? "§7Stummschalten" : "§7Aktivieren"))
        )));
        container.setItem(22, msgItem);
    }

    private static void toggleSharedHearts(net.minecraft.server.MinecraftServer server, ServerPlayer player) {
        sharedHearts = !sharedHearts;
        if (sharedHearts) {
            currentSharedHealth = player.getHealth();
            syncingHealth = true;
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.setHealth(currentSharedHealth);
            }
            syncingHealth = false;
            server.getPlayerList().broadcastSystemMessage(
                Component.empty().append(PREFIX).append(Component.literal("§c§lGeteilte Herzen: §aAktiviert §8(Alle teilen dieselben Herzen!)")),
                false
            );
        } else {
            server.getPlayerList().broadcastSystemMessage(
                Component.empty().append(PREFIX).append(Component.literal("§c§lGeteilte Herzen: §cDeaktiviert")),
                false
            );
        }
    }

    private static void toggleUhc(ServerLevel level, ServerPlayer player) {
        uhcMode = !uhcMode;
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamerule naturalRegeneration " + (!uhcMode));
        server.getPlayerList().broadcastSystemMessage(
            Component.empty().append(PREFIX).append(Component.literal("§6§lUltra Hardcore (UHC): " + 
                (uhcMode ? "§aAktiviert §8(Keine natürliche Regeneration!)" : "§cDeaktiviert"))),
            false
        );
    }

    private static void openWhitelistMenu(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider((syncId, playerInv, p) -> {
            SimpleContainer container = new SimpleContainer(27);
            List<Block> list = new ArrayList<>();
            for (Block b : WHITELIST) {
                if (b.asItem() != Items.AIR) list.add(b);
            }

            for (int i = 0; i < Math.min(list.size(), 26); i++) {
                Block b = list.get(i);
                ItemStack it = new ItemStack(b.asItem());
                it.set(DataComponents.CUSTOM_NAME, Component.literal("§a" + b.getName().getString()));
                it.set(DataComponents.LORE, new ItemLore(List.of(
                    Component.literal("§7Dieser Block ist geschützt."),
                    Component.literal(""),
                    Component.literal("§8» §cKlick: Von Whitelist entfernen!")
                )));
                container.setItem(i, it);
            }

            ItemStack backItem = new ItemStack(Items.BARRIER);
            backItem.set(DataComponents.CUSTOM_NAME, Component.literal("§c« Zurück zum Hauptmenü"));
            container.setItem(26, backItem);

            return new ChestMenu(MenuType.GENERIC_9x3, syncId, playerInv, container, 3) {
                @Override
                public void clicked(int slotId, int button, ContainerInput containerInput, Player clicker) {
                    if (slotId >= 0 && slotId < 27) {
                        ServerPlayer sp = (ServerPlayer) clicker;
                        if (!isHost(sp)) return;

                        if (slotId == 26) {
                            openChallengeMenu(sp);
                            return;
                        }

                        if (slotId < list.size()) {
                            Block removeBlock = list.get(slotId);
                            WHITELIST.remove(removeBlock);
                            sp.sendSystemMessage(Component.empty().append(PREFIX).append(Component.literal("§c" + removeBlock.getName().getString() + " §7von der Whitelist entfernt!")));
                            openWhitelistMenu(sp);
                        }
                        return;
                    }
                    super.clicked(slotId, button, containerInput, clicker);
                }
            };
        }, Component.literal("§8» §dWhitelist Blöcke")));
    }

    private static void toggleWater(ServerLevel level, ServerPlayer player) {
        if (WHITELIST.contains(Blocks.WATER)) {
            WHITELIST.remove(Blocks.WATER);
            BANNED_BLOCKS.add(Blocks.WATER);
            startFastRadialPurge(level, player.chunkPosition(), Blocks.WATER, 16);
            player.sendSystemMessage(Component.empty().append(PREFIX).append(Component.literal("§cWasser verbannt — Despawn-Welle gestartet!")));
        } else {
            WHITELIST.add(Blocks.WATER);
            BANNED_BLOCKS.remove(Blocks.WATER);
            player.sendSystemMessage(Component.empty().append(PREFIX).append(Component.literal("§aWasser ist wieder auf der Whitelist!")));
        }
    }

    private static void toggleLava(ServerLevel level, ServerPlayer player) {
        if (WHITELIST.contains(Blocks.LAVA)) {
            WHITELIST.remove(Blocks.LAVA);
            BANNED_BLOCKS.add(Blocks.LAVA);
            startFastRadialPurge(level, player.chunkPosition(), Blocks.LAVA, 16);
            player.sendSystemMessage(Component.empty().append(PREFIX).append(Component.literal("§cLava verbannt — Despawn-Welle gestartet!")));
        } else {
            WHITELIST.add(Blocks.LAVA);
            BANNED_BLOCKS.remove(Blocks.LAVA);
            player.sendSystemMessage(Component.empty().append(PREFIX).append(Component.literal("§aLava ist wieder auf der Whitelist!")));
        }
    }

    private static void toggleBlockWhitelist(Block block, ServerPlayer player) {
        if (WHITELIST.contains(block)) {
            WHITELIST.remove(block);
            player.sendSystemMessage(Component.empty().append(PREFIX).append(Component.literal("§c" + block.getName().getString() + " ist nicht mehr geschützt!")));
        } else {
            WHITELIST.add(block);
            BANNED_BLOCKS.remove(block);
            player.sendSystemMessage(Component.empty().append(PREFIX).append(Component.literal("§a" + block.getName().getString() + " ist nun geschützt!")));
        }
    }

    private static void startFastRadialPurge(ServerLevel level, ChunkPos center, Block targetBlock, int radius) {
        List<ChunkPos> chunks = new ArrayList<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz <= radius * radius) {
                    ChunkPos cp = new ChunkPos(center.x() + dx, center.z() + dz);
                    if (Math.abs(dx) <= 2 && Math.abs(dz) <= 2) {
                        LevelChunk c = level.getChunkSource().getChunk(cp.x(), cp.z(), false);
                        if (c != null) clearChunkDirect(level, c, targetBlock);
                    } else {
                        chunks.add(cp);
                    }
                }
            }
        }

        chunks.sort(Comparator.comparingInt(cp -> {
            int ox = cp.x() - center.x();
            int oz = cp.z() - center.z();
            return ox * ox + oz * oz;
        }));

        PURGE_QUEUE.clear();
        PURGE_QUEUE.addAll(chunks);
        purgeTargetBlock = targetBlock;
        purgeLevel = level;
    }

    private static void clearChunkDirect(ServerLevel level, LevelChunk chunk, Block specificBlock) {
        LevelChunkSection[] sections = chunk.getSections();
        if (sections == null) return;

        BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        int minSectionY = level.getMinSectionY();

        for (int sIndex = 0; sIndex < sections.length; sIndex++) {
            LevelChunkSection section = sections[sIndex];
            if (section == null || section.hasOnlyAir()) continue;

            if (!section.maybeHas(state -> (specificBlock != null) ? state.is(specificBlock) : BANNED_BLOCKS.contains(state.getBlock()))) {
                continue;
            }

            int sectionBottomY = (minSectionY + sIndex) * 16;

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 0; y < 16; y++) {
                        BlockState state = section.getBlockState(x, y, z);
                        Block b = state.getBlock();

                        boolean shouldDelete = (specificBlock != null) ? (b == specificBlock) : BANNED_BLOCKS.contains(b);

                        if (shouldDelete) {
                            int worldY = sectionBottomY + y;
                            mPos.set(startX + x, worldY, startZ + z);
                            level.setBlock(mPos, Blocks.AIR.defaultBlockState(), 2 | 16);
                        }
                    }
                }
            }
        }
    }
}
