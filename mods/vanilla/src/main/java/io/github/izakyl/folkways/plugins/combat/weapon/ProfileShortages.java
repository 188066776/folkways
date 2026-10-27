package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/** A stock preview, never a reservation. Loaded members consume preview units once in roster order. */
public record ProfileShortages(
    int assigned, int incomplete, int unloaded, Map<UUID, Integer> missing, Map<UUID, Integer> unavailableStock) {
    public static ProfileShortages read(Colony colony, LoadoutProfile profile, MinecraftServer server) {
        PatrolSupplies supplies = PatrolSupplies.of(colony);
        Map<UUID, Integer> missing = new LinkedHashMap<>();
        Map<UUID, Integer> unavailable = new LinkedHashMap<>();
        Map<WorldPos, SimpleContainer> stock = new LinkedHashMap<>();
        int incomplete = 0, unloaded = 0;
        List<UUID> members = supplies.profiles().residents(profile.id());
        for (UUID member : members) {
            ResidentEntity body = null;
            for (ServerLevel level : server.getAllLevels()) {
                if (level.getEntity(member) instanceof ResidentEntity person) {
                    body = person;
                    break;
                }
            }
            if (body == null) {
                unloaded++;
                continue;
            }
            ResidentEntity person = body;
            Map<UUID, Integer> deficits = supplies.deficits(body, profile);
            boolean blocked =
                profile.entries().stream()
                .filter(entry -> entry.slot().isPresent())
                .anyMatch(
                entry ->
                    !person.getItemBySlot(entry.slot().orElseThrow()).isEmpty()
                        && !entry.matches(
                        person.getItemBySlot(entry.slot().orElseThrow())));
            if (blocked
                || deficits.values().stream().anyMatch(count -> count > 0)
                || profile.entries().isEmpty() && profile.legacy().isPresent()) {
                incomplete++;
            }
            ServerLevel level = (ServerLevel) body.level();
            List<WorldPos> nearby =
                colony.storesIn(level.dimension()).stream()
                .filter(
                source ->
                    source.sameRealm(WorldSpaces.at(person))
                        && WorldSpaces.world(level, source)
                        .filter(
                        at ->
                            at.distanceToSqr(person.position())
                                <= 32 * 32)
                        .isPresent())
                .sorted(
                Comparator.comparingDouble(
                source ->
                    WorldSpaces.world(level, source)
                        .orElseThrow()
                        .distanceToSqr(person.position())))
                .limit(32)
                .toList();
            for (LoadoutProfile.Entry entry : profile.entries()) {
                int left = deficits.get(entry.id());
                missing.merge(entry.id(), left, Integer::sum);
                for (WorldPos source : nearby) {
                    if (left == 0) {
                        break;
                    }
                    var sourceStock = supplies.source(level, source);
                    if (sourceStock.isEmpty()) {
                        continue;
                    }
                    WorldPos anchor = WorldPos.of(level, Containers.anchor(level, source.block(level)));
                    stock.computeIfAbsent(anchor, ignored -> supplies.unreserved(level, source, sourceStock.get()));
                    SimpleContainer available = stock.get(anchor);
                    while (left > 0) {
                        ItemStack taken = Containers.extract(available, entry::matches, left);
                        if (taken.isEmpty()) {
                            break;
                        }
                        left -= taken.getCount();
                    }
                }
                unavailable.merge(entry.id(), left, Integer::sum);
            }
        }
        return new ProfileShortages(members.size(), incomplete, unloaded, Map.copyOf(missing), Map.copyOf(unavailable));
    }
}
