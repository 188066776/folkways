package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.inventory.InventoryChanges;
import io.github.izakyl.folkways.core.engine.plan.Stores;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class Stowing {

    private Stowing() {
    }

    static void put(Worker who, Placement placement, List<ItemStack> made) {
        for (ItemStack stack : made) {
            ItemStack left = stack.copy();
            for (Stash into : placement.into()) {
                if (left.isEmpty()) {
                    break;
                }
                for (Worker.Store store : who.within()) {
                    if (store.at().equals(into.pos())) {
                        if (StoreRule.stores(Stores.rulesAt((ServerLevel) who.body().level(), store.at()))) {
                            left = Containers.insert(store.contents(), left);
                            if (who.body().level() instanceof ServerLevel level) {
                                InventoryChanges.store(level, store.at());
                            }
                        }
                        break;
                    }
                }
            }
            if (!left.isEmpty()) {
                left = Containers.insert(who.pack(), left);
            }
            who.spill(left);
        }
    }
}
