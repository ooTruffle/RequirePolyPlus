package dev.requirepolyplus;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.polyfrost.oneclient.api.OneClient;
import org.polyfrost.oneclient.api.OneClientPlayer;
import org.polyfrost.oneclient.api.event.OneClientPlayerJoinEvent;
import org.polyfrost.oneclient.common.OneClientServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RequirePolyPlus implements ModInitializer {
    public static final String MOD_ID = "require-polyplus";
    static final Logger LOGGER = LoggerFactory.getLogger("RequirePoly+");

    private volatile MinecraftServer minecraftServer;
    private OneClientServer oneClient;
    private Config config;

    /** Players who haven't sent the Poly+ hello yet, mapped to the tick they get kicked on. */
    private final Map<UUID, Long> deadlines = new ConcurrentHashMap<>();
    /** Players whose client gets logged, mapped to the tick to log it on. */
    private final Map<UUID, Long> logClientAt = new ConcurrentHashMap<>();
    /** When each player's kick was last announced in chat, so rejoining over and over doesn't spam it. */
    private final Map<UUID, Long> lastKickBroadcast = new ConcurrentHashMap<>();
    private static final long KICK_BROADCAST_COOLDOWN_TICKS = 60 * 20;
    private long tick;

    @Override
    public void onInitialize() {
        config = Config.load(LOGGER);

        PayloadTypeRegistry.serverboundPlay().register(OneClientPayload.TYPE, OneClientPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(OneClientPayload.TYPE, OneClientPayload.CODEC);
        ClientDetector.init();

        oneClient = new OneClientServer(new FabricPlatform(LOGGER, () -> minecraftServer));
        OneClient.register(oneClient); // lets other mods use OneClient.getApi()
        oneClient.getEventBus().subscribe(OneClientPlayerJoinEvent.class, event -> onPolyPlusHello(event.getPlayer()));

        ServerLifecycleEvents.SERVER_STARTING.register(server -> minecraftServer = server);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            minecraftServer = null;
            deadlines.clear();
            logClientAt.clear();
            lastKickBroadcast.clear();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (!config.tag.isEmpty()) {
                player.removeTag(config.tag); // left over from an earlier session
            }
            startTimer(player);

            // Announce oneclient:api only now, once the player is fully in game. A global receiver
            // gets announced during configuration instead, and Poly+ then tries to send its hello
            // before it can ("Cannot send packets when not in game!") and never retries.
            ServerPlayNetworking.registerReceiver(handler, OneClientPayload.TYPE, (payload, context) ->
                oneClient.handlePluginMessage(player.getUUID(), player.getName().getString(), payload.data()));
            ClientDetector.announceChannels(handler);
            logClientAt.put(player.getUUID(), tick + 60); // give clients 3s to register their channels
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (!config.tag.isEmpty()) {
                player.removeTag(config.tag); // the tag only lasts while they're online
            }
            deadlines.remove(player.getUUID());
            logClientAt.remove(player.getUUID());
            oneClient.handleQuit(player.getUUID());
            ClientDetector.forget(player.getUUID());
        });

        ServerTickEvents.END_SERVER_TICK.register(this::onTick);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            PolyPlusCommand.register(dispatcher, this));

        LOGGER.info(config.kickEnabled
            ? "Players without Poly+ will be kicked after " + config.timeoutSeconds + "s"
            : "Poly+ is not required (turn it on with /rpp set required true)");
    }

    private void onPolyPlusHello(OneClientPlayer polyPlayer) {
        deadlines.remove(polyPlayer.getUniqueId());
        LOGGER.info("{} joined with Poly+ {} (Minecraft {})", polyPlayer.getName(),
            polyPlayer.getClientVersion(), polyPlayer.getMinecraftVersion());

        MinecraftServer server = minecraftServer;
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(polyPlayer.getUniqueId());
        if (player == null) {
            return;
        }

        if (config.kickEnabled && !isExempt(player) && !config.meetsMinVersion(polyPlayer.getClientVersion())) {
            LOGGER.info("Kicking {}: Poly+ {} is older than {}", polyPlayer.getName(), polyPlayer.getClientVersion(), config.minVersion);
            player.connection.disconnect(Component.literal(fill(config.outdatedMessage, polyPlayer)));
            return;
        }
        if (!config.tag.isEmpty()) {
            player.addTag(config.tag);
        }
        if (!config.welcomeMessage.isEmpty()) {
            player.sendSystemMessage(Component.literal(fill(config.welcomeMessage, polyPlayer)));
        }
    }

    private String fill(String message, OneClientPlayer player) {
        return Config.format(message)
            .replace("%player%", player.getName())
            .replace("%version%", player.getClientVersion())
            .replace("%min%", config.minVersion);
    }

    private void onTick(MinecraftServer server) {
        tick++;
        logClientAt.entrySet().removeIf(entry -> {
            if (entry.getValue() > tick) {
                return false;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                LOGGER.info("{} is using {} (brand: {})", player.getName().getString(),
                    ClientDetector.detect(player, oneClient), ClientDetector.brand(player.getUUID()));
            }
            return true;
        });
        if (!config.kickEnabled) {
            deadlines.clear();
            return;
        }
        Iterator<Map.Entry<UUID, Long>> it = deadlines.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            if (entry.getValue() > tick) {
                continue;
            }
            it.remove();
            if (oneClient.isOneClient(entry.getKey())) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null && !isExempt(player)) {
                String name = player.getName().getString();
                String client = ClientDetector.detect(player, oneClient);
                LOGGER.info("Kicking {}: no Poly+ hello received (looks like {})", name, client);
                player.connection.disconnect(Component.literal(Config.format(config.kickMessage)
                    .replace("%player%", name)));
                Long last = lastKickBroadcast.get(entry.getKey());
                if (!config.kickBroadcast.isEmpty() && (last == null || tick - last >= KICK_BROADCAST_COOLDOWN_TICKS)) {
                    lastKickBroadcast.put(entry.getKey(), tick);
                    server.getPlayerList().broadcastSystemMessage(Component.literal(Config.format(config.kickBroadcast)
                        .replace("%player%", name)
                        .replace("%client%", client)), false);
                }
            }
        }
    }

    /** Gives a player without Poly+ the configured time before they're kicked. */
    private void startTimer(ServerPlayer player) {
        if (config.kickEnabled && !isExempt(player) && !oneClient.isOneClient(player.getUUID())) {
            deadlines.putIfAbsent(player.getUUID(), tick + config.timeoutSeconds * 20L);
        }
    }

    // ---- used by /rpp ----

    Config config() {
        return config;
    }

    OneClientServer api() {
        return oneClient;
    }

    boolean isExempt(ServerPlayer player) {
        return config.exempt.contains(player.getName().getString().toLowerCase(Locale.ROOT))
            || config.exempt.contains(player.getUUID().toString().toLowerCase(Locale.ROOT));
    }

    /** Re-checks everyone online after a setting changes, so it takes effect without rejoining. */
    void settingsChanged(MinecraftServer server) {
        config.save();
        if (!config.kickEnabled) {
            deadlines.clear();
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isExempt(player)) {
                deadlines.remove(player.getUUID());
            } else {
                startTimer(player);
            }
        }
    }

    /** Moves the Poly+ tag from {@code oldTag} to the current one on everyone online. */
    void tagChanged(MinecraftServer server, String oldTag) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!oldTag.isEmpty()) {
                player.removeTag(oldTag);
            }
            if (!config.tag.isEmpty() && oneClient.isOneClient(player.getUUID())) {
                player.addTag(config.tag);
            }
        }
    }

    void reload(MinecraftServer server) {
        String oldTag = config.tag;
        config = Config.load(LOGGER);
        tagChanged(server, oldTag);
        settingsChanged(server);
    }
}
