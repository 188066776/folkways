package io.github.izakyl.folkways.core.engine.inventory;

import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.colony.ColonyRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

public final class InventoryChanges {

    private InventoryChanges() {
    }

    public static void store(ServerLevel level, WorldPos at) {
        for (ColonyData colony : ColonyRegistry.get(level.getServer()).colonies()) {
            colony.inventory().changed(Stash.at(at));
        }
    }

    public static void resident(Entity entity) {
        if (entity.level() instanceof ServerLevel level) {
            Bodies.of(entity).flatMap(body -> body.colonyId())
                .flatMap(id -> ColonyData.find(level.getServer(), id))
                .ifPresent(colony -> colony.inventory().changedResident(entity.getUUID()));
        }
    }
}
