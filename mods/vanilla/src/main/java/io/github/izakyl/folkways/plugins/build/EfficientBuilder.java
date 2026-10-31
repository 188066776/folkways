package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import io.github.izakyl.folkways.core.engine.labor.LaborRefusal;
import io.github.izakyl.folkways.plugins.build.draft.Joint;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
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
 * is who does it: a resident standing on the site is handed one shot at a time ({@link ShotNode}), and the site fires
 * it as the schematicannon would. No resident walks to a block, so nothing of the site's cost is pathfinding, and a
 * cell that needs something under it to stand is left until that thing is there.
 *
 * <p>Only created when {@link EfficientConstruction#active()} is set by the add-on, so a world without it never
 * reaches this class.
 */
final class EfficientBuilder {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("folkways-build");

    /** How many hands one asking may bring, so the plan is not flooded with journeys at once. */
    private static final int HANDS_A_PASS = 2;

    /** How long a hand stays at a site nothing has come of before it goes back to its other work. */
    private static final int PATIENCE_TICKS = 600;

    /** How many pieces one hand is given at a time: its own run of the plan, and the goods that run spends. */
    private static final int SLICE_PER_HAND = 24;

    /**
     * How long a hand may be asked for and not come before the asking is taken back and made again. This is a
     * backstop for a journey that never ends, not a travel budget: a resident walking to a site a hundred blocks
     * out takes minutes, and taking the asking back under it only sends another resident out behind it.
     */
    private static final int ASK_TICKS = 6000;

    private final UUID order;
    private final BuildSite.Asks asks;
    private final BuildTarget target;
    private final Set<BlockPos> latched;
    private final Set<Joint> joined;
    private final Map<BlockPos, BuildRefusal> refused;

    private final List<Layering.Work> works = new ArrayList<>();
    private final Map<Integer, Layering.Work> byId = new LinkedHashMap<>();
    private final Set<Integer> finished = new HashSet<>();
    private final Set<Integer> claimed = new HashSet<>();
    private final Map<UUID, PresenceNode> present = new LinkedHashMap<>();
    private final Map<UUID, Long> askedAt = new LinkedHashMap<>();
    private final List<BlockPos> stances = new ArrayList<>();

    /** Where the next slice of the plan begins: each hand takes a run of pieces and builds only those. */
    private int sliceAt;

    /** Runs handed to a hand that never came, put back in front of the cursor rather than behind it. */
    private final Set<Integer> abandoned = new LinkedHashSet<>();

    private boolean closed;
    private long staffAt = Long.MIN_VALUE;
    private long lastShotAt = Long.MIN_VALUE;
    private long toldWhyAt = Long.MIN_VALUE;
    private int asked;
    private boolean toldFirstShot;
    private boolean dirty;
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
        lastShotAt = level.getGameTime();
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
        works.forEach(work -> byId.put(work.id(), work));
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
            if (work.kind() != Layering.Kind.JOIN && satisfied(level, step.get())) {
                // What the world already holds is met as well, or the site would never come to its end.
                latch(level, step.get());
                finished.add(work.id());
            }
        }
        stances.addAll(findStances(level));
        anchor = target.cells().keySet().stream().findFirst().orElse(BlockPos.ZERO);
        LOGGER.info("efficient site {}: {} cell(s), {} piece(s), {} stance(s)", shortId(),
            target.cells().size(), works.size(), stances.size());
        if (target.cells().isEmpty()) {
            return;
        }
        LOGGER.info("efficient site {}: up to {} piece(s) a hand", shortId(), SLICE_PER_HAND);
        staff();
    }

    /** One turn of the site: hands asked for, and one hand kept company. */
    boolean tick(ServerLevel level) {
        if (closed) {
            return false;
        }
        standing = level;
        if (nothingLeft()) {
            return false;
        }
        forgetHandsThatNeverCame(level);
        if (!present.isEmpty() && arrived() == 0 && level.getGameTime() % 200L == 0L) {
            LOGGER.info("efficient site {}: {} hand(s) asked, none here yet", shortId(), present.size());
        }
        staff();
        boolean changed = dirty;
        dirty = false;
        return changed;
    }

    /** What a hand that is here does next: a shot of its own slice of the plan, in the order the plan lays it. */
    Workload.Within.Turn hand(ServerLevel level, PresenceNode hand) {
        if (closed || complete()) {
            return Workload.Within.LEAVE;
        }
        if (claimed.isEmpty() && level.getGameTime() - lastShotAt > PATIENCE_TICKS) {
            rest(200);
            return Workload.Within.LEAVE;
        }
        int unsupported = 0;
        int broke = 0;
        boolean mine = false;
        for (int id : hand.slice()) {
            Layering.Work work = byId.get(id);
            if (work == null || finished.contains(id) || claimed.contains(id)) {
                continue;
            }
            mine = true;
            Optional<Step> maybe = work.step();
            if (maybe.isEmpty() || work.kind() == Layering.Kind.SCAFFOLD) {
                finished.add(id);
                continue;
            }
            Step step = maybe.get();
            if (work.kind() == Layering.Kind.JOIN) {
                Joint joint = work.joint().orElse(null);
                if (joint == null || joined.contains(joint)) {
                    finished.add(id);
                    continue;
                }
            } else if (satisfied(level, step)) {
                latch(level, step);
                finished.add(id);
                continue;
            }
            if (!stands(level, step)) {
                unsupported++;
                continue;
            }
            BuildJob job;
            try {
                job = step.job(level, target, Stances.WHEREVER, latched);
            } catch (RuntimeException broken) {
                if (broke++ == 0) {
                    LOGGER.warn("efficient site {}: piece {} will not address", shortId(), id, broken);
                }
                continue;
            }
            List<Need> cost = work.kind() == Layering.Kind.JOIN ? joinCost(level, work) : job.cost();
            if (cost == null) {
                continue;
            }
            // Only what this hand actually carries: a piece it cannot pay for would come back for want of goods.
            if (!hand.canPay(cost)) {
                continue;
            }
            claimed.add(id);
            BlockPos stance = hand.stance() == null ? anchor : hand.stance();
            hand.paid();
            return new Workload.Within.Turn.Piece(new ShotNode(this, hand, work, job, stance, cost, level));
        }
        if (level.getGameTime() - toldWhyAt >= 200L) {
            toldWhyAt = level.getGameTime();
            LOGGER.info("efficient site {}: nothing up, finished={} claimed={} unsupported={} broken={}",
                shortId(), finished.size(), claimed.size(), unsupported, broke);
        }
        // Its own run of the plan is built: this hand is done, and another takes the next run.
        if (!mine) {
            rest(40);
            return Workload.Within.LEAVE;
        }
        hand.waited(level);
        if (hand.bored(level)) {
            rest(100);
            return Workload.Within.LEAVE;
        }
        return Workload.Within.WAIT;
    }

    /** Fires the shot the hand was given: what the goods it was handed pay for lands in the world. */
    Outcome shoot(Layering.Work work, BuildJob job, ServerLevel level, Worker who) {
        claimed.remove(work.id());
        dirty = true;
        lastShotAt = level.getGameTime();
        if (work.kind() == Layering.Kind.JOIN) {
            Joint joint = work.joint().orElse(null);
            boolean made = false;
            if (joint != null) {
                Joints.Joining joining = Joints.joiner().join(level, joint.from(), joint.to(), who.supplied());
                if (joining.joined()) {
                    joined.add(joint);
                    made = true;
                }
                joining.left().forEach(stack -> Block.popResource(level, joint.from(), stack));
            }
            // A turn that did not join is not done: it is up for another hand, not marked behind the site's back.
            if (made) {
                finished.add(work.id());
            }
        } else {
            // Nobody is laid into a wall: a body standing where the shot goes is stepped aside, and a shot that
            // cannot be stepped clear waits rather than sealing someone in.
            if (!Bystanders.clear(level, fill(job), stances)) {
                return Outcome.failed(BuildRefusal.OCCUPIED);
            }
            List<ItemStack> drops = lay(level, job);
            work.step().ifPresent(step -> latch(level, step));
            drops.forEach(stack -> Block.popResource(level, job.focus().cell(), stack));
            finished.add(work.id());
        }
        if (!toldFirstShot && !finished.isEmpty()) {
            toldFirstShot = true;
            LOGGER.info("efficient site {}: first block at {} ({} piece(s) left)", shortId(),
                job.focus().cell(), works.size() - finished.size());
        }
        return new Outcome.Done(
            List.of(WorkNoise.placed(job.focus().cell(), level.getBlockState(job.focus().cell()))),
            List.of(), Optional.of(new Xp(BuildContent.trade(), 1)), List.of());
    }

    /** The shot was given up before it was fired: the piece is up for another hand. */
    void release(Layering.Work work) {
        claimed.remove(work.id());
    }

    /** The core has handed a shot back: the piece is free again, and the site says what became of it. */
    void dropped(ShotNode shot, Ending how) {
        claimed.remove(shot.work().id());
        if (how instanceof Ending.Failed failed && failed.why() == LaborRefusal.GOODS_GONE) {
            shot.hand().drain();
        }
        if (!(how instanceof Ending.Done)) {
            LOGGER.info("efficient site {}: a shot at {} came back {}", shortId(), shot.focus(), how);
        }
    }

    // The cells of a shot that are still to be filled, as the world stands now.
    private Map<BlockPos, BlockState> fill(BuildJob job) {
        Map<BlockPos, BlockState> after = new LinkedHashMap<>();
        job.after().forEach((pos, state) -> {
            BlockPos at = pos.cell();
            if (!latched.contains(at)) {
                after.put(at, state);
            }
        });
        return after;
    }

    private List<ItemStack> lay(ServerLevel level, BuildJob job) {
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        job.before().forEach((pos, state) -> {
            BlockPos at = pos.cell();
            if (level.hasChunkAt(at)) {
                before.put(at, level.getBlockState(at));
            }
        });
        Map<BlockPos, BlockState> after = fill(job);
        if (after.isEmpty()) {
            return List.of();
        }
        Set<BlockPos> shot = new LinkedHashSet<>();
        job.shot().forEach(pos -> shot.add(pos.cell()));
        Map<BlockPos, BlockData> data = new LinkedHashMap<>();
        job.data().forEach((pos, payload) -> data.put(pos.cell(), payload));
        spill(level, before.keySet());
        return Rubble.lay(level, null, before, after, shot, job.focus().cell(), data, ItemStack.EMPTY,
            SchematicPlacement.icon(job.cost()), true);
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

    // What a turn between two laid ends costs, read off the blocks it joins. Null when the joiner cannot quote it.
    @Nullable
    private static List<Need> joinCost(ServerLevel level, Layering.Work work) {
        Joint joint = work.joint().orElse(null);
        if (joint == null) {
            return List.of();
        }
        Optional<List<ItemStack>> quoted = Joints.joiner().cost(level, joint.from(), joint.to());
        if (quoted.isEmpty()) {
            return null;
        }
        List<Need> needs = new ArrayList<>();
        for (ItemStack stack : quoted.get()) {
            needs.add(new Need(ItemSpec.of(BuiltInRegistries.ITEM.getKey(stack.getItem())), stack.getCount()));
        }
        return needs;
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

    /** Every cell met and every joint made: the order is built. */
    boolean complete() {
        return !closed && latched.size() == target.cells().size() && joined.containsAll(target.joints());
    }

    private boolean nothingLeft() {
        return latched.size() >= target.cells().size() && joined.containsAll(target.joints());
    }

    // The hands at the site, asked for as far as there are places to stand and work to do. A few at a time and no
    // more often than the colony can take them.
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
            // Each hand is given its own run of the plan, so what it is asked to carry is exactly what it will lay.
            List<Integer> slice = nextSlice();
            if (slice.isEmpty()) {
                return;
            }
            BlockPos stance = stances.isEmpty() ? null : stances.get(asked % stances.size());
            asked++;
            PresenceNode node = new PresenceNode(this, stance, slice);
            present.put(node.id(), node);
            askedAt.put(node.id(), level.getGameTime());
            asks.submit(level.dimension(), Grown.of(node), this::ended);
            added++;
        }
        if (added > 0) {
            LOGGER.info("efficient site {}: asked for {} hand(s), {} at the site", shortId(), added, present.size());
        }
    }

    /**
     * The next run of the plan nobody has taken: collected in the plan's own order, so a hand's run can be built
     * front to back, and wrapping round once everything behind has been handed out.
     */
    private List<Integer> nextSlice() {
        if (works.isEmpty()) {
            return List.of();
        }
        List<Integer> slice = new ArrayList<>();
        // Runs given up by a hand that never came are taken first, or they would wait for the cursor to come round.
        Iterator<Integer> given = abandoned.iterator();
        while (given.hasNext() && slice.size() < SLICE_PER_HAND) {
            int id = given.next();
            given.remove();
            if (!finished.contains(id)) {
                slice.add(id);
            }
        }
        int scanned = 0;
        while (slice.size() < SLICE_PER_HAND && scanned < works.size()) {
            if (sliceAt >= works.size()) {
                sliceAt = 0;
            }
            Layering.Work work = works.get(sliceAt++);
            scanned++;
            if (finished.contains(work.id())) {
                continue;
            }
            slice.add(work.id());
        }
        return slice;
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
        askedAt.remove(node);
    }

    void leave(PresenceNode node) {
        present.remove(node.id());
        askedAt.remove(node.id());
    }

    // A hand asked for long ago that has never come is a place on the site nobody is using. The asking is taken
    // back, so the colony may send someone who can actually get there. The asking is collected first and the map
    // changed after, since taking one back may answer at once.
    private void forgetHandsThatNeverCame(ServerLevel level) {
        long now = level.getGameTime();
        List<UUID> stale = new ArrayList<>();
        for (Map.Entry<UUID, PresenceNode> entry : present.entrySet()) {
            Long since = askedAt.get(entry.getKey());
            if (since != null && !entry.getValue().arrived() && now - since > ASK_TICKS) {
                stale.add(entry.getKey());
            }
        }
        for (UUID node : stale) {
            asks.withdraw(node);
            PresenceNode gone = present.remove(node);
            if (gone != null) {
                abandoned.addAll(gone.slice());
            }
            askedAt.remove(node);
            LOGGER.info("efficient site {}: a hand never came; asking again", shortId());
        }
    }

    // A resident has just come: whatever kept the site idle until now is not this one's to answer for.
    void touched() {
        lastShotAt = standing == null ? lastShotAt : standing.getGameTime();
    }

    void handArrived(PresenceNode node, Worker who) {
        int stacks = 0;
        Container pack = who.pack();
        for (int slot = 0; slot < pack.getContainerSize(); slot++) {
            stacks += pack.getItem(slot).isEmpty() ? 0 : 1;
        }
        LOGGER.info("efficient site {}: a hand is here with {} stack(s)", shortId(), stacks);
    }

    private int arrived() {
        int at = 0;
        for (PresenceNode node : present.values()) {
            at += node.arrived() ? 1 : 0;
        }
        return at;
    }

    /** The stay a resident is kept in while it is on the site. It carries exactly what its own run will spend. */
    NodeSpec staySpec(PresenceNode node, @Nullable BlockPos stance, List<Integer> slice) {
        BlockPos where = stance == null ? anchor : stance;
        Stances footing = stance == null ? Stances.WHEREVER : Stances.at(standing, stance);
        return NodeSpec.of(UUID.randomUUID(), BuildContent.ID, new WorkSite.AtBlock(target.cell(where)), footing,
                node)
            .needs(needs(slice))
            .vocation(BuildContent.trade())
            .gesture(WorkGesture.SWING)
            .focus(where)
            .estimate(100)
            .doing(BuildContent.BUILDING)
            .done();
    }

    // What one hand's run of the plan costs, in the kinds and counts it will actually lay. The site's shots have no
    // reservation of their own, so this is what has to be in the pack when the run is built.
    List<Need> needs(List<Integer> slice) {
        ServerLevel level = standing;
        if (level == null || slice.isEmpty()) {
            return List.of();
        }
        Map<ItemSpec, Long> wanted = new LinkedHashMap<>();
        for (int id : slice) {
            Layering.Work work = byId.get(id);
            if (work == null || finished.contains(id) || work.kind() == Layering.Kind.JOIN) {
                continue;
            }
            Optional<Step> maybe = work.step();
            if (maybe.isEmpty()) {
                continue;
            }
            try {
                BuildJob job = maybe.get().job(level, target, Stances.WHEREVER, latched);
                for (Need need : job.cost()) {
                    wanted.merge(need.spec(), need.count(), Long::sum);
                }
            } catch (RuntimeException broken) {
                // A piece that will not address is left out of the basket and skipped when the run is built.
                LOGGER.warn("efficient site {}: piece {} will not address", shortId(), id, broken);
            }
        }
        return wanted.entrySet().stream()
            .sorted(Map.Entry.<ItemSpec, Long>comparingByValue().reversed())
            .limit(EfficientConstruction.settings().basketKinds())
            .map(entry -> new Need(entry.getKey(),
                Math.min(entry.getValue(), EfficientConstruction.settings().basketItemsPerKind()), true))
            .toList();
    }

    BuildTarget target() {
        return target;
    }

    /** Gives the order up: every hand is turned away and the site hears no more. */
    void close() {
        closed = true;
        // Withdrawing may answer at once and change the map, so the ids are copied first.
        for (UUID node : List.copyOf(present.keySet())) {
            asks.withdraw(node);
        }
        present.clear();
        askedAt.clear();
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
        // Outside the footprint first: standing room inside is the first thing a finished wall takes away, and a
        // hand that cannot walk to its place is a place on the site nobody is using.
        collect(level, min.offset(-2, -1, -2), max.offset(2, 1, 2), min, max, true, found);
        if (found.isEmpty()) {
            collect(level, min, max, min, max, false, found);
        }
        return found;
    }

    private void collect(ServerLevel level, BlockPos min, BlockPos max, BlockPos insideMin, BlockPos insideMax,
            boolean onlyOutside, List<BlockPos> into) {
        for (BlockPos cell : BlockPos.betweenClosed(min, max)) {
            if (into.size() >= 16) {
                return;
            }
            BlockPos at = cell.immutable();
            boolean inside = at.getX() >= insideMin.getX() && at.getX() <= insideMax.getX()
                && at.getY() >= insideMin.getY() && at.getY() <= insideMax.getY()
                && at.getZ() >= insideMin.getZ() && at.getZ() <= insideMax.getZ();
            if (onlyOutside && inside) {
                continue;
            }
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

    private String shortId() {
        return order.toString().substring(0, 8);
    }
}
