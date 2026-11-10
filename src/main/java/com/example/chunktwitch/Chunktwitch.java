package com.example.chunktwitch;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

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
 * request fills that chunk (bottom of the world up to the player's feet).
 *
 * Connect with:  /chunktwitch connect <yourchannel>
 */
public class Chunktwitch implements ModInitializer {

    record Entry(String user, Block block) {}

static final Set<String> BLOCKED = Set.of();

    static final Pattern CHAT = Pattern.compile("^:(\\w+)!\\S+ PRIVMSG #\\S+ :!chunk\\s+(.+)$");

    static final ConcurrentLinkedQueue<Entry> QUEUE = new ConcurrentLinkedQueue<>();
    static final Set<String> DONE = new HashSet<>();
    static final Map<String, Block> BLOCKS_BY_NAME = new HashMap<>();

static final ServerBossEvent BAR = new ServerBossEvent(
        java.util.UUID.randomUUID(),
        Component.literal("0 Chunk(s) waiting!"),
        BossEvent.BossBarColor.PURPLE,
        BossEvent.BossBarOverlay.PROGRESS);

    static volatile int generation = 0;
    static volatile Socket currentSocket = null;
    static Path donePath = null;
    static int lastQueueSize = -1;
    static final Map<UUID, String> LAST_CHUNK = new HashMap<>();
    static final int PLACE_FLAGS = 2 | 16 | 32 | 512;

    @Override
    public void onInitialize() {
        // /chunktwitch connect <channel>
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

    // ---------------------------------------------------------------- server

    void onServerStarted(MinecraftServer server) {
        runCommand(server, "scoreboard objectives modify chunks numberformat styled {\"color\":\"red\"}");
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

        // scoreboard (errors if it already exists, which is fine)
        runQuiet(server, "scoreboard objectives add chunks dummy \"Chunks Owned\"");
        runCommand(server, "scoreboard objectives modify chunks displayname \"Chunks Owned\"");
        runCommand(server, "scoreboard objectives setdisplay sidebar chunks");

        QUEUE.clear();
        lastQueueSize = -1;

        // reconnect to the saved channel
        String channel = loadChannel();
        if (channel != null) startTwitch(channel);
    }
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

        String previous = LAST_CHUNK.put(player.getUUID(), key);
        if (previous == null || previous.equals(key)) continue;
        if (DONE.contains(key)) continue;

        Entry e = QUEUE.poll();
        if (e == null) continue;

        // only the Overworld lifts the player to the surface
        boolean overworld = level.dimension().equals(Level.OVERWORLD);
        int surfaceAtPlayer = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, player.getBlockX(), player.getBlockZ());
        boolean underground = overworld && player.getBlockY() < surfaceAtPlayer;

        fillChunk(level, cx, cz, e.block());
        DONE.add(key);
        appendDone(key);
        runCommand(server, "scoreboard players add " + e.user() + " chunks 1");

        if (underground) liftToSurface(server, level, player, surfaceAtPlayer);
        announce(server, player, e.user(), e.block());
    }
}
void liftToSurface(MinecraftServer server, ServerLevel level, ServerPlayer player, int surface) {
    int x = player.getBlockX();
    int z = player.getBlockZ();
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    int y = Math.max(surface, level.getMinY());
    int maxY = level.getMaxY();
    // go up until there are two free blocks (feet + head), so trees don't trap you
    while (y < maxY) {
        boolean feet = level.getBlockState(pos.set(x, y, z)).isAir();
        boolean head = level.getBlockState(pos.set(x, y + 1, z)).isAir();
        if (feet && head) break;
        y++;
    }
    // same exact x and z, only y changes
    runCommand(server, "tp " + player.getUUID() + " " + fmt(player.getX()) + " " + y + " " + fmt(player.getZ()));
}

static String fmt(double d) {
    return String.format(Locale.ROOT, "%.3f", d);
}
void fillChunk(ServerLevel level, int cx, int cz, Block block) {
    BlockState state = block.defaultBlockState();
    BlockState air = Blocks.AIR.defaultBlockState();
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    int minY = level.getMinY();
    int maxY = level.getMaxY();
    boolean overworld = level.dimension().equals(Level.OVERWORLD);

    // Nether/End: keep a 2-block pocket where each player stands, since they aren't teleported out
    Set<Long> keepFree = new HashSet<>();
    if (!overworld) {
        for (ServerPlayer p : level.players()) {
            if ((p.getBlockX() >> 4) == cx && (p.getBlockZ() >> 4) == cz) {
                keepFree.add(BlockPos.asLong(p.getBlockX(), p.getBlockY(), p.getBlockZ()));
                keepFree.add(BlockPos.asLong(p.getBlockX(), p.getBlockY() + 1, p.getBlockZ()));
            }
        }
    }

    for (int x = cx * 16; x < cx * 16 + 16; x++) {
        for (int z = cz * 16; z < cz * 16 + 16; z++) {
            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            for (int y = minY; y <= maxY; y++) {
                pos.set(x, y, z);
                BlockState current = level.getBlockState(pos);
                if (current.is(Blocks.BEDROCK)) continue;
                if (keepFree.contains(pos.asLong())) continue;

                // below the ground: fill everything, caves and air pockets included
                if (y < surface) {
                    level.setBlock(pos, state, PLACE_FLAGS);
                    continue;
                }

                // at or above the ground: air is never changed
                if (current.isAir()) continue;

                Block cb = current.getBlock();
                boolean solid = current.isCollisionShapeFullBlock(
                        net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                        || cb instanceof net.minecraft.world.level.block.SlabBlock
                        || cb instanceof net.minecraft.world.level.block.StairBlock
                        || cb instanceof net.minecraft.world.level.block.FenceBlock;
                boolean fluid = !current.getFluidState().isEmpty();

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

    // ---------------------------------------------------------------- files

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

    // ---------------------------------------------------------------- twitch

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
        if (b != null) return isSafe(b) ? b : null;
    }
    return null;
}
static boolean isSafe(Block b) {
    return true;
}
}
