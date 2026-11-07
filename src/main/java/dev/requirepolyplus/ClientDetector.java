package dev.requirepolyplus;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jspecify.annotations.Nullable;
import org.polyfrost.oneclient.api.OneClientApi;
import org.polyfrost.oneclient.api.OneClientPlayer;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Best-guess detection of which client a player is using, from the brand the client reports and the
 * plugin channels it registers. Clients can fake both, so this is a hint, not proof. Poly+ is the
 * exception in that it's confirmed through the OneClient Server-API hello.
 */
final class ClientDetector {
    /** Lunar Client's Apollo channel. Lunar registers it back once the server announces it. */
    static final RawPayload.Kind LUNAR_APOLLO = new RawPayload.Kind(Identifier.parse("lunar:apollo"));

    /** Brand each connecting client sent, by player UUID. Filled in by {@code BrandMixin}. */
    private static final Map<UUID, String> BRANDS = new ConcurrentHashMap<>();

    private ClientDetector() {
    }

    static void init() {
        LUNAR_APOLLO.register();
    }

    static void recordBrand(UUID player, String brand) {
        BRANDS.put(player, brand);
    }

    static void forget(UUID player) {
        BRANDS.remove(player);
    }

    static @Nullable String brand(UUID player) {
        return BRANDS.get(player);
    }

    /** Announces the channels other clients look for, so they identify themselves. Call on join. */
    static void announceChannels(ServerGamePacketListenerImpl handler) {
        ServerPlayNetworking.registerReceiver(handler, LUNAR_APOLLO.type(), (payload, context) -> {
            // We don't use Apollo's messages, only the fact that Lunar talks on this channel.
        });
    }

    /** Channels the client registered, minus the everyday vanilla and Fabric API ones. */
    static Set<String> interestingChannels(ServerPlayer player) {
        Set<String> channels = new TreeSet<>();
        for (Identifier id : ServerPlayNetworking.getSendable(player)) {
            String ns = id.getNamespace();
            if (!ns.equals("minecraft") && !ns.equals("c") && !ns.startsWith("fabric")) {
                channels.add(id.toString());
            }
        }
        return channels;
    }

    /** A short name for the client, e.g. "Poly+ 1.4.5", "Lunar Client" or "Fabric". */
    static String detect(ServerPlayer player, OneClientApi api) {
        Optional<OneClientPlayer> poly = api.getPlayer(player.getUUID());
        if (poly.isPresent()) {
            return "Poly+ " + poly.get().getClientVersion();
        }

        String brand = Optional.ofNullable(brand(player.getUUID())).orElse("").toLowerCase(Locale.ROOT);
        Set<String> namespaces = new TreeSet<>();
        for (Identifier id : ServerPlayNetworking.getSendable(player)) {
            namespaces.add(id.getNamespace());
        }

        if (namespaces.contains("lunar") || namespaces.contains("lunarclient") || brand.startsWith("lunarclient")) {
            return "Lunar Client";
        }
        if (namespaces.contains("labymod") || namespaces.contains("labymod3") || brand.contains("labymod")) {
            return "LabyMod";
        }
        if (namespaces.contains("badlion") || brand.contains("badlion")) {
            return "Badlion Client";
        }
        if (namespaces.contains("feather") || brand.contains("feather")) {
            return "Feather Client";
        }
        if (brand.isEmpty()) {
            return "Unknown (no brand sent)";
        }
        return switch (brand) {
            case "vanilla" -> "Vanilla";
            case "fabric" -> "Fabric";
            case "quilt" -> "Quilt";
            case "forge" -> "Forge";
            case "neoforge" -> "NeoForge";
            default -> "Unknown (brand \"" + brand(player.getUUID()) + "\")";
        };
    }

    /** A custom payload we only need to receive, kept as raw bytes. */
    record RawPayload(Type<RawPayload> type, byte[] data) implements CustomPacketPayload {
        record Kind(Type<RawPayload> type) {
            Kind(Identifier id) {
                this(new Type<>(id));
            }

            void register() {
                StreamCodec<FriendlyByteBuf, RawPayload> codec = StreamCodec.of(
                    (buf, payload) -> buf.writeBytes(payload.data()),
                    buf -> {
                        byte[] bytes = new byte[buf.readableBytes()];
                        buf.readBytes(bytes);
                        return new RawPayload(type, bytes);
                    });
                PayloadTypeRegistry.serverboundPlay().register(type, codec);
                PayloadTypeRegistry.clientboundPlay().register(type, codec);
            }
        }
    }
}
