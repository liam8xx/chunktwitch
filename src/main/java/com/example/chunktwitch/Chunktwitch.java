package com.example.chunktwitch;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Twitch chat controls chunks.
 *   !chunk iron block   /   !chunk block of diamond
 * When the player walks into a chunk nobody has claimed, the next queued
 * request turns every existing block in that chunk into the viewer's block.
 * Air is never changed, bedrock is kept.
 *
 * Connect with:  /chunktwitch connect <yourchannel>
 */
public class Chunktwitch implements ModInitializer {

    record Entry(String user, Block block) {}

    // block names viewers may NOT use (empty = everything allowed)
    static final Set<String> BLOCKED = Set.of();

    static final Pattern CHAT = Pattern.compile("^:(\\w+)!\\S+ PRIVMSG #\\S+ :!chunk\\s+(.+)$");

    // 2 = send to clients, 16 = no neighbour shape updates, 32 = no drops,
    // 512 = skip "on place" logic (stops anvils, sand and gravel from falling)
    static final int PLACE_FLAGS = 2 | 16 | 32 | 512;

    static final ConcurrentLinkedQueue<Entry> QUEUE = new ConcurrentLinkedQueue<>();
    static final Set<String> DONE = new HashSet<>();
    static final Map<String, Block> BLOCKS_BY_NAME = new HashMap<>();
    static final Map<UUID, String> LAST_CHUNK = new HashMap<>();

    static final ServerBossEvent BAR = new ServerBossEvent(
            UUID.randomUUID(),
            Component.literal("0 Chunk(s) waiting!"),
            BossEvent.BossBarColor.PURPLE,
            BossEvent.BossBarOverlay.PROGRESS);

    static volatile int generation = 0;
    static volatile Socket currentSocket = null;
    static Path donePath = null;
    static int lastQueueSize = -1;
    static final Map<String, Integer> SCORES = new HashMap<>();
    static Path scoresPath = null;

