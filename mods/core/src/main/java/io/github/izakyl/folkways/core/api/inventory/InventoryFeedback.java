package io.github.izakyl.folkways.core.api.inventory;

import io.github.izakyl.folkways.core.api.terms.Stash;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

// Server-thread notifications. They enqueue work; they never read an inventory synchronously.
public interface InventoryFeedback {

    InventoryFeedback NONE = new InventoryFeedback() { };

    record StoreSlot(Stash store, int physicalSlot) {
    }

    default void stale(StoreSlot slot, InventorySnapshot.Version observed) {
    }

    default void stale(Stash store, InventorySnapshot.Version observed) {
    }

    default void staleResident(UUID resident, InventorySnapshot.Version observed) {
    }

    default void staleApproach(Stash store, InventorySnapshot.Version observed) {
    }

    default void changed(Stash store) {
    }

    default void providersChanged(ResourceLocation owner) {
    }

    // Register a stack already moved by execution; no inventory is opened or synchronously refreshed.
    default long changedResidentSlot(UUID resident, int physicalSlot, ItemStack stack) {
        changedResident(resident);
        return 0;
    }

    default long changedEquipment(UUID resident, EquipmentSlot slot, ItemStack stack) {
        changedResident(resident);
        return 0;
    }

    default void changedResident(UUID resident) {
    }
}
