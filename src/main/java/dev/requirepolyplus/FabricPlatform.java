package dev.requirepolyplus;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.polyfrost.oneclient.common.PlatformLogger;
import org.polyfrost.oneclient.common.ServerPlatform;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.function.Supplier;

/** Connects Polyfrost's Server-API to Fabric (Server-API itself only ships Bukkit, Bungee and Minestom). */
final class FabricPlatform implements ServerPlatform {
    private final PlatformLogger logger;
    private final Supplier<MinecraftServer> server;

    FabricPlatform(Logger logger, Supplier<MinecraftServer> server) {
        this.server = server;
        this.logger = new PlatformLogger() {
            @Override
            public void debug(String message) {
                logger.debug(message);
            }

            @Override
            public void error(String message, Throwable thrown) {
                logger.error(message, thrown);
            }
        };
    }

    @Override
    public PlatformLogger getLogger() {
        return logger;
    }

    @Override
    public String getApiVersion() {
        return FabricLoader.getInstance().getModContainer(RequirePolyPlus.MOD_ID)
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    }

    @Override
    public void sendPluginMessage(UUID uniqueId, byte[] data) {
        MinecraftServer current = server.get();
        if (current == null) {
            return;
        }
        ServerPlayer player = current.getPlayerList().getPlayer(uniqueId);
        if (player != null && ServerPlayNetworking.canSend(player, OneClientPayload.TYPE)) {
            ServerPlayNetworking.send(player, new OneClientPayload(data));
        }
    }
}