    // ================================================================ setup

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("chunktwitch")
                        .then(Commands.literal("connect")
                                .then(Commands.argument("channel", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String channel = StringArgumentType.getString(ctx, "channel");
                                            saveChannel(channel);
                                            startTwitch(channel);
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("Connecting to Twitch chat: #" + channel), false);
                                            return 1;
                                        })))
                        .then(Commands.literal("disconnect")
                                .executes(ctx -> {
                                    generation++;
                                    closeSocket();
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("Disconnected from Twitch chat."), false);
                                    return 1;
                                }))
                        .then(Commands.literal("clear")
                                .executes(ctx -> {
                                    QUEUE.clear();
                                    ctx.getSource().sendSuccess(() -> Component.literal("Queue cleared."), false);
                                    return 1;
                                }))));

        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            generation++;
            closeSocket();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> BAR.addPlayer(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            BAR.removePlayer(handler.player);
            LAST_CHUNK.remove(handler.player.getUUID());
        });

        ServerTickEvents.END_SERVER_TICK.register(this::onTick);
    }

    void onServerStarted(MinecraftServer server) {
        // block name lookup table
        if (BLOCKS_BY_NAME.isEmpty()) {
            for (Block b : BuiltInRegistries.BLOCK) {
                BLOCKS_BY_NAME.put(BuiltInRegistries.BLOCK.getKey(b).getPath(), b);
            }
        }

        // load claimed chunks for this world
        DONE.clear();
        donePath = server.getWorldPath(LevelResource.ROOT).resolve("chunktwitch_done.txt");
        try {
            if (Files.exists(donePath)) DONE.addAll(Files.readAllLines(donePath));
        } catch (IOException e) {
            System.err.println("[ChunkTwitch] could not read done chunks: " + e);
        }

        // scoreboard: create it (fails quietly if it already exists), then show it
        runQuiet(server, "scoreboard objectives add chunks dummy \"Chunks Owned\"");
        runCommand(server, "scoreboard objectives modify chunks displayname \"Chunks Owned\"");
        runQuiet(server, "scoreboard objectives modify chunks numberformat blank"); // hide the number column; the count is in the name now
        runCommand(server, "scoreboard objectives setdisplay sidebar chunks");

        loadScores(server);
        for (Map.Entry<String, Integer> en : SCORES.entrySet()) {
            updateBoard(server, en.getKey(), en.getValue());
        }

        QUEUE.clear();
        lastQueueSize = -1;

        String channel = loadChannel();
        if (channel != null) startTwitch(channel);
    }

    // ================================================================ game logic

    void onTick(MinecraftServer server) {
        int size = QUEUE.size();
        if (size != lastQueueSize) {
            lastQueueSize = size;
            BAR.setName(Component.literal(size + " Chunk(s) waiting!"));
            BAR.setProgress(size == 0 ? 0f : Math.min(1f, size / 10f));
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = (ServerLevel) player.level();
            int cx = player.getBlockX() >> 4;
            int cz = player.getBlockZ() >> 4;
            String key = level.dimension() + ":" + cx + "," + cz;

            // only react when the player moves INTO a different chunk
            String previous = LAST_CHUNK.put(player.getUUID(), key);
            if (previous == null || previous.equals(key)) continue;
            if (DONE.contains(key)) continue;

            Entry e = QUEUE.poll();
            if (e == null) continue;

            fillChunk(level, cx, cz, e.block());
            DONE.add(key);
            appendDone(key);
            int total = SCORES.merge(e.user(), 1, Integer::sum);
            saveScores();
            updateBoard(server, e.user(), total);
            announce(server, player, e.user(), e.block());
        }
    }

    void fillChunk(ServerLevel level, int cx, int cz, Block block) {
        BlockState state = block.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = level.getMinY();
        int maxY = level.getMaxY();

        for (int y = minY; y <= maxY; y++) {
            for (int x = cx * 16; x < cx * 16 + 16; x++) {
                for (int z = cz * 16; z < cz * 16 + 16; z++) {
                    pos.set(x, y, z);
                    BlockState current = level.getBlockState(pos);

                    // air is never changed, in any dimension
                    if (current.isAir()) continue;
                    if (current.is(Blocks.BEDROCK)) continue;

                    Block cb = current.getBlock();
                    boolean solid = current.isCollisionShapeFullBlock(
                            net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                            || cb instanceof net.minecraft.world.level.block.SlabBlock
                            || cb instanceof net.minecraft.world.level.block.StairBlock
                            || cb instanceof net.minecraft.world.level.block.FenceBlock;
                    boolean fluid = !current.getFluidState().isEmpty();

                    // blocks, slabs, stairs, fences and fluids become the viewer's block;
                    // torches, plants, fire, leaf litter etc. vanish
                    level.setBlock(pos, (solid || fluid) ? state : air, PLACE_FLAGS);
                }
            }
        }
    }

    void announce(MinecraftServer server, ServerPlayer player, String user, Block block) {
        var id = BuiltInRegistries.BLOCK.getKey(block);
        String blockKey = "block." + id.getNamespace() + "." + id.getPath();
        String target = player.getName().getString();

        String text = "[{\"text\":\"" + user + "\",\"color\":\"yellow\"},"
                + "{\"text\":\" changed the chunk to \",\"color\":\"white\"},"
                + "{\"translate\":\"" + blockKey + "\",\"color\":\"green\"}]";

        runCommand(server, "title " + target + " actionbar " + text);
        runCommand(server, "execute at " + target + " run playsound minecraft:item.totem.use master "
                + target + " ~ ~ ~ 1 1");
    }

    void runCommand(MinecraftServer server, String command) {
        try {
            server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack());
        } catch (Exception e) {
            String msg = "[ChunkTwitch] command failed: " + command + " -> " + e.getMessage();
            System.err.println(msg);
            server.getPlayerList().broadcastSystemMessage(Component.literal(msg), false);
        }
    }

    void runQuiet(MinecraftServer server, String command) {
        try {
            server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack());
        } catch (Exception ignored) {}
    }

    static String teamName(String user) {
    // short, safe team id (Twitch names can be long)
    return "c" + Integer.toHexString(user.hashCode());
}

void updateBoard(MinecraftServer server, String user, int total) {
    String team = teamName(user);
    runQuiet(server, "team add " + team);
    runQuiet(server, "team join " + team + " " + user);
    runCommand(server, "team modify " + team + " suffix {\"text\":\" - " + total + "\",\"color\":\"red\"}");
    runQuiet(server, "scoreboard players set " + user + " chunks " + total);
}

