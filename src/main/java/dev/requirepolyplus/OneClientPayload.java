package dev.requirepolyplus;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.polyfrost.oneclient.common.protocol.Protocol;

/** Raw bytes sent on the oneclient:api channel. Server-API does the actual encoding. */
public record OneClientPayload(byte[] data) implements CustomPacketPayload {
    public static final Type<OneClientPayload> TYPE = new Type<>(Identifier.parse(Protocol.CHANNEL));

    public static final StreamCodec<FriendlyByteBuf, OneClientPayload> CODEC = StreamCodec.of(
        (buf, payload) -> buf.writeBytes(payload.data()),
        buf -> {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return new OneClientPayload(bytes);
        }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
