package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MineGameTests {

    private static final ResourceLocation MINE = ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "mine");
    private static final int SURFACE = 64;
    private static final BlockPos MARK = new BlockPos(0, SURFACE + 1, 0);
    private static final int MOST_ROUNDS = 40;

    private MineGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(MineGameTests.class);
    }

    private record Laid(Drawn.Ready ready, CompoundTag kept, int lowest, int highest, int minX, int maxX, int minZ,
            int maxZ) {

        static Laid of(Drawn.Ready ready, CompoundTag kept) {
            int lowest = Integer.MAX_VALUE;
            int highest = Integer.MIN_VALUE;
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (Draft.Cell cell : ready.draft().cells()) {
                BlockPos at = ready.corner().offset(cell.offset());
                lowest = Math.min(lowest, at.getY());
                highest = Math.max(highest, at.getY());
                minX = Math.min(minX, at.getX());
                maxX = Math.max(maxX, at.getX());
                minZ = Math.min(minZ, at.getZ());
                maxZ = Math.max(maxZ, at.getZ());
            }
            return new Laid(ready, kept, lowest, highest, minX, maxX, minZ, maxZ);
        }

        boolean holds(BlockPos at, BlockState state) {
            for (Draft.Cell cell : ready.draft().cells()) {
                if (ready.corner().offset(cell.offset()).equals(at)) {
                    return cell.state().equals(state);
                }
            }
            return false;
        }

        boolean touches(BlockPos at) {
            for (Draft.Cell cell : ready.draft().cells()) {
                if (ready.corner().offset(cell.offset()).equals(at)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static Pattern mine(GameTestHelper helper) {
        return Patterns.find(MINE).orElseThrow(() -> new AssertionError("the mine pattern is not loaded"));
    }

    private static Hint mark() {
        return new Hint.Zone(MARK, MARK.offset(2, 0, 2));
    }

    private static DraftKit.Ground ground() {
        return new DraftKit.Ground(SURFACE).floorAt(-64);
    }

    private static List<Laid> dig(GameTestHelper helper, Pattern pattern, DraftKit.Filled settings,
            DraftKit.Ground world, int rounds) {
        return dig(helper, pattern, settings, world, rounds, Direction.NORTH, Growth.first(mark()));
    }

    private static List<Laid> dig(GameTestHelper helper, Pattern pattern, DraftKit.Filled settings,
            DraftKit.Ground world, int rounds, Direction facing, Growth growth) {
        Hint hint = mark();
        List<Laid> laid = new ArrayList<>();
        for (int round = 0; round < rounds; round++) {
            Round answered = pattern.growOn(new Commission(hint, settings, world, facing, 7L, growth));
            if (answered instanceof Round.Ended) {
                return laid;
            }
            if (!(answered instanceof Round.Grew grew)) {
                Drawn.Refused refused = ((Round.Stuck) answered).refused();
                helper.fail("round " + round + " is stuck: " + refused.why() + " " + refused.detail());
                throw new AssertionError("stuck");
            }
            Drawn.Ready ready = grew.ready();
            helper.assertTrue(ready.draft().cells().size() <= FolkwaysVanillaConfig.maxBuildCells(), "round " + round
                + " fits one blueprint, got " + ready.draft().cells().size() + " cells");
            for (Draft.Cell cell : ready.draft().cells()) {
                world.place(ready.corner().getX() + cell.offset().getX(), ready.corner().getY() + cell.offset().getY(),
                    ready.corner().getZ() + cell.offset().getZ(), cell.state());
            }
            laid.add(Laid.of(ready, grew.kept()));
            growth = growth.next(grew.kept(), ready.corner(), ready.draft(), hint.anchor());
        }
        return laid;
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void theMineGoesDownRoundByRoundThenOutAlongItsLevel(GameTestHelper helper) {
        Pattern pattern = mine(helper);
        helper.assertTrue(pattern.grows(), "a mine grows");
        List<Laid> laid = dig(helper, pattern, DraftKit.Filled.defaulting(pattern.knobs()), ground(), MOST_ROUNDS);
        helper.assertTrue(laid.size() > 5 && laid.size() < MOST_ROUNDS, "the mine finishes, after " + laid.size());
        Laid head = laid.get(0);
        helper.assertTrue(head.highest() > SURFACE + 4, "the first round raises a headframe over the mouth");
        helper.assertTrue(head.lowest() >= SURFACE, "the first round digs nothing below the surface");
        helper.assertTrue(DraftKit.count(head.ready().draft(), Blocks.SPRUCE_FENCE_GATE) == 1,
            "a gate opens the fence round the mouth");
        int floor = Integer.MAX_VALUE;
        int turned = -1;
        for (int round = 1; round < laid.size(); round++) {
            Laid one = laid.get(round);
            boolean shaft = one.maxX() - one.minX() <= 10 && one.maxZ() - one.minZ() <= 10;
            if (shaft) {
                helper.assertTrue(turned < 0, "round " + round + " works the shaft again after the mine turned out");
                helper.assertTrue(floor == Integer.MAX_VALUE || one.lowest() <= floor + 1,
                    "round " + round + " never climbs back up");
                helper.assertTrue(floor == Integer.MAX_VALUE || floor - one.lowest() <= 16,
                    "round " + round + " goes down at most sixteen cells");
                floor = Math.min(floor, one.lowest());
            } else if (turned < 0) {
                turned = round;
            }
        }
        helper.assertTrue(turned > 4, "the stair takes several rounds to reach the level, turned at " + turned);
        helper.assertValueEqual(floor, 16, "the stair ends at the default Y coordinate");
        int reach = 0;
        for (int round = turned; round < laid.size(); round++) {
            Laid one = laid.get(round);
            helper.assertTrue(one.lowest() >= floor && one.highest() <= floor + 5,
                "round " + round + " stays on the level");
            reach = Math.max(reach, Math.max(one.maxX() - one.minX(), one.maxZ() - one.minZ()));
        }
        helper.assertTrue(reach >= 2 * 12, "the branches reach out to either side, got " + reach);
        Set<BlockPos> opened = new HashSet<>();
        for (int round = 0; round < laid.size(); round++) {
            Drawn.Ready ready = laid.get(round).ready();
            for (Draft.Cell cell : ready.draft().cells()) {
                BlockPos at = ready.corner().offset(cell.offset());
                if (cell.state().isAir()) {
                    opened.add(at);
                } else {
                    helper.assertTrue(!opened.contains(at), "round " + round + " fills " + at.toShortString()
                        + " with " + cell.state() + " though an earlier round dug it open");
                }
            }
        }
        int lamps = 0;
        int drifts = 0;
        for (int round = turned; round < laid.size(); round++) {
            lamps += DraftKit.count(laid.get(round).ready().draft(), Blocks.LANTERN);
            drifts++;
        }
        helper.assertTrue(lamps >= 2 * drifts, "every stretch of the drift is lit, " + lamps + " lamps in " + drifts
            + " rounds");
        helper.assertTrue(pattern.growOn(new Commission(mark(), DraftKit.Filled.defaulting(pattern.knobs()),
            ground(), Direction.NORTH, 7L, Growth.first(mark()))) instanceof Round.Grew, "a fresh mine grows again");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void customPositiveAndNegativeYCoordinatesSetTheExactMineFloor(GameTestHelper helper) {
        Pattern pattern = mine(helper);
        for (int target : new int[] {37, -23}) {
            List<Laid> laid = dig(helper, pattern, DraftKit.Filled.defaulting(pattern.knobs()).count("y", target),
                ground(), MOST_ROUNDS);
            helper.assertTrue(laid.size() > 2 && laid.size() < MOST_ROUNDS,
                "the mine finishes at Y " + target);
            int lowest = laid.stream().skip(1).mapToInt(Laid::lowest).min().orElseThrow();
            helper.assertValueEqual(lowest, target, "the stair reaches the requested world Y coordinate");
            Laid drift = laid.get(laid.size() - 1);
            helper.assertValueEqual(drift.lowest(), target + 1,
                "the final drift digs just above the requested floor Y");
            helper.assertTrue(drift.maxX() - drift.minX() > 10 || drift.maxZ() - drift.minZ() > 10,
                "the mine opens its branches at Y " + target);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void theStairWindsRoundAnOpenWellBehindARail(GameTestHelper helper) {
        Pattern pattern = mine(helper);
        DraftKit.Ground world = ground();
        List<Laid> laid = dig(helper, pattern, DraftKit.Filled.defaulting(pattern.knobs()), world, MOST_ROUNDS);
        int floor = Integer.MAX_VALUE;
        int rails = 0;
        for (int round = 1; round < laid.size(); round++) {
            Laid one = laid.get(round);
            if (one.maxX() - one.minX() <= 10 && one.maxZ() - one.minZ() <= 10) {
                floor = Math.min(floor, one.lowest());
                rails += DraftKit.count(one.ready().draft(), Blocks.SPRUCE_FENCE);
            }
        }
        for (int y = floor + 1; y <= SURFACE; y++) {
            BlockPos at = MARK.offset(1, y - MARK.getY(), 1);
            helper.assertTrue(world.block(at).orElseThrow().isAir(), "the middle of the shaft is open at "
                + at.toShortString());
        }
        helper.assertTrue(rails >= SURFACE - floor, "a rail runs down beside the stair, got " + rails + " fences over "
            + (SURFACE - floor) + " steps");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void shorterMainStopsSooner(GameTestHelper helper) {
        Pattern pattern = mine(helper);
        int many = dig(helper, pattern, DraftKit.Filled.defaulting(pattern.knobs()).count("main_east", 34), ground(),
            MOST_ROUNDS).size();
        int few = dig(helper, pattern, DraftKit.Filled.defaulting(pattern.knobs()).count("main_east", 10), ground(),
            MOST_ROUNDS).size();
        helper.assertTrue(few < many, "a shorter main takes fewer rounds: " + few + " / " + many);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void waterBesideTheStairIsSealedAndGravelOverItIsPropped(GameTestHelper helper) {
        Pattern pattern = mine(helper);
        DraftKit.Filled settings = DraftKit.Filled.defaulting(pattern.knobs());
        DraftKit.Ground dry = ground();
        List<Laid> first = dig(helper, pattern, settings, dry, 2);
        Laid flight = first.get(1);
        BlockPos wet = null;
        BlockPos loose = null;
        for (int y = flight.lowest() + 2; y < SURFACE - 2 && (wet == null || loose == null); y++) {
            for (int x = flight.minX() - 1; x <= flight.maxX() + 1 && (wet == null || loose == null); x++) {
                for (int z = flight.minZ() - 1; z <= flight.maxZ() + 1; z++) {
                    BlockPos at = new BlockPos(x, y, z);
                    if (dry.block(at).orElseThrow().isAir() || flight.touches(at)) {
                        continue;
                    }
                    if (wet == null && dry.block(at.east()).orElseThrow().isAir()
                        && flight.holds(at.east(), Blocks.AIR.defaultBlockState())) {
                        wet = at;
                    } else if (loose == null && dry.block(at.below()).orElseThrow().isAir()
                        && flight.holds(at.below(), Blocks.AIR.defaultBlockState())) {
                        loose = at;
                    }
                }
            }
        }
        helper.assertTrue(wet != null && loose != null, "found rock beside and over the first flight");
        DraftKit.Ground soaked = ground();
        soaked.place(wet.getX(), wet.getY(), wet.getZ(), Blocks.WATER.defaultBlockState());
        soaked.place(loose.getX(), loose.getY(), loose.getZ(), Blocks.GRAVEL.defaultBlockState());
        List<Laid> again = dig(helper, pattern, settings, soaked, 2);
        helper.assertTrue(again.get(1).holds(wet, Blocks.COBBLESTONE.defaultBlockState()),
            "water beside the stair is sealed at " + wet.toShortString());
        helper.assertTrue(again.get(1).holds(loose, Blocks.COBBLESTONE.defaultBlockState()),
            "gravel over the stair is propped at " + loose.toShortString());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void oreShowingOnAWallIsFollowedAndOreInsideTheRockIsNot(GameTestHelper helper) {
        Pattern pattern = mine(helper);
        DraftKit.Filled settings = DraftKit.Filled.defaulting(pattern.knobs());
        DraftKit.Ground plain = ground();
        List<Laid> before = dig(helper, pattern, settings, plain, 3);
        Laid flight = before.get(1);
        Laid after = before.get(2);
        BlockPos shown = null;
        Direction inward = null;
        for (int y = flight.lowest() + 2; y < SURFACE - 2 && shown == null; y++) {
            for (int x = flight.minX() - 1; x <= flight.maxX() + 1 && shown == null; x++) {
                for (int z = flight.minZ() - 1; z <= flight.maxZ() + 1 && shown == null; z++) {
                    BlockPos at = new BlockPos(x, y, z);
                    for (Direction open : Direction.Plane.HORIZONTAL) {
                        boolean wall = flight.holds(at.relative(open), Blocks.AIR.defaultBlockState());
                        for (int deep = 0; deep < 5 && wall; deep++) {
                            BlockPos in = at.relative(open.getOpposite(), deep);
                            wall = plain.block(in).orElseThrow().is(Blocks.STONE) && !after.touches(in)
                                && !flight.touches(in);
                        }
                        if (wall) {
                            shown = at;
                            inward = open.getOpposite();
                            break;
                        }
                    }
                }
            }
        }
        helper.assertTrue(shown != null, "found a bare wall of the first flight");
        BlockPos behind = shown.relative(inward);
        BlockPos hidden = shown.relative(inward, 3);
        DraftKit.Ground veined = ground();
        List<Laid> dug = dig(helper, pattern, settings, veined, 2);
        veined.place(shown.getX(), shown.getY(), shown.getZ(), Blocks.IRON_ORE.defaultBlockState());
        veined.place(behind.getX(), behind.getY(), behind.getZ(), Blocks.IRON_ORE.defaultBlockState());
        veined.place(hidden.getX(), hidden.getY(), hidden.getZ(), Blocks.DIAMOND_ORE.defaultBlockState());
        Laid next = resume(helper, pattern, settings, veined, dug);
        helper.assertTrue(next.holds(shown, Blocks.AIR.defaultBlockState()), "the ore on the wall is dug");
        helper.assertTrue(next.holds(behind, Blocks.AIR.defaultBlockState()), "and the vein behind it followed");
        helper.assertTrue(!next.touches(hidden), "but ore shut inside the rock is left alone");
        DraftKit.Ground veinedAgain = ground();
        List<Laid> dugAgain = dig(helper, pattern, DraftKit.Filled.defaulting(pattern.knobs()).flag("chase", false),
            veinedAgain, 2);
        veinedAgain.place(shown.getX(), shown.getY(), shown.getZ(), Blocks.IRON_ORE.defaultBlockState());
        helper.assertTrue(!resume(helper, pattern, DraftKit.Filled.defaulting(pattern.knobs()).flag("chase", false),
            veinedAgain, dugAgain).touches(shown), "with chasing off the ore is left for the player");
        helper.succeed();
    }

    private static Laid resume(GameTestHelper helper, Pattern pattern, DraftKit.Filled settings,
            DraftKit.Ground world, List<Laid> dug) {
        Hint hint = mark();
        Growth growth = Growth.first(hint);
        for (Laid one : dug) {
            growth = growth.next(one.kept(), one.ready().corner(), one.ready().draft(), hint.anchor());
        }
        Round round = pattern.growOn(new Commission(hint, settings, world, Direction.NORTH, 7L, growth));
        if (!(round instanceof Round.Grew grew)) {
            helper.fail("the next round grows, got " + round);
            throw new AssertionError("stuck");
        }
        return Laid.of(grew.ready(), grew.kept());
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aFloodedWayDownWaitsUntilItIsDrained(GameTestHelper helper) {
        Pattern pattern = mine(helper);
        DraftKit.Filled settings = DraftKit.Filled.defaulting(pattern.knobs());
        DraftKit.Ground world = ground();
        List<Laid> head = dig(helper, pattern, settings, world, 1);
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                for (int y = SURFACE - 8; y <= SURFACE - 6; y++) {
                    world.place(x, y, z, Blocks.WATER.defaultBlockState());
                }
            }
        }
        Hint hint = mark();
        Growth growth = Growth.first(hint).next(head.get(0).kept(), head.get(0).ready().corner(),
            head.get(0).ready().draft(), hint.anchor());
        Round flooded = pattern.growOn(new Commission(hint, settings, world, Direction.NORTH, 7L, growth));
        helper.assertTrue(flooded instanceof Round.Stuck stuck && stuck.refused().explanation()
            .filter(notice -> notice.key().equals(DraftKit.key(pattern, "error.mine.flooded"))
                && notice.args().equals(List.of(Notice.count(SURFACE - 6))))
            .isPresent(),
            "a lake across the stair holds the mine up and says where, got " + flooded);
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                for (int y = SURFACE - 8; y <= SURFACE - 6; y++) {
                    world.place(x, y, z, Blocks.STONE.defaultBlockState());
                }
            }
        }
        helper.assertTrue(pattern.growOn(new Commission(hint, settings, world, Direction.NORTH, 7L, growth))
            instanceof Round.Grew, "once the lake is gone the same round goes on");
        helper.succeed();
    }

    private static DraftKit.Filled directions(Pattern pattern, int east, int south, int west, int north) {
        return DraftKit.Filled.defaulting(pattern.knobs()).flag("chase", false)
            .count("main_east", east).count("main_south", south)
            .count("main_west", west).count("main_north", north);
    }

    private static boolean open(DraftKit.Ground world, BlockPos at) {
        return world.block(at).orElseThrow().isAir() && world.block(at.above()).orElseThrow().isAir();
    }

    private static Set<BlockPos> connected(DraftKit.Ground world, BlockPos start, int radius) {
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> todo = new ArrayDeque<>();
        seen.add(start);
        todo.add(start);
        while (!todo.isEmpty()) {
            BlockPos at = todo.removeFirst();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos next = at.relative(direction);
                if (Math.abs(next.getX() - 1) <= radius && Math.abs(next.getZ() - 1) <= radius
                        && open(world, next) && seen.add(next)) {
                    todo.addLast(next);
                }
            }
        }
        return seen;
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void northIsWorldNorthForDifferentDraftFacingsAndZeroMainsStayClosed(GameTestHelper helper) {
        Pattern pattern = mine(helper);
        for (Direction facing : new Direction[] {Direction.NORTH, Direction.EAST}) {
            DraftKit.Ground world = ground();
            DraftKit.Filled settings = directions(pattern, 0, 0, 0, 14).count("branch_north", 0)
                .items("seal", "minecraft:stone");
            List<Laid> laid = dig(helper, pattern, settings, world, MOST_ROUNDS, facing, Growth.first(mark()));
            helper.assertTrue(laid.size() < MOST_ROUNDS, "the north mine ends for " + facing);
            BlockPos end = new BlockPos(1, 17, 1 - 8 - 14);
            helper.assertTrue(open(world, end), "the main ends due world north for " + facing);
            Set<BlockPos> connected = connected(world, end, 30);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos hub = new BlockPos(1, 17, 1).relative(direction, 7);
                helper.assertTrue(connected.contains(hub), "all four hub sides join for " + facing + ": " + hub);
                if (direction != Direction.NORTH) {
                    BlockPos closed = new BlockPos(1, 17, 1).relative(direction, 10);
                    helper.assertTrue(world.block(closed).orElseThrow().is(Blocks.STONE),
                        "a zero main stays rock for " + facing + " toward " + direction);
                }
            }
            helper.assertTrue(world.block(end.east(2)).orElseThrow().is(Blocks.STONE),
                "zero branch length leaves the side wall intact");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void crossingBranchesJoinAndKeepEarlierLampsPostsAndStairs(GameTestHelper helper) {
        Pattern pattern = mine(helper);
        DraftKit.Filled settings = directions(pattern, 18, 18, 18, 18);
        for (String direction : List.of("east", "south", "west", "north")) {
            settings.count("branch_" + direction, 28);
        }
        DraftKit.Ground world = ground();
        List<Laid> laid = dig(helper, pattern, settings, world, MOST_ROUNDS);
        helper.assertTrue(laid.size() < MOST_ROUNDS, "all four directions end within the round bound");
        Map<BlockPos, BlockState> kept = new HashMap<>();
        Set<String> roles = new HashSet<>();
        List<Integer> order = new ArrayList<>();
        for (Laid round : laid) {
            if (round.kept().getLong("i") <= round.kept().getLong("n")) {
                continue;
            }
            if (round.kept().getLong("t") > 0) {
                int direction = (int) round.kept().getLong("d");
                if (order.isEmpty() || order.getLast() != direction) {
                    order.add(direction);
                }
            }
            for (Draft.Cell cell : round.ready().draft().cells()) {
                BlockPos at = round.ready().corner().offset(cell.offset());
                if (kept.containsKey(at)) {
                    helper.assertValueEqual(cell.state(), kept.get(at), "later rounds preserve " + at);
                }
                if (!cell.state().isAir()) {
                    kept.put(at, cell.state());
                    roles.add(cell.source().role());
                }
            }
        }
        helper.assertValueEqual(order, List.of(0, 1, 2, 3), "directions finish east, south, west, north");
        helper.assertTrue(roles.contains("lamp") && roles.contains("post") && roles.contains("cap"),
            "the crossing test actually places lamps, posts and caps: " + roles);
        Set<BlockPos> joined = connected(world, new BlockPos(1 + 26, 17, 1), 60);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos end = new BlockPos(1, 17, 1).relative(direction, 26);
            helper.assertTrue(joined.contains(end), "main endpoint joins the hub: " + end);
        }
        // East and south branches cross at world offsets (13, 13) from the shaft centre.
        BlockPos crossing = new BlockPos(14, 17, 14);
        helper.assertTrue(joined.contains(crossing), "adjacent branches meet in the southeast quadrant");
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            helper.assertTrue(open(world, crossing.relative(direction)), "the crossing opens toward " + direction);
        }
        for (Laid round : laid) {
            for (Draft.Cell cell : round.ready().draft().cells()) {
                String role = cell.source().role();
                if (role.equals("stairs") || role.equals("rail") || role.equals("landing")) {
                    BlockPos at = round.ready().corner().offset(cell.offset());
                    helper.assertValueEqual(world.block(at).orElseThrow(), cell.state(),
                        "the junction preserves the shaft at " + at);
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void everyFinalStairHeightJoinsTheHubWithoutLosingItsFloor(GameTestHelper helper) {
        Pattern pattern = mine(helper);
        for (int target = 26; target < 38; target++) {
            Direction facing = target % 2 == 0 ? Direction.SOUTH : Direction.WEST;
            DraftKit.Ground world = ground();
            DraftKit.Filled settings = directions(pattern, 0, 0, 0, 4)
                .count("y", target).count("branch_north", 0);
            List<Laid> laid = dig(helper, pattern, settings, world, MOST_ROUNDS, facing, Growth.first(mark()));
            helper.assertTrue(laid.size() < MOST_ROUNDS, "the mine ends at Y " + target);
            Set<BlockPos> joined = connected(world, new BlockPos(1, target + 1, 1 - 8 - 4), 25);
            boolean reachesStair = false;
            for (Laid round : laid) {
                for (Draft.Cell cell : round.ready().draft().cells()) {
                    String role = cell.source().role();
                    if (!role.equals("landing") && !role.equals("stairs") && !role.equals("rail")) {
                        continue;
                    }
                    BlockPos at = round.ready().corner().offset(cell.offset());
                    helper.assertValueEqual(world.block(at).orElseThrow(), cell.state(),
                        "the hub keeps the shaft at Y " + target + ": " + at);
                    if (role.equals("landing") && at.getY() == target && joined.contains(at.above())) {
                        reachesStair = true;
                    }
                }
            }
            helper.assertTrue(reachesStair, "the final stair joins all exits at Y " + target);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos exit = new BlockPos(1, target, 1).relative(direction, 7);
                helper.assertTrue(!world.block(exit).orElseThrow().isAir(), "the hub has a floor: " + exit);
                helper.assertTrue(open(world, exit.above()) && world.block(exit.above(3)).orElseThrow().isAir(),
                    "the hub has three blocks of headroom: " + exit);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void alreadyOpenStretchesStillAdvanceThroughAllDirections(GameTestHelper helper) {
        Pattern pattern = mine(helper);
        DraftKit.Filled settings = directions(pattern, 12, 12, 12, 12);
        DraftKit.Ground world = ground();
        List<Laid> shaft = dig(helper, pattern, settings, world, 7);
        for (int x = -50; x <= 50; x++) {
            for (int z = -50; z <= 50; z++) {
                if (Math.max(Math.abs(x - 1), Math.abs(z - 1)) < 6) {
                    continue;
                }
                for (int y = 17; y <= 20; y++) {
                    world.place(x, y, z, Blocks.AIR.defaultBlockState());
                }
            }
        }
        Laid last = shaft.getLast();
        Growth growth = Growth.first(mark()).next(last.kept(), last.ready().corner(), last.ready().draft(),
            mark().anchor());
        List<Laid> continued = dig(helper, pattern, settings, world, MOST_ROUNDS, Direction.NORTH, growth);
        helper.assertTrue(continued.size() < MOST_ROUNDS, "open passages do not keep growing forever");
        helper.assertValueEqual(continued.getLast().kept().getLong("d"), 3L, "the north direction is reached");
        helper.assertValueEqual(continued.getLast().kept().getLong("t"), 12L, "the last direction finishes");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void maximumLengthsFitEachRoundAndFinishWithinABound(GameTestHelper helper) {
        Pattern pattern = mine(helper);
        DraftKit.Filled settings = directions(pattern, 128, 128, 128, 128).count("spacing", 2);
        for (String direction : List.of("east", "south", "west", "north")) {
            settings.count("branch_" + direction, 48);
        }
        List<Laid> laid = dig(helper, pattern, settings, ground(), 100);
        helper.assertTrue(laid.size() < 100, "maximum settings finish within one hundred rounds");
        helper.assertValueEqual(laid.getLast().kept().getLong("d"), 3L, "maximum settings reach north");
        helper.assertValueEqual(laid.getLast().kept().getLong("t"), 128L, "north reaches its exact maximum length");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void allZeroMainsAreRefusedAndOldSettingsUseNewDefaults(GameTestHelper helper) {
        Pattern pattern = mine(helper);
        Round round = pattern.growOn(new Commission(mark(), directions(pattern, 0, 0, 0, 0), ground(),
            Direction.NORTH, 7L, Growth.first(mark())));
        helper.assertTrue(round instanceof Round.Stuck stuck && stuck.refused().explanation()
            .filter(notice -> notice.key().equals(DraftKit.key(pattern, "error.mine.directions"))).isPresent(),
            "zero mains have a translated refusal, got " + round);
        CompoundTag old = new CompoundTag();
        old.putInt("pairs", 8);
        old.putInt("branch", 24);
        Chosen loaded = Chosen.load(pattern.knobs(), old);
        helper.assertValueEqual(loaded.count("main_east"), 26, "old settings default to an east main");
        helper.assertValueEqual(loaded.count("main_north"), 0, "other directions default to zero");
        helper.assertValueEqual(loaded.count("branch_east"), 12, "old branch key is ignored");
        helper.assertTrue(!loaded.save().contains("pairs") && !loaded.save().contains("branch"),
            "obsolete keys are not saved again");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void oldKeepContinuesItsOriginalSideAndProgress(GameTestHelper helper) {
        Pattern pattern = mine(helper);
        DraftKit.Filled settings = directions(pattern, 18, 18, 18, 18);
        DraftKit.Ground world = ground();
        List<Laid> shaft = dig(helper, pattern, settings, world, 7);
        Laid last = shaft.getLast();
        helper.assertTrue(last.kept().getLong("i") > last.kept().getLong("n"), "the old shaft reached its floor");
        CompoundTag old = last.kept().copy();
        old.remove("d");
        old.remove("hub");
        old.putLong("t", 3);
        Growth growth = Growth.first(mark()).next(old, last.ready().corner(), last.ready().draft(), mark().anchor());
        List<Laid> continued = dig(helper, pattern, settings, world, MOST_ROUNDS, Direction.NORTH, growth);
        helper.assertTrue(!continued.isEmpty() && continued.size() < MOST_ROUNDS, "old progress grows and ends");
        int direction = (int) continued.getFirst().kept().getLong("d");
        helper.assertValueEqual(continued.getFirst().kept().getLong("t"), 11L, "old t continues without resetting");
        for (Laid round : continued) {
            helper.assertTrue(round.kept().getBoolean("legacy"), "the old lifecycle remains marked");
            helper.assertValueEqual(round.kept().getLong("d"), (long) direction, "only the old side grows");
        }
        helper.assertValueEqual(continued.getLast().kept().getLong("t"), 18L, "the new matching knob sets length");
        List<Laid> fallback = dig(helper, pattern, directions(pattern, 0, 0, 0, 0), world, MOST_ROUNDS,
            Direction.NORTH, growth);
        helper.assertTrue(!fallback.isEmpty() && fallback.size() < MOST_ROUNDS, "old missing counts still finish");
        helper.assertValueEqual(fallback.getLast().kept().getLong("t"), 26L,
            "an unavailable old-direction knob falls back to the former default length");
        DraftKit.Ground earlyWorld = ground();
        Laid head = dig(helper, pattern, settings, earlyWorld, 1).getFirst();
        CompoundTag early = head.kept().copy();
        early.remove("d");
        early.remove("hub");
        Growth oldFlight = Growth.first(mark()).next(early, head.ready().corner(), head.ready().draft(),
            mark().anchor());
        List<Laid> finished = dig(helper, pattern, settings, earlyWorld, MOST_ROUNDS, Direction.NORTH, oldFlight);
        helper.assertTrue(finished.size() < MOST_ROUNDS, "a legacy shaft also finishes its original direction");
        helper.assertValueEqual(finished.getLast().kept().getLong("d"), (long) direction,
            "migrating during the flight retains the same old side");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void aLevelAboveTheMouthIsRefused(GameTestHelper helper) {
        Pattern pattern = mine(helper);
        DraftKit.Ground low = new DraftKit.Ground(10).floorAt(-64);
        Hint hint = new Hint.Zone(new BlockPos(0, 11, 0), new BlockPos(2, 11, 2));
        Round round = pattern.growOn(new Commission(hint, DraftKit.Filled.defaulting(pattern.knobs()), low,
            Direction.NORTH, 7L, Growth.first(hint)));
        helper.assertTrue(round instanceof Round.Stuck stuck && stuck.refused().explanation()
            .filter(notice -> notice.key().equals(DraftKit.key(pattern, "error.mine.level"))).isPresent(),
            "iron level lies above a mouth at y 10, got " + round);
        helper.succeed();
    }
}
