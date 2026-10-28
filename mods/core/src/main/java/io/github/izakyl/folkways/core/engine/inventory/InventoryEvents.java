package io.github.izakyl.folkways.core.engine.inventory;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.engine.colony.ColonyRegistry;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID)
public final class InventoryEvents {

    private InventoryEvents() {
    }

    @SubscribeEvent
    public static void equipped(LivingEquipmentChangeEvent event) {
        InventoryChanges.resident(event.getEntity());
    }

    @SubscribeEvent
    public static void loaded(ChunkEvent.Load event) {
        chunk(event, true);
    }

    @SubscribeEvent
    public static void unloaded(ChunkEvent.Unload event) {
        chunk(event, false);
    }

    private static void chunk(ChunkEvent event, boolean loaded) {
        if (event.getLevel() instanceof ServerLevel level) {
            var pos = event.getChunk().getPos();
            for (var colony : ColonyRegistry.get(level.getServer()).colonies()) {
                colony.inventory().chunk(level, pos.x, pos.z, loaded);
            }
        }
    }
}
