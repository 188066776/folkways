package io.github.izakyl.folkways.cookery;

import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.inventory.InventoryIndex;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public final class InventoryObservations {

    private InventoryObservations() {
    }

    public static void publish(io.github.izakyl.folkways.core.api.colony.Colony colony, ServerLevel level) {
        publish(ColonyData.find(level.getServer(), colony.id()).orElseThrow(), level);
    }

    // These synchronous execution fixtures explicitly run the core observer between planning turns.
    public static void publish(ColonyData colony, ServerLevel level) {
        Map<UUID, Body> present = new LinkedHashMap<>();
        colony.works().residents().forEach(resident -> {
            var entity = level.getEntity(resident.id());
            if (entity != null && !entity.isRemoved()) {
                Bodies.of(entity).ifPresent(body -> present.put(resident.id(), body));
            }
        });
        colony.inventory().advance(level, colony, present);
        try {
            var work = InventoryIndex.class.getDeclaredMethod("work", MinecraftServer.class, int.class);
            work.setAccessible(true);
            work.invoke(colony.inventory(), level.getServer(), 1_000);
        } catch (ReflectiveOperationException broken) {
            throw new AssertionError(broken);
        }
    }
}
