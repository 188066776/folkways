package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.plugins.build.draft.Joint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * An order's work raised by the site itself rather than by hands on every block.
 *
 * <p>The plan is the one {@link Layering} lays out for the ordinary site, in the order it can be done. What changes
 * is who does it: the site fires each shot itself, as the schematicannon would, and the residents who come to the
 * site only carry what it takes and keep it going. A cell that needs something under it to stand is left until
 * that thing is there; everything else goes up as soon as its materials are in a hand at the site.
 *
 * <p>Only created when {@link EfficientConstruction#active()} is set by the add-on, so a world without it never
 * reaches this class.
 */
final class EfficientBuilder {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("folkways-build");

    /** How many shots one tick may fire whatever the crowd, so a whole colony cannot stall the server. */
    private static final int MOST_SHOTS_A_TICK = 64;

    /** How many hands one asking may bring, so the plan is not flooded with journeys at once. */
    private static final int HANDS_A_PASS = 2;

    /** How long the site keeps asking for hands when nothing at all can be laid. */
    private static final int IDLE_TICKS = 200;

    /** How many of one material one hand is asked to bring in a trip, before the stack setting multiplies it. */
    private static final long BASKET_PER_KIND = 16L;

    private final UUID order;
    private final BuildSite.Asks asks;
    private final BuildTarget target;
    private final Set<BlockPos> latched;
    private final Set<Joint> joined;
    private final Map<BlockPos, BuildRefusal> refused;

    private final List<Layering.Work> works = new ArrayList<>();
    private final Set<Integer> finished = new HashSet<>();
    private final Map<UUID, PresenceNode> present = new LinkedHashMap<>();
    private final List<BlockPos> stances = new ArrayList<>();

    private double credit;
    private boolean closed;
    private long idleSince = Long.MIN_VALUE;
    private long staffAt = Long.MIN_VALUE;
    private int asked;
    private boolean toldFirstShot;
    private BlockPos anchor = BlockPos.ZERO;
    private ServerLevel standing;

    EfficientBuilder(UUID order, BuildSite.Asks asks, BuildTarget target, Set<BlockPos> latched,
            Set<Joint> joined, Map<BlockPos, BuildRefusal> refused) {
        this.order = order;
        this.asks = asks;
        this.target = target;
        this.latched = latched;
        this.joined = joined;
        this.refused = refused;
    }

    /** Takes the order up: what was met before, then the plan for what is left. */
    void start(ServerLevel level, Set<BlockPos> met, Set<Joint> metJoints) {
        standing = level;
        met.forEach(cell -> latched.add(cell.offset(target.storageOrigin())));
        metJoints.forEach(joint -> joined.add(joint.moved(target.storageOrigin())));
        for (Map.Entry<BlockPos, BlockState> cell : target.cells().entrySet()) {
            if (!latched.contains(cell.getKey()) && level.hasChunkAt(cell.getKey())
                && target.satisfied(level, cell.getKey())) {
                latched.add(cell.getKey());
            }
        }
        latched.retainAll(target.cells().keySet());
        joined.retainAll(target.joints());
        Layering.Plan plan = Layering.of(level, target, latched);
        refused.putAll(plan.refused());
        works.addAll(plan.works());
        for (Layering.Work work : works) {
            Optional<Step> step = work.step();
            if (step.isEmpty() || work.kind() == Layering.Kind.SCAFFOLD) {
                finished.add(work.id());
                continue;
            }
            if (work.joint().isPresent() && joined.contains(work.joint().get())) {
                finished.add(work.id());
                continue;
            }
            if (satisfied(level, step.get())) {
                finished.add(work.id());
            }
        }
        stances.addAll(findStances(level));
        anchor = target.cells().keySet().stream().findFirst().orElse(BlockPos.ZERO);
        LOGGER.info("efficient site {}: {} cell(s), {} piece(s), {} stance(s)", shortId(),
            target.cells().size(), works.size(), stances.size());
        staff();
    }

    /** One turn of the site: hands asked for, and the shots those hands pay for fired. */
    boolean tick(ServerLevel level) {
        if (closed) {
            return false;
        }
        standing = level;
        boolean changed = false;
        if (nothingLeft()) {
            return false;
        }
        credit += arrived() / (double) EfficientConstruction.settings().ticksPerBlock();
        int fired = 0;
        while (credit >= 1.0D && fired < MOST_SHOTS_A_TICK) {
            if (!fire(level)) {
                break;
            }
            credit -= 1.0D;
            fired++;
            changed = true;
        }
        if (fired == 0) {
            credit = Math.min(credit, 1.0D);
        }
        if (fired > 0) {
            idleSince = Long.MIN_VALUE;
        } else if (idleSince == Long.MIN_VALUE) {
            idleSince = level.getGameTime();
        }
        if (!present.isEmpty() && arrived() == 0 && level.getGameTime() % 200L == 0L) {
            LOGGER.info("efficient site {}: {} hand(s) asked, none here yet", shortId(), present.size());
        }
        staff();
        return changed;
    }

    private boolean fire(ServerLevel level) {
        for (Layering.Work work : works) {
            if (finished.contains(work.id())) {
                continue;
            }
            Optional<Step> maybe = work.step();
            if (maybe.isEmpty()) {
                finished.add(work.id());
                continue;
            }
            Step step = maybe.get();
            if (work.kind() == Layering.Kind.JOIN) {
                if (join(level, work, step)) {
                    finished.add(work.id());
                    return true;
                }
                continue;
            }
            if (satisfied(level, step)) {
                finished.add(work.id());
                continue;
            }
            if (!stands(level, step)) {
                continue;
            }
            BuildJob job = step.job(level, target, Stances.WHEREVER, latched);
            if (!affordable(job.cost())) {
                continue;
            }
            pay(job.cost());
            lay(level, job);
            finished.add(work.id());
            latch(level, step);
            if (!toldFirstShot) {
                toldFirstShot = true;
                LOGGER.info("efficient site {}: first block at {} ({} piece(s) left)", shortId(),
                    step.focus(), works.size() - finished.size());
            }
            return true;
        }
        return false;
    }

    // A block that needs something to lean on, or hangs in the air until it falls, waits for what holds it up.
    private static boolean stands(ServerLevel level, Step step) {
        BlockState wanted = step.after().get(step.focus());
        if (wanted == null || wanted.isAir()) {
            return true;
        }
        if (!wanted.canSurvive(level, step.focus())) {
            return false;
        }
        if (wanted.getBlock() instanceof FallingBlock) {
            BlockPos below = step.focus().below();
            return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
        }
        return true;
    }

    private void lay(ServerLevel level, BuildJob job) {
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        job.before().forEach((pos, state) -> {
            BlockPos at = pos.cell();
            if (level.hasChunkAt(at)) {
                before.put(at, level.getBlockState(at));
            }
        });
        Map<BlockPos, BlockState> after = new LinkedHashMap<>();
        job.after().forEach((pos, state) -> {
            BlockPos at = pos.cell();
            if (!latched.contains(at)) {
                after.put(at, state);
            }
        });
        if (after.isEmpty()) {
            return;
        }
        Set<BlockPos> shot = new LinkedHashSet<>();
        job.shot().forEach(pos -> shot.add(pos.cell()));
        Map<BlockPos, BlockData> data = new LinkedHashMap<>();
        job.data().forEach((pos, payload) -> data.put(pos.cell(), payload));
        spill(level, before.keySet());
        List<ItemStack> drops = Rubble.lay(level, null, before, after, shot, job.focus().cell(), data,
            ItemStack.EMPTY, SchematicPlacement.icon(job.cost()), true);
        // What the site broke is the colony's, as it is when a resident breaks it: left where it fell.
        for (ItemStack drop : drops) {
            Block.popResource(level, job.focus().cell(), drop);
        }
    }

    // Whatever a broken cell held is put on the ground rather than lost with the block.
    private static void spill(ServerLevel level, Set<BlockPos> cells) {
        for (BlockPos cell : cells) {
            BlockEntity entity = level.getBlockEntity(cell);
            if (!(entity instanceof Container container)) {
                continue;
            }
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty()) {
                    Block.popResource(level, cell, stack);
                    container.setItem(slot, ItemStack.EMPTY);
                }
            }
        }
    }

    private boolean join(ServerLevel level, Layering.Work work, Step step) {
        Joint joint = work.joint().orElse(null);
        if (joint == null) {
            return true;
        }
        if (joined.contains(joint) || Joints.joiner().joined(level, joint.from(), joint.to())) {
            joined.add(joint);
            return true;
        }
        // What a turn between two laid ends costs is read off the blocks it joins, as the ordinary site reads it.
        Optional<List<ItemStack>> quoted = Joints.joiner().cost(level, joint.from(), joint.to());
        if (quoted.isEmpty()) {
            return false;
        }
        List<Need> needs = new ArrayList<>();
        for (ItemStack stack : quoted.get()) {
            needs.add(new Need(ItemSpec.of(BuiltInRegistries.ITEM.getKey(stack.getItem())), stack.getCount()));
        }
        if (!affordable(needs)) {
            return false;
        }
        List<ItemStack> paid = pay(needs);
        Joints.Joining joining = Joints.joiner().join(level, joint.from(), joint.to(), paid);
        if (joining.joined()) {
            joined.add(joint);
            return true;
        }
        joining.left().forEach(stack -> Block.popResource(level, joint.from(), stack));
        return false;
    }

    private void latch(ServerLevel level, Step step) {
        for (BlockPos cell : step.after().keySet()) {
            if (target.cells().containsKey(cell) && !latched.contains(cell) && target.satisfied(level, cell)) {
                latched.add(cell);
                refused.remove(cell);
            }
        }
    }

    private boolean satisfied(ServerLevel level, Step step) {
        for (Map.Entry<BlockPos, BlockState> cell : step.after().entrySet()) {
            if (latched.contains(cell.getKey())) {
                continue;
            }
            if (!BuildPlanner.satisfied(level.getBlockState(cell.getKey()), cell.getValue())) {
                return false;
            }
        }
        return true;
    }

    /** Every cell met and every joint made: the order is built. */
    boolean complete() {
        return !closed && latched.size() == target.cells().size() && joined.containsAll(target.joints());
    }

    private boolean nothingLeft() {
        return latched.size() >= target.cells().size() && joined.containsAll(target.joints());
    }

    // The hands at the site, asked for as far as there are places to stand and work to do. A few at a time and no
    // more often than the colony can take them: a hand that comes back empty-handed must not be replaced at once,
    // or the site would ask for a fresh journey every tick and drown the plan in journeys that go nowhere.
    void staff() {
        ServerLevel level = standing;
        if (closed || level == null || nothingLeft()) {
            return;
        }
        if (level.getGameTime() < staffAt) {
            return;
        }
        int wanted = Math.min(EfficientConstruction.settings().workersPerSite(), Math.max(1, stances.size()));
        int added = 0;
        while (present.size() < wanted && added < HANDS_A_PASS) {
            // With no place found to stand, the colony is still asked: the resident makes its own way to the site.
            BlockPos stance = stances.isEmpty() ? null : stances.get(asked % stances.size());
            asked++;
            PresenceNode node = new PresenceNode(this, stance);
            present.put(node.id(), node);
            asks.submit(level.dimension(), Grown.of(node), this::ended);
            added++;
        }
        if (added > 0) {
            LOGGER.info("efficient site {}: asked for {} hand(s), {} at the site", shortId(), added, present.size());
        }
    }

    /** Holds the next asking back, so a site nothing can be done at does not ask again in the same breath. */
    void rest(long ticks) {
        ServerLevel level = standing;
        if (level != null) {
            staffAt = level.getGameTime() + ticks;
        }
    }

    void ended(UUID node, Ending how) {
        present.remove(node);
    }

    void leave(PresenceNode node) {
        present.remove(node.id());
    }

    boolean idle(ServerLevel level) {
        return idleSince != Long.MIN_VALUE && level.getGameTime() - idleSince > IDLE_TICKS;
    }

    // A resident has just come: whatever kept the site idle until now is not this one's to answer for.
    void touched() {
        idleSince = Long.MIN_VALUE;
    }

    /** A hand reached the site; what it brought is what the site can spend from now on. */
    void handArrived(PresenceNode node) {
        int stacks = 0;
        for (ItemStack stack : node.held()) {
            stacks += stack.isEmpty() ? 0 : 1;
        }
        if (stacks == 0) {
            LOGGER.info("efficient site {}: a hand arrived empty", shortId());
        } else {
            LOGGER.info("efficient site {}: a hand arrived with {} stack(s)", shortId(), stacks);
        }
    }

    private String shortId() {
        return order.toString().substring(0, 8);
    }

    List<PresenceNode> hands() {
        return List.copyOf(present.values());
    }

    // Only residents who have actually come to the site carry materials or add to its pace.
    private int arrived() {
        int at = 0;
        for (PresenceNode node : present.values()) {
            at += node.arrived() ? 1 : 0;
        }
        return at;
    }

    NodeSpec spec(PresenceNode node, BlockPos stance) {
        BlockPos where = stance == null ? anchor : stance;
        Stances footing = stance == null ? Stances.WHEREVER : Stances.at(standing, stance);
        return NodeSpec.of(UUID.randomUUID(), BuildContent.ID, new WorkSite.AtBlock(target.cell(where)), footing,
                node)
            .needs(needs())
            .vocation(BuildContent.trade())
            .gesture(WorkGesture.SWING)
            .focus(where)
            .estimate(100)
            .doing(BuildContent.BUILDING)
            .done();
    }

    // What a hand coming now would usefully bring: the kinds the rest of the plan spends most of, in a basket one
    // resident can gather and carry. Asking for a whole stack of everything the plan will ever need is a basket the
    // colony may never be able to fill, and a hand that never comes builds nothing.
    List<Need> needs() {
        Map<ItemSpec, Long> wanted = new LinkedHashMap<>();
        for (Layering.Work work : works) {
            if (finished.contains(work.id())) {
                continue;
            }
            work.step().ifPresent(step -> step.cost()
                .forEach(need -> wanted.merge(need.spec(), need.count(), Long::sum)));
        }
        long cap = BASKET_PER_KIND * EfficientConstruction.settings().materialStacks();
        return wanted.entrySet().stream()
            .sorted(Map.Entry.<ItemSpec, Long>comparingByValue().reversed())
            .limit(EfficientConstruction.settings().materialKinds())
            .map(entry -> new Need(entry.getKey(), Math.min(entry.getValue(), cap), true))
            .toList();
    }

    private boolean affordable(List<Need> cost) {
        for (Need need : cost) {
            if (held(need.spec()) < need.count()) {
                return false;
            }
        }
        return true;
    }

    private long held(ItemSpec spec) {
        long count = 0L;
        for (PresenceNode node : present.values()) {
            if (node.arrived()) {
                count += node.holding(spec);
            }
        }
        return count;
    }

    private List<ItemStack> pay(List<Need> cost) {
        List<ItemStack> paid = new ArrayList<>();
        for (Need need : cost) {
            long left = need.count();
            for (PresenceNode node : present.values()) {
                if (left <= 0) {
                    break;
                }
                if (node.arrived()) {
                    left -= node.take(need.spec(), left, paid);
                }
            }
        }
        return paid;
    }

    /** Gives the order up: every hand is turned away and the site hears no more. */
    void close() {
        closed = true;
        present.keySet().forEach(asks::withdraw);
        present.clear();
    }

    // Where a resident can stand and still be on the site: room to stand, room to breathe, floor underfoot, and
    // nothing the order still means to put there.
    private List<BlockPos> findStances(ServerLevel level) {
        List<BlockPos> found = new ArrayList<>();
        if (target.cells().isEmpty()) {
            return found;
        }
        BlockPos min = null;
        BlockPos max = null;
        for (BlockPos cell : target.cells().keySet()) {
            min = min == null ? cell : BlockPos.min(min, cell);
            max = max == null ? cell : BlockPos.max(max, cell);
        }
        if (min == null || max == null) {
            return found;
        }
        collect(level, min, max, found);
        if (found.isEmpty()) {
            collect(level, min.offset(-2, -1, -2), max.offset(2, 1, 2), found);
        }
        return found;
    }

    private void collect(ServerLevel level, BlockPos min, BlockPos max, List<BlockPos> into) {
        for (BlockPos cell : BlockPos.betweenClosed(min, max)) {
            if (into.size() >= 16) {
                return;
            }
            BlockPos at = cell.immutable();
            if (target.occupies(level, at) || target.cells().containsKey(at)) {
                continue;
            }
            if (!level.hasChunkAt(at) || !level.getFluidState(at).isEmpty()
                || !level.getFluidState(at.above()).isEmpty()) {
                continue;
            }
            if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()
                || !level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty()) {
                continue;
            }
            BlockPos floor = at.below();
            if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) {
                continue;
            }
            into.add(at);
        }
    }

    UUID order() {
        return order;
    }

    @Nullable
    ServerLevel standing() {
        return standing;
    }
}
