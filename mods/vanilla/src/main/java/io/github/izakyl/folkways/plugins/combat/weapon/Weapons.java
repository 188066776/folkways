package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.resident.ResidentKinds;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.patrol.PatrolContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.neoforged.neoforge.common.Tags;

public final class Weapons {
    private static final ResourceLocation PATROLLING = ResourceLocation.fromNamespaceAndPath("folkways", "patrolling");
    private static final ToolNeed LEGACY_MELEE = new ToolNeed.Tagged(Tags.Items.MELEE_WEAPON_TOOLS);

    public static final WeaponAdapter BOW = new NativeAdapter(NativeWeaponSession.Kind.BOW, 15);
    public static final WeaponAdapter CROSSBOW = new NativeAdapter(NativeWeaponSession.Kind.CROSSBOW, 8);
    public static final WeaponAdapter MELEE = new NativeAdapter(NativeWeaponSession.Kind.MELEE, 4.5);

    private record Candidate(WeaponAdapter adapter, ItemStack stack) {
        boolean ranged() {
            return LoadoutRegistry.using(stack).map(provider -> provider.ranged(stack)).orElseGet(adapter::ranged);
        }

        double range(Worker worker) {
            return LoadoutRegistry.using(stack)
                .map(provider -> provider.range(stack))
                .orElseGet(() -> adapter.range(worker, stack));
        }
    }

    private record Registered(ResourceLocation id, WeaponAdapter adapter) {
    }

    private static volatile List<Registered> registered =
        List.of(
        new Registered(ResourceLocation.withDefaultNamespace("bow"), BOW),
        new Registered(ResourceLocation.withDefaultNamespace("crossbow"), CROSSBOW),
        new Registered(ResourceLocation.fromNamespaceAndPath("folkways", "melee"), MELEE));

    private Weapons() {
    }

