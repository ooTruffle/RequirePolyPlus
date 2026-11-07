package dev.requirepolyplus.mixin;

import com.mojang.authlib.GameProfile;
import dev.requirepolyplus.ClientDetectorHooks;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla reads the client's brand and throws it away, so keep a copy. */
@Mixin(ServerCommonPacketListenerImpl.class)
abstract class BrandMixin {
    @Shadow
    public abstract GameProfile getOwner();

    @Inject(method = "handleCustomPayload", at = @At("HEAD"))
    private void requirepolyplus$recordBrand(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        if (packet.payload() instanceof BrandPayload(String brand)) {
            ClientDetectorHooks.recordBrand(getOwner().id(), brand);
        }
    }
}
