package dev.requirepolyplus;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.polyfrost.oneclient.api.OneClient;
import org.polyfrost.oneclient.common.OneClientServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RequirePolyPlus implements ModInitializer {
    public static final String MOD_ID = "require-polyplus";
    static final Logger LOGGER = LoggerFactory.getLogger("RequirePoly+");

    private static final long TIMEOUT_TICKS = 10 * 20;
    private static final String KICK_MESSAGE = "You need the Poly+ mod to play on this server.\nGet it at modrinth.com/mod/polyplus";

    private volatile MinecraftServer minecraftServer;
    private OneClientServer oneClient;

    private final Map<UUID, Long> deadlines = new ConcurrentHashMap<>();
    private long tick;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(OneClientPayload.TYPE, OneClientPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(OneClientPayload.TYPE, OneClientPayload.CODEC);

        oneClient = new OneClientServer(new FabricPlatform(LOGGER, () -> minecraftServer));
        OneClient.register(oneClient);

        ServerLifecycleEvents.SERVER_STARTING.register(server -> minecraftServer = server);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> minecraftServer = null);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            deadlines.put(player.getUUID(), tick + TIMEOUT_TICKS);

            ServerPlayNetworking.registerReceiver(handler, OneClientPayload.TYPE, (payload, context) ->
                oneClient.handlePluginMessage(player.getUUID(), player.getName().getString(), payload.data()));
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUUID();
            deadlines.remove(id);
            oneClient.handleQuit(id);
        });

        ServerTickEvents.END_SERVER_TICK.register(this::onTick);
    }

    private void onTick(MinecraftServer server) {
        tick++;
        deadlines.entrySet().removeIf(entry -> {
            if (entry.getValue() > tick) {
                return false;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null && !oneClient.isOneClient(entry.getKey())) {
                LOGGER.info("Kicking {}: no Poly+ hello received", player.getName().getString());
                player.connection.disconnect(Component.literal(KICK_MESSAGE));
            }
            return true;
        });
    }
}
