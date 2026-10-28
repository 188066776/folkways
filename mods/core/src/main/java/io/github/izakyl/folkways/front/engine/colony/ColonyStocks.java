package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.inventory.InventorySnapshot;
import io.github.izakyl.folkways.core.api.inventory.ColonyInventory;
import io.github.izakyl.folkways.core.api.inventory.SlotSnapshot;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;

public final class ColonyStocks {

    private static final Map<ColonyInventory, Counted> TOTALS = new WeakHashMap<>();

    private record Counted(InventorySnapshot.Version version, Map<ItemSpec, Long> goods) {
    }

    private ColonyStocks() {
    }

    public static Map<ItemSpec, Integer> tally(Colony colony, MinecraftServer server) {
        Map<ItemSpec, Integer> held = new LinkedHashMap<>();
        counted(colony).forEach((spec, count) -> held.put(spec, count.intValue()));
        return held;
    }

    public static long available(Colony colony, MinecraftServer server, ItemSpec spec) {
        return counted(colony).entrySet().stream().filter(entry -> Goods.matches(Goods.display(entry.getKey()), spec))
            .mapToLong(Map.Entry::getValue).sum();
    }

    private static synchronized Map<ItemSpec, Long> counted(Colony colony) {
        ColonyInventory inventory = colony.inventory();
        InventorySnapshot snapshot = inventory.snapshot();
        if (!snapshot.coverage().complete()) {
            inventory.ensureObserved();
        }
        var version = snapshot.version();
        Counted previous = TOTALS.get(inventory);
        if (previous != null && previous.version().epoch().equals(version.epoch())
            && previous.version().contents() == version.contents()
            && previous.version().topology() == version.topology()) {
            return previous.goods();
        }
        Map<ItemSpec, Long> held = new LinkedHashMap<>();
        slots(snapshot).map(SlotSnapshot::stackCopy).filter(stack -> !stack.isEmpty())
            .forEach(stack -> held.merge(Goods.specOf(stack), (long) stack.getCount(), Long::sum));
        Map<ItemSpec, Long> goods = Map.copyOf(held);
        TOTALS.put(inventory, new Counted(version, goods));
        return goods;
    }

    private static Stream<SlotSnapshot> slots(InventorySnapshot snapshot) {
        return Stream.concat(snapshot.stores().values().stream().flatMap(store -> store.slots().stream()),
            snapshot.residents().values().stream().flatMap(resident -> resident.pack().stream()));
    }
}
