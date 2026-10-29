package io.github.izakyl.folkways.core.engine.inventory;

import net.minecraft.server.MinecraftServer;

public final class InventoryTestWork {
    private InventoryTestWork() {
    }

    public static void finish(InventoryIndex index, MinecraftServer server) {
        index.work(server, 100);
    }
}
