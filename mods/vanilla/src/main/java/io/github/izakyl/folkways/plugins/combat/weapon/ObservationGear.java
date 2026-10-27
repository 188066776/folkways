package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** Observation equipment uses ordinary profile supplies and must actually remain in the pack. */
public final class ObservationGear {
    private static final List<ResourceLocation> ITEMS = List.of(
        ResourceLocation.withDefaultNamespace("spyglass"),
        ResourceLocation.fromNamespaceAndPath("superbwarfare", "binoculars"),
        ResourceLocation.fromNamespaceAndPath("superbwarfare", "spyglass"));

    private ObservationGear() {
    }

    public static List<ItemStack> choices() {
        return ITEMS.stream().filter(BuiltInRegistries.ITEM::containsKey)
            .map(id -> new ItemStack(BuiltInRegistries.ITEM.get(id))).toList();
    }

    public static boolean carried(ResidentEntity body) {
        if (!(body.level() instanceof ServerLevel level)) {
            return false;
        }
        return body.colonyId().flatMap(id -> Colonies.of(level.getServer(), id))
            .flatMap(colony -> LoadoutProfiles.of(colony).assigned(body.id()))
            .filter(profile -> profile.entries().stream()
                .filter(entry -> entry.kind() == LoadoutProfile.Kind.SUPPLY)
                .anyMatch(entry -> body.pack().contents().stream()
                    .anyMatch(stack -> !stack.isEmpty()
                        && ITEMS.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                        && entry.matches(stack))))
            .isPresent();
    }
}