void loadScores(MinecraftServer server) {
    SCORES.clear();
    scoresPath = server.getWorldPath(LevelResource.ROOT).resolve("chunktwitch_scores.txt");
    try {
        if (Files.exists(scoresPath)) {
            for (String line : Files.readAllLines(scoresPath)) {
                int i = line.lastIndexOf('=');
                if (i <= 0) continue;
                try {
                    SCORES.put(line.substring(0, i), Integer.parseInt(line.substring(i + 1).trim()));
                } catch (NumberFormatException ignored) {}
            }
        }
    } catch (IOException e) {
        System.err.println("[ChunkTwitch] could not read scores: " + e);
    }
}

void saveScores() {
    if (scoresPath == null) return;
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, Integer> en : SCORES.entrySet()) {
        sb.append(en.getKey()).append('=').append(en.getValue()).append('\n');
    }
    try {
        Files.writeString(scoresPath, sb.toString());
    } catch (IOException e) {
        System.err.println("[ChunkTwitch] could not save scores: " + e);
    }
}

    // ================================================================ files

    static Path channelFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("chunktwitch.txt");
    }

    static void saveChannel(String channel) {
        try {
            Files.writeString(channelFile(), channel);
        } catch (IOException e) {
            System.err.println("[ChunkTwitch] could not save channel: " + e);
        }
    }

    static String loadChannel() {
        try {
            if (Files.exists(channelFile())) {
                String s = Files.readString(channelFile()).trim();
                return s.isEmpty() ? null : s;
            }
        } catch (IOException ignored) {}
        return null;
    }

    static void appendDone(String key) {
        if (donePath == null) return;
        try {
            Files.writeString(donePath, key + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[ChunkTwitch] could not save done chunk: " + e);
        }
    }

    // ================================================================ twitch (read-only, anonymous)

    static void closeSocket() {
        Socket s = currentSocket;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    static void startTwitch(String channel) {
        closeSocket();
        final int gen = ++generation;
        final String chan = channel.toLowerCase(Locale.ROOT);

        Thread t = new Thread(() -> {
            while (gen == generation) {
                try (Socket socket = new Socket("irc.chat.twitch.tv", 6667)) {
                    currentSocket = socket;
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    OutputStream out = socket.getOutputStream();
                    send(out, "NICK justinfan" + (10000 + new Random().nextInt(80000)));
                    send(out, "JOIN #" + chan);
                    System.out.println("[ChunkTwitch] connected to #" + chan);

                    String line;
                    while (gen == generation && (line = in.readLine()) != null) {
                        if (line.startsWith("PING")) {
                            send(out, "PONG :tmi.twitch.tv");
                            continue;
                        }
                        Matcher m = CHAT.matcher(line);
                        if (m.matches()) handleChat(m.group(1), m.group(2));
                    }
                } catch (IOException e) {
                    if (gen == generation) System.err.println("[ChunkTwitch] twitch connection lost: " + e);
                }
                if (gen != generation) break;
                try { Thread.sleep(5000); } catch (InterruptedException ignored) { break; }
            }
        }, "chunktwitch-irc");
        t.setDaemon(true);
        t.start();
    }

    static void send(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    static void handleChat(String user, String raw) {
        user = user.toLowerCase(Locale.ROOT);

        // one chunk in the queue per viewer (silently ignored)
        for (Entry q : QUEUE) {
            if (q.user().equals(user)) return;
        }

        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty() || !text.matches("[a-z0-9_ ]+")) return;

        Block block = resolveBlock(text);
        if (block == null) return;

        QUEUE.add(new Entry(user, block));
    }

    static Block resolveBlock(String text) {
        String n = text.replace(' ', '_');
        if (n.startsWith("block_of_")) n = n.substring("block_of_".length()) + "_block";

        for (String candidate : new String[] { n, n + "_block" }) {
            if (BLOCKED.contains(candidate)) return null;
            Block b = BLOCKS_BY_NAME.get(candidate);
            if (b != null) return b;
        }
        return null;
    }
}
