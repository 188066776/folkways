package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.inventory.InventoryIndex;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public final class InventoryObservations {
    private InventoryObservations() {
    }

    public static void publish(Colony colony, ServerLevel level) {
        ColonyData data = ColonyData.find(level.getServer(), colony.id()).orElseThrow();
        Map<UUID, Body> present = new HashMap<>();
        data.works().residents().forEach(resident -> {
            var entity = level.getEntity(resident.id());
            if (entity != null) {
                Bodies.of(entity).ifPresent(body -> present.put(resident.id(), body));
            }
        });
        data.inventory().advance(level, data, present);
        try {
            var work = InventoryIndex.class.getDeclaredMethod("work", MinecraftServer.class, int.class);
            work.setAccessible(true);
            work.invoke(data.inventory(), level.getServer(), 1_000);
        } catch (ReflectiveOperationException broken) {
            throw new AssertionError(broken);
        }
    }
}
