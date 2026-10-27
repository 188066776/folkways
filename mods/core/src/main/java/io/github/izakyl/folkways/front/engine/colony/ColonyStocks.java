package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.front.api.Facing;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

public final class ColonyStocks {

    private ColonyStocks() {
    }

    public static Map<ItemSpec, Integer> tally(Colony colony, MinecraftServer server) {
        Map<ItemSpec, Integer> held = new LinkedHashMap<>();
        for (ColonyView view : colony.views(server)) {
            for (ItemStack stack : stacks(colony, view)) {
                held.merge(Goods.specOf(stack), stack.getCount(), Integer::sum);
            }
        }
        for (Body resident : Residents.of(colony, server)) {
            Container pack = resident.pack();
            for (int slot = 0; slot < pack.getContainerSize(); slot++) {
                ItemStack stack = pack.getItem(slot);
                if (!stack.isEmpty()) {
                    held.merge(Goods.specOf(stack), stack.getCount(), Integer::sum);
                }
            }
        }
        return held;
    }

    public static long available(Colony colony, MinecraftServer server, ItemSpec spec) {
        long total = 0;
        for (ColonyView view : colony.views(server)) {
            for (ItemStack stack : stacks(colony, view)) {
                if (Goods.matches(stack, spec)) {
                    total += stack.getCount();
                }
            }
        }
        for (Body resident : Residents.of(colony, server)) {
            total += Goods.countIn(resident.pack(), spec);
        }
        return total;
    }

    private static List<ItemStack> stacks(Colony colony, ColonyView view) {
        ServerLevel level = view.level();
        List<ItemStack> found = new ArrayList<>();
        Set<BlockPos> counted = new LinkedHashSet<>();
        for (WorldPos store : colony.storesIn(view.dimension())) {
            WorldSpaces.storage(level, store).filter(level::isLoaded).ifPresent(cell -> {
                BlockPos anchor = Containers.anchor(level, cell);
                if (!counted.contains(anchor)) {
                    Containers.at(level, cell).ifPresent(container -> {
                        counted.add(anchor);
                        for (int slot = 0; slot < container.getContainerSize(); slot++) {
                            ItemStack stack = container.getItem(slot);
                            if (!stack.isEmpty()) {
                                found.add(stack);
                            }
                        }
                    });
                }
            });
        }
        for (Facing facing : Facing.all(colony)) {
            facing.stock(view).forEach((at, stacks) -> WorldSpaces.storage(level, at).filter(level::isLoaded)
                .ifPresent(cell -> {
                    if (counted.add(Containers.anchor(level, cell))) {
                        stacks.stream().filter(stack -> !stack.isEmpty()).forEach(found::add);
                    }
                }));
        }
        return found;
    }
}
