package dev.requirepolyplus;

import java.util.UUID;

/** Public entry point for the mixin package (ClientDetector itself stays package-private). */
public final class ClientDetectorHooks {
    private ClientDetectorHooks() {
    }

    public static void recordBrand(UUID player, String brand) {
        ClientDetector.recordBrand(player, brand);
    }
}
