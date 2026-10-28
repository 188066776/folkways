package io.github.izakyl.folkways.test;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.inventory.InventoryIndex;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public final class InventoryObservations {
    private InventoryObservations() { }

    // Synchronous test drivers advance the observer before asking for inventory-dependent work.
    public static void publish(Colony colony, ServerLevel level, Map<UUID, Body> present) {
        var data = ColonyData.find(level.getServer(), colony.id()).orElseThrow();
        data.inventory().advance(level, data, present);
        try {
            var work = InventoryIndex.class.getDeclaredMethod("work", MinecraftServer.class, int.class);
            work.setAccessible(true);
            work.invoke(data.inventory(), level.getServer(), 1000);
        } catch (ReflectiveOperationException broken) {
            throw new AssertionError(broken);
        }
    }
}