    /** Register a native extension during mod setup. Explicit adapters precede the common melee tag. */
    public static synchronized void register(ResourceLocation id, WeaponAdapter adapter) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(adapter);
        if (registered.stream().anyMatch(entry -> entry.id().equals(id))) {
            throw new IllegalArgumentException("weapon adapter already registered: " + id);
        }
        List<Registered> next = new ArrayList<>();
        next.add(new Registered(id, adapter));
        next.addAll(registered);
        registered = List.copyOf(next);
    }

    public static boolean armed(Worker worker) {
        if (!(worker.body() instanceof ResidentEntity)) {
            return barehanded(worker) || !worker.held(LEGACY_MELEE).isEmpty();
        }
        return permitted(worker) && (adapter(worker, null).isPresent() || barehanded(worker));
    }

    public static boolean ranged(Worker worker) {
        if (!(worker.body() instanceof ResidentEntity)) {
            return false;
        }
        return adapter(worker, null).filter(candidate -> candidate.ranged()).isPresent();
    }

    public static double range(Worker worker) {
        if (!(worker.body() instanceof ResidentEntity)) {
            return 4.5;
        }
        return adapter(worker, null).map(candidate -> candidate.range(worker)).orElse(4.5);
    }

    public static Optional<WeaponSession> open(Worker worker) {
        // Other ResidentKinds retain Core ToolUse combat until their own native hand ownership is
        // adapted.
        if (!(worker.body() instanceof ResidentEntity)) {
            return Optional.empty();
        }
        if (!permitted(worker)) {
            return Optional.empty();
        }
        Optional<Candidate> adapter = adapter(worker, null);
        if (adapter.isPresent()) {
            return adapter.get().adapter().open(worker, adapter.get().stack());
        }
        return barehanded(worker) ? Optional.of(NativeWeaponSession.barehanded(worker)) : Optional.empty();
    }

    private static boolean barehanded(Worker worker) {
        return ResidentKinds.barehanded(worker.resident().kind(), Optional.of(Vocations.required(PATROLLING)));
    }

    private static Optional<PatrolSupplies> supplies(Worker worker) {
        return worker.body() instanceof ResidentEntity resident && resident.level() instanceof ServerLevel level
            ? resident.colonyId()
            .flatMap(id -> Colonies.of(level.getServer(), id))
            .flatMap(PatrolContent::suppliesOf)
            : Optional.empty();
    }

    public static boolean permitted(Worker worker) {
        return supplies(worker).map(supplies -> supplies.mayEngage(worker.resident().id())).orElse(true);
    }

    public static boolean ranged(Worker worker, LivingEntity target) {
        return adapter(worker, target).filter(candidate -> candidate.ranged()).isPresent();
    }

    public static boolean indirect(Worker worker, LivingEntity target) {
        return adapter(worker, target)
            .flatMap(candidate -> LoadoutRegistry.using(candidate.stack())
                .map(provider -> provider.indirect(candidate.stack())))
            .orElse(false);
    }

    public static boolean longRange(Worker worker, LivingEntity target) {
        return adapter(worker, target).filter(Candidate::ranged)
            .flatMap(candidate -> LoadoutRegistry.using(candidate.stack())
                .map(provider -> provider.longRange(candidate.stack())))
            .orElse(false);
    }

    public static double range(Worker worker, LivingEntity target) {
        return adapter(worker, target).map(candidate -> candidate.range(worker)).orElse(4.5);
    }

    public static Optional<WeaponSession> open(Worker worker, LivingEntity target) {
        if (!(worker.body() instanceof ResidentEntity) || !permitted(worker)) {
            return Optional.empty();
        }
        return adapter(worker, target)
            .flatMap(candidate -> candidate.adapter().open(worker, candidate.stack()))
            .or(() -> barehanded(worker) ? Optional.of(NativeWeaponSession.barehanded(worker)) : Optional.empty());
    }

    /** Return the old hand before opening another; only the current work node advances either session. */
    public static WeaponSession select(Worker worker, LivingEntity target, WeaponSession current) {
        WeaponSession selected = select(worker, current, adapter(worker, target));
        return selected != null || !permitted(worker) || !barehanded(worker)
            ? selected : NativeWeaponSession.barehanded(worker);
    }

    public static WeaponSession selectRanged(Worker worker, LivingEntity target, WeaponSession current) {
        return select(worker, current, adapter(worker, target, true));
    }

    public static double rangedRange(Worker worker, LivingEntity target) {
        return adapter(worker, target, true).map(candidate -> candidate.range(worker)).orElse(0.0);
    }

    public static boolean rangedIndirect(Worker worker, LivingEntity target) {
        return adapter(worker, target, true).flatMap(candidate -> LoadoutRegistry.using(candidate.stack())
            .map(provider -> provider.indirect(candidate.stack()))).orElse(false);
    }

    private static WeaponSession select(Worker worker, WeaponSession current, Optional<Candidate> desired) {
        if (current != null
            && current.state() != WeaponSession.State.CLOSED
            && current.state() != WeaponSession.State.STOPPING
            && permitted(worker)
            && worker.body() instanceof ResidentEntity resident
            && resident.ownsWeaponHand(current)
            && desired.filter(candidate -> candidate.stack() == resident.getMainHandItem()).isPresent()) {
            return current;
        }
        if (current != null) {
            current.close();
        }
        if (!(worker.body() instanceof ResidentEntity) || !permitted(worker)) {
            return null;
        }
        return desired.flatMap(candidate -> candidate.adapter().open(worker, candidate.stack())).orElse(null);
    }

    private static Optional<Candidate> adapter(Worker worker, LivingEntity target) {
        return adapter(worker, target, false);
    }

    private static Optional<Candidate> adapter(Worker worker, LivingEntity target, boolean rangedOnly) {
        List<ItemStack> carried = new ArrayList<>();
        if (worker.body() instanceof ResidentEntity resident && resident.hasWeaponSession()) {
            carried.add(resident.getMainHandItem());
        }
        for (int slot = 0; slot < worker.pack().getContainerSize(); slot++) {
            carried.add(worker.pack().getItem(slot));
        }
        Optional<LoadoutProfile> profile =
            supplies(worker).flatMap(supplies -> supplies.profiles().assigned(worker.resident().id()));
        List<Candidate> candidates = new ArrayList<>();
        if (profile.isPresent()) {
            for (LoadoutProfile.Entry entry : profile.get().entries()) {
                if (entry.kind() != LoadoutProfile.Kind.WEAPON) {
                    continue;
                }
                for (ItemStack stack : carried) {
                    if (Goods.matches(stack, entry.items())) {
                        add(candidates, worker, stack);
                    }
                }
            }
        }
        // Existing held tools and player-given equipment remain a last resort when profile
        // equipment is unavailable.
        if (candidates.isEmpty()) {
            for (ItemStack stack : carried) {
                add(candidates, worker, stack);
            }
        }
        if (rangedOnly) {
            candidates.removeIf(candidate -> !candidate.ranged());
        }
        if (target == null) {
            return candidates.stream().findFirst();
        }
        double distance = worker.body().distanceTo(target);
        boolean sight = worker.body().hasLineOfSight(target);
        return candidates.stream()
            .min(
            Comparator.comparingInt(
            candidate -> {
                if (distance <= 4.5) {
                    return candidate.ranged() ? 1 : 0;
                }
                if (!candidate.ranged()) {
                    return 2;
                }
                boolean indirect = LoadoutRegistry.using(candidate.stack())
                    .map(provider -> provider.indirect(candidate.stack())).orElse(false);
                return sight || indirect ? distance <= candidate.range(worker) ? 0 : 1 : 3;
            }));
    }

    private static void add(List<Candidate> candidates, Worker worker, ItemStack stack) {
        registered.stream()
            .map(Registered::adapter)
            .filter(
            adapter ->
                adapter.usable(worker, stack)
                    && LoadoutRegistry.using(stack)
                    .map(provider -> provider.usable(worker, stack))
                    .orElse(true))
            .findFirst()
            .ifPresent(adapter -> candidates.add(new Candidate(adapter, stack)));
    }

    private record NativeAdapter(NativeWeaponSession.Kind kind, double range) implements WeaponAdapter {
        @Override
        public boolean ranged() {
            return kind != NativeWeaponSession.Kind.MELEE;
        }

        @Override
        public boolean usable(Worker worker, ItemStack stack) {
            if (!accepts(stack)) {
                return false;
            }
            if (!ranged() || kind == NativeWeaponSession.Kind.CROSSBOW && CrossbowItem.isCharged(stack)) {
                return true;
            }
            ProjectileWeaponItem item = (ProjectileWeaponItem) stack.getItem();
            for (int slot = 0; slot < worker.pack().getContainerSize(); slot++) {
                ItemStack ammunition = worker.pack().getItem(slot);
                if (!ammunition.isEmpty() && item.getAllSupportedProjectiles(stack).test(ammunition)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean accepts(ItemStack stack) {
            return !stack.isEmpty()
                && switch (kind) {
                    case BOW -> stack.is(Items.BOW);
                    case CROSSBOW -> stack.is(Items.CROSSBOW);
                    case MELEE -> stack.is(Tags.Items.MELEE_WEAPON_TOOLS);
                };
        }

        @Override
        public Optional<WeaponSession> open(Worker worker) {
            for (int slot = 0; slot < worker.pack().getContainerSize(); slot++) {
                ItemStack stack = worker.pack().getItem(slot);
                if (usable(worker, stack)) {
                    return open(worker, stack);
                }
            }
            for (int slot = 0; slot < worker.pack().getContainerSize(); slot++) {
                ItemStack stack = worker.pack().getItem(slot);
                if (accepts(stack)) {
                    return open(worker, stack);
                }
            }
            return Optional.empty();
        }

        @Override
        public Optional<WeaponSession> open(Worker worker, ItemStack weapon) {
            if (!(worker.body() instanceof ResidentEntity resident)
                || resident.hasWeaponSession()
                || worker.pack() != resident.pack()
                || !resident.isAlive()
                || resident.isRemoved()
                || !(resident.level() instanceof ServerLevel level)
                || !level.getServer().isSameThread()) {
                return Optional.empty();
            }
            int selected = -1;
            for (int slot = 0; slot < worker.pack().getContainerSize(); slot++) {
                if (worker.pack().getItem(slot) == weapon && accepts(weapon)) {
                    selected = slot;
                    break;
                }
            }
            if (selected < 0) {
                return Optional.empty();
            }
            ItemStack actual = worker.pack().removeItemNoUpdate(selected);
            NativeWeaponSession session = new NativeWeaponSession(worker, this, kind);
            resident.claimWeaponHand(session, actual);
            return Optional.of(session);
        }
    }
}
