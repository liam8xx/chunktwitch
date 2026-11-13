package com.example.chunktwitch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;

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
    static volatile boolean canChat = false;
    static volatile OutputStream currentOut = null;
    static volatile String chatChannel = null;

@Override
public void onInitialize() {
    CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(Commands.literal("chunktwitch")
                    .then(Commands.literal("connect")
                            .executes(ctx -> {
                                SERVER = ctx.getSource().getServer();
                                ctx.getSource().sendSuccess(
                                        () -> Component.literal("Connecting to Twitch..."), false);
                                startConnect();
                                return 1;
                            }))
                    .then(Commands.literal("disconnect")
                            .executes(ctx -> {
                                generation++;
                                closeSocket();
                                canChat = false;
                                try { Files.deleteIfExists(authFile()); } catch (IOException ignored) {}
                                ctx.getSource().sendSuccess(
                                        () -> Component.literal("Disconnected from Twitch and removed the saved login."), false);
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

        SERVER = server;
        QUEUE.clear();
        lastQueueSize = -1;

        if (Files.exists(authFile())) startConnect(); // reconnect automatically if already logged in
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
        boolean underground = false;

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

    static final String CLIENT_ID = "ywbod558j3ufwcas8rqrpcasgf16qo";
    static final String SCOPES = "chat:read chat:edit";
    static final String DEVICE_URL = "https://id.twitch.tv/oauth2/device";
    static final String TOKEN_URL = "https://id.twitch.tv/oauth2/token";
    static final String VALIDATE_URL = "https://id.twitch.tv/oauth2/validate";
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static volatile MinecraftServer SERVER = null;

static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
}

static Path authFile() {
    return FabricLoader.getInstance().getConfigDir().resolve("chunktwitch-auth.properties");
}

static void tell(String msg) {
    MinecraftServer s = SERVER;
    System.out.println(msg);
    if (s == null) return;
    s.execute(() -> s.getPlayerList().broadcastSystemMessage(Component.literal(msg), false));
}

static JsonObject post(String url, String form) throws Exception {
    HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
    HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    JsonObject o;
    try {
        o = JsonParser.parseString(res.body()).getAsJsonObject();
    } catch (Exception e) {
        o = new JsonObject();
    }
    o.addProperty("_http", res.statusCode());
    return o;
}

static Properties readAuth() {
    Properties p = new Properties();
    try (InputStream in = Files.newInputStream(authFile())) {
        p.load(in);
    } catch (IOException ignored) {}
    return p;
}

static void saveTokens(JsonObject r) {
    Properties p = readAuth();
    p.setProperty("access_token", r.get("access_token").getAsString());
    if (r.has("refresh_token")) p.setProperty("refresh_token", r.get("refresh_token").getAsString());
    try (OutputStream out = Files.newOutputStream(authFile())) {
        p.store(out, "ChunkTwitch login - keep private, do not share or show on stream");
    } catch (IOException e) {
        System.err.println("[ChunkTwitch] could not save login: " + e);
    }
}

/** returns the account's login name, or null if the token is no good */
static String validate(String token) throws Exception {
    HttpRequest req = HttpRequest.newBuilder(URI.create(VALIDATE_URL))
            .header("Authorization", "OAuth " + token)
            .GET().build();
    HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    if (res.statusCode() != 200) return null;
    JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
    return o.has("login") ? o.get("login").getAsString().toLowerCase(Locale.ROOT) : null;
}

/** uses the saved login, refreshing it if needed. Returns {login, token}, or null if a new login is required. */
static synchronized String[] ensureLogin() throws Exception {
    Properties p = readAuth();
    String access = p.getProperty("access_token");
    String refresh = p.getProperty("refresh_token");

    if (access != null) {
        String login = validate(access);
        if (login != null) return new String[] { login, access };
    }
    if (refresh == null) return null;

    JsonObject r = post(TOKEN_URL, "client_id=" + enc(CLIENT_ID)
            + "&grant_type=refresh_token&refresh_token=" + enc(refresh));
    if (!r.has("access_token")) return null;
    saveTokens(r);
    String token = r.get("access_token").getAsString();
    String login = validate(token);
    return login == null ? null : new String[] { login, token };
}

static String[] deviceLogin() throws Exception {
    JsonObject d = post(DEVICE_URL, "client_id=" + enc(CLIENT_ID) + "&scopes=" + enc(SCOPES));
    if (!d.has("device_code")) {
        tell("[ChunkTwitch] Twitch refused the login request (check the Client ID): " + d);
        return null;
    }
    String deviceCode = d.get("device_code").getAsString();
    String userCode = d.get("user_code").getAsString();
    String uri = d.get("verification_uri").getAsString();
    int interval = d.has("interval") ? d.get("interval").getAsInt() : 5;
    long expires = d.has("expires_in") ? d.get("expires_in").getAsLong() : 600;
    long deadline = System.currentTimeMillis() + expires * 1000L;

    tellLink(uri);
    try {
        if (java.awt.Desktop.isDesktopSupported()
                && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
            java.awt.Desktop.getDesktop().browse(URI.create(uri));
        }
    } catch (Throwable ignored) {}

    while (System.currentTimeMillis() < deadline) {
        Thread.sleep(interval * 1000L);
        JsonObject r = post(TOKEN_URL, "client_id=" + enc(CLIENT_ID)
                + "&scopes=" + enc(SCOPES)
                + "&device_code=" + enc(deviceCode)
                + "&grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code"));
        if (r.has("access_token")) {
            saveTokens(r);
            String token = r.get("access_token").getAsString();
            String login = validate(token);
            return login == null ? null : new String[] { login, token };
        }
        String msg = r.has("message") ? r.get("message").getAsString() : "";
        if (msg.equals("authorization_pending")) continue;
        if (msg.equals("slow_down")) { interval += 5; continue; }
        tell("[ChunkTwitch] Login stopped: " + msg);
        return null;
    }
    tell("[ChunkTwitch] Login timed out. Run /chunktwitch connect to try again.");
    return null;
}

static void tellLink(String uri) {
    System.out.println("[ChunkTwitch] Login link: " + uri);
    MinecraftServer s = SERVER;
    if (s == null) return;
    s.execute(() -> {
        Component link = Component.literal("[Click here to log in to Twitch]")
                .withStyle(style -> style
                        .withClickEvent(new ClickEvent.OpenUrl(URI.create(uri)))
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true));
        s.getPlayerList().broadcastSystemMessage(Component.literal("[ChunkTwitch] ").append(link), false);
    });
}
    
static void startConnect() {
    Thread t = new Thread(() -> {
        try {
            String[] login = ensureLogin();
            if (login == null) login = deviceLogin();
            if (login == null) return;
            tell("[ChunkTwitch] Logged in to Twitch as " + login[0] + ". Listening to #" + login[0] + ".");
            startTwitch();
        } catch (Exception e) {
            tell("[ChunkTwitch] Twitch login failed: " + e.getMessage());
        }
    }, "chunktwitch-login");
    t.setDaemon(true);
    t.start();
}
    
    static void closeSocket() {
        Socket s = currentSocket;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    static void startTwitch() {
    closeSocket();
    final int gen = ++generation;

    Thread t = new Thread(() -> {
        while (gen == generation) {
            canChat = false;
            String[] login;
            try {
                login = ensureLogin(); // refreshes the token on every reconnect
            } catch (Exception e) {
                try { Thread.sleep(5000); } catch (InterruptedException ignored) { return; }
                continue;
            }
            if (login == null) {
                tell("[ChunkTwitch] Your Twitch login has expired. Type /chunktwitch connect to log in again.");
                return;
            }
            final String chan = login[0];

            try (Socket socket = new Socket("irc.chat.twitch.tv", 6667)) {
                currentSocket = socket;
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                OutputStream out = socket.getOutputStream();
                currentOut = out;
                chatChannel = chan;

                send(out, "PASS oauth:" + login[1]);
                send(out, "NICK " + chan);
                send(out, "JOIN #" + chan);

                String line;
                while (gen == generation && (line = in.readLine()) != null) {
                    if (line.contains(" 001 ")) {
                        canChat = true;
                        System.out.println("[ChunkTwitch] chat login OK, replies enabled");
                    }
                    if (line.contains("Login authentication failed") || line.contains("Improperly formatted auth")) {
                        System.err.println("[ChunkTwitch] Twitch rejected the login: " + line);
                    }
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
            canChat = false;
            currentOut = null;
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

    static void say(String message) {
    OutputStream out = currentOut;
    String chan = chatChannel;
    if (!canChat || out == null || chan == null) return;
    try {
        send(out, "PRIVMSG #" + chan + " :" + message);
    } catch (IOException e) {
        System.err.println("[ChunkTwitch] could not send chat message: " + e);
    }
}

static String prettyName(Block b) {
    String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
    StringBuilder sb = new StringBuilder();
    for (String w : path.split("_")) {
        if (w.isEmpty()) continue;
        if (sb.length() > 0) sb.append(' ');
        sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
    }
    return sb.toString();
}

static void handleChat(String user, String raw) {
    user = user.toLowerCase(Locale.ROOT);

    // one chunk in the queue per viewer
    for (Entry q : QUEUE) {
        if (q.user().equals(user)) {
            say("@" + user + " You already have a chunk in the queue, please wait until it is used to claim another chunk");
            return;
        }
    }

    String text = raw.trim().toLowerCase(Locale.ROOT);
    if (text.isEmpty() || !text.matches("[a-z0-9_ ]+")) return;

    Block block = resolveBlock(text);
    if (block == null) return;

    QUEUE.add(new Entry(user, block));
    int position = QUEUE.size();
    int claimed = SCORES.getOrDefault(user, 0);
    say("@" + user + " you chose " + prettyName(block) + " as a chunk. Position in queue: "
            + position + " | Chunks Claimed: " + claimed);
}

    static final Map<String, Integer> SCORES = new java.util.concurrent.ConcurrentHashMap<>();
    
}
