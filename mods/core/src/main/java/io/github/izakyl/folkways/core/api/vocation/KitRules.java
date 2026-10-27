package io.github.izakyl.folkways.core.api.vocation;

import io.github.izakyl.folkways.base.Ledger;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.work.Tools;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import java.util.Collections;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

// Settings are resolved on the server thread; planners only carry these immutable item sets.
public final class KitRules {

    public record Snapshot(Map<ResourceLocation, Set<Item>> tools) {
        public Snapshot {
            Map<ResourceLocation, Set<Item>> copy = new LinkedHashMap<>();
            tools.forEach((id, items) -> copy.put(id, Set.copyOf(items)));
            tools = Map.copyOf(copy);
        }

        public boolean allows(Optional<Vocation> vocation, Item tool) {
            return vocation.isEmpty() || tools.getOrDefault(vocation.get().id(), Set.of()).contains(tool);
        }
    }

    private static volatile Function<Colony, Snapshot> rule = colony -> defaults();

    @FunctionalInterface
    public interface SupplyPolicy {
        boolean allows(Colony colony, Body body);
    }

    private static volatile Map<ResourceLocation, SupplyPolicy> supplies = Map.of();

    private KitRules() {
    }

    public static void install(Function<Colony, Snapshot> installed) {
        rule = Objects.requireNonNull(installed);
    }

    public static Snapshot defaults() {
        Map<ResourceLocation, Set<Item>> allowed = new LinkedHashMap<>();
        for (Vocation vocation : Vocations.all()) {
            Set<Item> tools = new LinkedHashSet<>();
            vocation.kit().forEach(spec -> tools.addAll(Goods.members(spec)));
            Tools.sharedGoods(vocation.id())
                .forEach(spec -> tools.addAll(Goods.members(spec)));
            allowed.put(vocation.id(), tools);
        }
        return new Snapshot(allowed);
    }

    public static Snapshot snapshot(Colony colony) {
        return rule.apply(colony);
    }

    public static boolean allows(Colony colony, Optional<Vocation> vocation, Item tool) {
        return snapshot(colony).allows(vocation, tool);
    }

    public static boolean allows(Colony colony, Vocation vocation, Item tool) {
        return allows(colony, Optional.of(vocation), tool);
    }

    /** Whether ordinary equipment delivery may run alongside this body's current controller. */
    public static synchronized void supplyPolicy(ResourceLocation owner, SupplyPolicy policy) {
        Ledger.writing(owner);
        Map<ResourceLocation, SupplyPolicy> next = new LinkedHashMap<>(supplies);
        if (next.putIfAbsent(Objects.requireNonNull(owner), Objects.requireNonNull(policy)) != null) {
            throw new IllegalStateException(owner + " already supplies an equipment delivery"
                + " policy");
        }
        supplies = Collections.unmodifiableMap(next);
    }

    public static boolean supplies(Colony colony, Body body) {
        return supplies.values().stream().allMatch(policy -> policy.allows(colony, body));
    }
}
