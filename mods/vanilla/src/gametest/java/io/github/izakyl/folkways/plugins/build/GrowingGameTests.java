package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Holding;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Closures;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Settings;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.build.draft.Chosen;
import io.github.izakyl.folkways.plugins.build.draft.Draft;
import io.github.izakyl.folkways.plugins.build.draft.Drawn;
import io.github.izakyl.folkways.plugins.build.draft.DraftRefusal;
import io.github.izakyl.folkways.plugins.build.draft.Round;
import io.github.izakyl.folkways.plugins.build.draft.Hint;
import io.github.izakyl.folkways.plugins.build.draft.Pattern;
import io.github.izakyl.folkways.plugins.build.draft.Patterns;
import io.github.izakyl.folkways.plugins.build.draft.PatternText;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GrowingGameTests {

    private static final ResourceLocation GROWER =
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "test_grower");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(GrowingGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void savedFailuresKeepTheirTranslationAndReadOlderSaves(GameTestHelper helper) throws Exception {
        Hint hint = new Hint.Zone(BlockPos.ZERO, new BlockPos(2, 2, 2));
        Growing growing = Growing.begun(ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "mine"),
            "Mine", hint, new CompoundTag(), Direction.NORTH, 1L, WorldPos.of(helper.getLevel(), BlockPos.ZERO),
            Optional.empty(), "");
        Pattern mine = Patterns.find(ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "mine")).orElseThrow();
        Notice explanation = new Notice(PatternText.key(mine.face(), "error.mine.flooded"),
            List.of(Notice.count(12)));
        Growing stopped = growing.stuck(new Drawn.Refused(DraftRefusal.PATTERN_FAILED, "diagnostic",
            Optional.of(explanation)), 100L);
        CompoundTag saved = stopped.save();
        Growing restored = Growing.load(Reader.of(saved)).orElseThrow();
        helper.assertTrue(restored.stuckExplanation().equals(Optional.of(explanation))
            && restored.stuckText().equals(stopped.stuckText()), "saving and loading preserves the translated detail");
        saved.remove("stuckExplanation");
        Growing older = Growing.load(Reader.of(saved)).orElseThrow();
        helper.assertTrue(older.stuckExplanation().isEmpty() && older.stuckDetail().equals("diagnostic"),
            "older saves still read their literal failure detail");
        helper.assertTrue(restored.building(UUID.randomUUID(), restored.growth(), false).stuckExplanation().isEmpty(),
            "a successful retry clears the previous failure");
        helper.succeed();
    }

    private record Rig(ServerLevel level, ColonyView view, AtomicReference<CompoundTag> saved, Colony colony) {
    }

    private static Rig rig(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AtomicReference<CompoundTag> saved = new AtomicReference<>(new CompoundTag());
        ColonyView view = new ColonyView() {
            public ServerLevel level() {
                return level;
            }

            public ResourceKey<Level> dimension() {
                return level.dimension();
            }

            public List<Holding> holdings() {
                return List.of();
            }

            public Set<BlockPos> blocks(ResourceLocation owner) {
                return Set.of();
            }

            public List<Resident> residents() {
                return List.of();
            }

            public Effects effectsOf(Resident resident) {
                return Effects.NONE;
            }
        };
        var inventory = new io.github.izakyl.folkways.core.engine.inventory.InventoryIndex(UUID.randomUUID());
        Colony colony = (Colony) Proxy.newProxyInstance(GrowingGameTests.class.getClassLoader(),
            new Class<?>[] {Colony.class}, (proxy, method, args) -> switch (method.getName()) {
                case "kept" -> saved.get();
                case "keep" -> {
                    saved.set(((CompoundTag) args[1]).copy());
                    yield null;
                }
                case "inventory" -> inventory;
                case "holdingAt" -> Optional.empty();
                case "views" -> List.of(view);
                case "view" -> view;
                default -> null;
            });
        return new Rig(level, view, saved, colony);
    }

    private static Growing begun(GameTestHelper helper, Rig rig) {
        Pattern pattern = Patterns.find(GROWER).orElseThrow(() -> new AssertionError("test_grower is not loaded"));
        BlockPos start = helper.absolutePos(new BlockPos(2, 2, 2));
        Hint hint = new Hint.Zone(start, start.offset(7, 0, 0));
        return Growing.begun(GROWER, "", hint, Chosen.load(pattern.knobs(), new CompoundTag()).save(), Direction.NORTH,
            0L, WorldPos.of(rig.level(), start), Optional.empty(), "");
    }

    private static int standing(Rig rig) {
        return rig.saved().get().getCompound("book").getList("buildOrders", Tag.TAG_COMPOUND).size();
    }

    private static int growing(Rig rig) {
        return rig.saved().get().getList("growing", Tag.TAG_COMPOUND).size();
    }

    private static int round(Rig rig) {
        return rig.saved().get().getList("growing", Tag.TAG_COMPOUND).getCompound(0).getInt("round");
    }

    // Rounds are drawn on a drafting thread and taken up on the server's; a test waits ticks for them to come back.
    private static void drawn(GameTestHelper helper, CompletableFuture<?> drawing) {
        helper.assertTrue(drawing.isDone(), "the drawing comes back");
    }

    private static void filed(GameTestHelper helper, CompletableFuture<Optional<Component>> grown, String what) {
        Optional<Component> refused = grown.join();
        helper.assertTrue(refused.isEmpty(), what + ", not " + refused.map(Component::getString).orElse(""));
    }

    private static Growing raising(GameTestHelper helper, int x, int rounds, boolean last, int stuck) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "test_raise");
        Pattern pattern = Patterns.find(id).orElseThrow();
        BlockPos start = helper.absolutePos(new BlockPos(x, 2, 2));
        CompoundTag settings = new CompoundTag();
        settings.putInt("rounds", rounds);
        settings.putBoolean("last", last);
        settings.putInt("stuck", stuck);
        return Growing.begun(id, "", new Hint.Zone(start, start), Chosen.load(pattern.knobs(), settings).save(),
            Direction.NORTH, 0L, WorldPos.of(helper.getLevel(), start), Optional.empty(), "");
    }

    private static void raised(GameTestHelper helper, BuildPackets.Raised result, int rounds) {
        helper.assertValueEqual(result.rounds(), rounds, "every completed round is counted");
        helper.assertTrue(result.refusal().isEmpty(), "growth finishes successfully: " + result.refusal());
    }

    @GameTest(template = "empty_large", timeoutTicks = 600)
    public static void raisingPast64RoundsMatchesColonyGrowthUntilEnded(GameTestHelper helper) {
        Rig rig = rig(helper);
        Growing instant = raising(helper, 2, 80, false, -1);
        Growing gradual = raising(helper, 13, 80, false, -1);
        AtomicReference<BuildPresence> presence =
            new AtomicReference<>(new BuildPresence(rig.colony(), rig.level().getServer()));
        CompletableFuture<BuildPackets.Raised> raising = BuildPackets.raiseRounds(rig.level(), instant);
        CompletableFuture<Optional<Component>> first = presence.get().grow(rig.level(), gradual);
        GameTestSequence steps = helper.startSequence()
            .thenWaitUntil(() -> drawn(helper, first))
            .thenExecute(() -> filed(helper, first, "the colony starts the same pattern"));
        for (int n = 0; n < 80; n++) {
            int completed = n + 1;
            BlockPos cell = gradual.hint().anchor().offset(n % 10, 0, n / 10);
            steps.thenExecute(() -> {
                helper.assertValueEqual(round(rig), completed, "the colony advances one round at a time");
                presence.get().settle(rig.level().getServer());
                rig.level().setBlock(cell, Blocks.STONE.defaultBlockState(), 3);
                presence.set(new BuildPresence(rig.colony(), rig.level().getServer()));
                presence.get().settle(rig.level().getServer());
            }).thenWaitUntil(() -> {
                presence.get().settle(rig.level().getServer());
                helper.assertTrue(growing(rig) == 0 || round(rig) > completed,
                    "the colony advances after round " + completed);
            });
        }
        steps.thenWaitUntil(() -> drawn(helper, raising)).thenExecute(() -> {
            raised(helper, raising.join(), 80);
            helper.assertValueEqual(growing(rig), 0, "Ended retires the colony's growth");
            helper.assertValueEqual(standing(rig), 0, "the colony has no unfinished rounds");
            for (int n = 0; n < 80; n++) {
                BlockPos offset = new BlockPos(n % 10, 0, n / 10);
                helper.assertTrue(rig.level().getBlockState(instant.hint().anchor().offset(offset)).is(Blocks.STONE),
                    "instant round " + n + " is placed");
                helper.assertValueEqual(rig.level().getBlockState(instant.hint().anchor().offset(offset)),
                    rig.level().getBlockState(gradual.hint().anchor().offset(offset)),
                    "both paths leave the same block");
            }
            helper.assertTrue(rig.level().getBlockState(instant.hint().anchor().offset(0, 0, 8)).isAir(),
                "Ended places no extra round");
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void raisingCountsTheLastRoundAndDoesNotDrawAgain(GameTestHelper helper) {
        Growing growing = raising(helper, 2, 3, true, 3);
        CompletableFuture<BuildPackets.Raised> raising = BuildPackets.raiseRounds(helper.getLevel(), growing);
        helper.startSequence().thenWaitUntil(() -> drawn(helper, raising)).thenExecute(() -> {
            raised(helper, raising.join(), 3);
            TranslatableContents message =
                (TranslatableContents) raising.join().message(growing.pattern()).getContents();
            helper.assertValueEqual(message.getKey(), "folkways.draft.grow.raised", "last reports success");
            helper.assertValueEqual(message.getArgs()[1], 3, "the success message includes the last round");
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void raisingReportsAnObstructionAfterPartialProgress(GameTestHelper helper) {
        Growing growing = raising(helper, 2, 5, false, 2);
        CompletableFuture<BuildPackets.Raised> raising = BuildPackets.raiseRounds(helper.getLevel(), growing);
        helper.startSequence().thenWaitUntil(() -> drawn(helper, raising)).thenExecute(() -> {
            BuildPackets.Raised result = raising.join();
            helper.assertValueEqual(result.rounds(), 2, "only completed rounds are counted");
            helper.assertTrue(result.refusal().orElseThrow().getString().contains("test obstruction"),
                "the actual refusal is preserved");
            TranslatableContents message = (TranslatableContents) result.message(growing.pattern()).getContents();
            helper.assertValueEqual(message.getKey(), "folkways.draft.grow.interrupted", "Stuck reports interruption");
            helper.assertValueEqual(message.getArgs()[1], 2, "the interruption includes completed progress");
            helper.assertValueEqual(message.getArgs()[2], result.refusal().orElseThrow(),
                "the reason stays translated");
            helper.assertTrue(helper.getLevel().getBlockState(growing.hint().anchor().offset(2, 0, 0)).isAir(),
                "the obstructed round is not raised");
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void raisingReportsItsSafetyLimitWithoutPlacingAnotherRound(GameTestHelper helper) {
        Growing growing = raising(helper, 2, 5, false, -1);
        CompletableFuture<BuildPackets.Raised> raising = BuildPackets.raiseRounds(helper.getLevel(), growing, 2);
        helper.startSequence().thenWaitUntil(() -> drawn(helper, raising)).thenExecute(() -> {
            BuildPackets.Raised result = raising.join();
            helper.assertValueEqual(result.rounds(), 2, "the safety limit bounds placements");
            TranslatableContents reason = (TranslatableContents) result.refusal().orElseThrow().getContents();
            helper.assertValueEqual(reason.getKey(), "folkways.draft.grow.limit", "the limit is reported honestly");
            helper.assertTrue(helper.getLevel().getBlockState(growing.hint().anchor().offset(2, 0, 0)).isAir(),
                "no placement exceeds the limit");
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void raisingCanEndExactlyAtItsSafetyLimit(GameTestHelper helper) {
        Growing growing = raising(helper, 2, 2, false, -1);
        CompletableFuture<BuildPackets.Raised> raising = BuildPackets.raiseRounds(helper.getLevel(), growing, 2);
        helper.startSequence().thenWaitUntil(() -> drawn(helper, raising))
            .thenExecute(() -> raised(helper, raising.join(), 2)).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void raisingNothingKeepsTheOriginalRefusal(GameTestHelper helper) {
        Growing growing = raising(helper, 2, 0, false, -1);
        CompletableFuture<BuildPackets.Raised> raising = BuildPackets.raiseRounds(helper.getLevel(), growing);
        helper.startSequence().thenWaitUntil(() -> drawn(helper, raising)).thenExecute(() -> {
            helper.assertValueEqual(raising.join().rounds(), 0, "nothing was raised");
            TranslatableContents message =
                (TranslatableContents) raising.join().message(growing.pattern()).getContents();
            helper.assertValueEqual(message.getKey(), DraftRefusal.NOTHING_DRAWN.translationKey(),
                "an empty first round does not claim success");
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 200)
    public static void eachFinishedRoundFilesTheNextUntilThePatternIsDone(GameTestHelper helper) {
        Rig rig = rig(helper);
        AtomicReference<BuildPresence> presence =
            new AtomicReference<>(new BuildPresence(rig.colony(), rig.level().getServer()));
        CompletableFuture<Optional<Component>> first = presence.get().grow(rig.level(), begun(helper, rig));
        BlockPos start = helper.absolutePos(new BlockPos(2, 2, 2));
        GameTestSequence steps = helper.startSequence()
            .thenWaitUntil(() -> drawn(helper, first))
            .thenExecute(() -> {
                filed(helper, first, "the first round is drawn and filed");
                helper.assertValueEqual(standing(rig), 1, "one order stands for the first round");
            });
        for (int round = 0; round < 3; round++) {
            int at = round;
            BlockPos step = start.offset(round, 0, 0);
            AtomicInteger before = new AtomicInteger();
            steps.thenExecute(() -> {
                helper.assertTrue(rig.level().getBlockState(step).isAir(), "round " + at + " is not built for free");
                presence.get().settle(rig.level().getServer());
                helper.assertValueEqual(standing(rig), 1, "the order for round " + at + " waits to be built");
                rig.level().setBlock(step, Blocks.STONE.defaultBlockState(), 3);
                before.set(round(rig));
                presence.set(new BuildPresence(rig.colony(), rig.level().getServer()));
                presence.get().settle(rig.level().getServer());
            }).thenWaitUntil(() -> helper.assertTrue(growing(rig) == 0 || round(rig) > before.get(),
                "the round after " + at + " is drawn once it stands"));
        }
        steps.thenExecute(() -> {
            helper.assertValueEqual(standing(rig), 0, "no order stands once the pattern answers nothing");
            helper.assertValueEqual(growing(rig), 0, "the growing retires when it is done");
            helper.assertTrue(rig.level().getBlockState(start.offset(3, 0, 0)).isAir(), "no fourth step");
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void stoppingAGrowingCancelsItsStandingRound(GameTestHelper helper) {
        Rig rig = rig(helper);
        BuildPresence presence = new BuildPresence(rig.colony(), rig.level().getServer());
        Growing fresh = begun(helper, rig);
        CompletableFuture<Optional<Component>> first = presence.grow(rig.level(), fresh);
        helper.startSequence().thenWaitUntil(() -> drawn(helper, first)).thenExecute(() -> {
            filed(helper, first, "the first round is filed");
            presence.act(BuildContent.PAGE.id(), BuildAct.stopping(fresh.id()), Optional.empty(), rig.view());
            helper.assertValueEqual(growing(rig), 0, "the growing is stopped");
            presence.settle(rig.level().getServer());
            presence.settle(rig.level().getServer());
            helper.assertValueEqual(standing(rig), 0, "its round is cancelled");
            helper.assertValueEqual(growing(rig), 0, "and nothing grows again");
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void cancellingTheStandingRoundStopsTheGrowing(GameTestHelper helper) {
        Rig rig = rig(helper);
        BuildPresence presence = new BuildPresence(rig.colony(), rig.level().getServer());
        CompletableFuture<Optional<Component>> first = presence.grow(rig.level(), begun(helper, rig));
        helper.startSequence().thenWaitUntil(() -> drawn(helper, first)).thenExecute(() -> {
            filed(helper, first, "the first round is filed");
            CompoundTag order = rig.saved().get().getCompound("book").getList("buildOrders", Tag.TAG_COMPOUND)
                .getCompound(0);
            presence.act(BuildContent.PAGE.id(), BuildAct.cancelling(order.getUUID("id")), Optional.empty(),
                rig.view());
            presence.settle(rig.level().getServer());
            presence.settle(rig.level().getServer());
            helper.assertValueEqual(standing(rig), 0, "the round is cancelled");
            helper.assertValueEqual(growing(rig), 0, "and the growing with it");
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aPatternDrawnOnTheServerIsFiledWholeFarPastFiveHundredCells(GameTestHelper helper) {
        Rig rig = rig(helper);
        BuildPresence presence = new BuildPresence(rig.colony(), rig.level().getServer());
        Pattern pattern = Patterns.find(ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "test_slab"))
            .orElseThrow(() -> new AssertionError("test_slab is not loaded"));
        BlockPos start = helper.absolutePos(new BlockPos(1, 2, 1));
        Hint hint = new Hint.Zone(start, start.offset(39, 0, 39));
        CompletableFuture<Rounds.Once> drawing = Rounds.drawOnce(rig.level(), pattern, hint,
            Chosen.load(pattern.knobs(), new CompoundTag()), Direction.NORTH);
        helper.startSequence().thenWaitUntil(() -> drawn(helper, drawing)).thenExecute(() -> {
            Rounds.Once once = drawing.join();
            if (!(once instanceof Rounds.Once.Drafted drafted)) {
                helper.fail("the slab should be drawn, got " + once);
                return;
            }
            helper.assertValueEqual(drafted.blueprint().blocks().size(), 1600, "every cell of the slab is kept");
            helper.assertValueEqual(drafted.corner(), start, "the slab lies where it was marked");
            helper.assertTrue(presence.file(rig.level(), drafted.blueprint(), drafted.corner(), "", Optional.empty(),
                "").isPresent(), "and it is filed as one order");
            helper.assertTrue(rig.level().getBlockState(start).isAir(), "filing builds nothing for free");
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void aCommissionCrossesTheWireIntact(GameTestHelper helper) {
        Hint hint = new Hint.Path(List.of(new BlockPos(1, 2, 3), new BlockPos(40, 5, -6)));
        CompoundTag settings = new CompoundTag();
        settings.putInt("width", 7);
        CommissionPacket sent = new CommissionPacket(java.util.UUID.randomUUID(),
            ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "arch_bridge"), hint.save(), settings,
            Direction.EAST, "西桥", true);
        net.minecraft.network.RegistryFriendlyByteBuf buffer = new net.minecraft.network.RegistryFriendlyByteBuf(
            io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
        CommissionPacket.STREAM_CODEC.encode(buffer, sent);
        CommissionPacket read = CommissionPacket.STREAM_CODEC.decode(buffer);
        helper.assertValueEqual(read.colonyId(), sent.colonyId(), "the colony");
        helper.assertValueEqual(read.pattern(), sent.pattern(), "the pattern");
        helper.assertValueEqual(Hint.load(read.hint()).orElseThrow(), hint, "the mark");
        helper.assertValueEqual(read.settings().getInt("width"), 7, "the settings");
        helper.assertValueEqual(read.facing(), Direction.EAST, "the facing");
        helper.assertValueEqual(read.siteName(), "西桥", "the site's name");
        helper.assertTrue(read.raiseInCreative(), "whether to raise at once");
        helper.succeed();
    }

    private static final ResourceLocation MINE = ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "mine");

    private static CompoundTag defaults(Pattern pattern, int y) {
        CompoundTag picked = new CompoundTag();
        picked.putInt("y", y);
        Chosen loaded = Chosen.load(pattern.knobs(), picked);
        return Chosen.of(pattern.knobs(), new Settings() {
            public boolean flag(String key) {
                return loaded.flag(key);
            }

            public int count(String key) {
                return loaded.count(key);
            }

            public ResourceLocation choice(String key) {
                return loaded.choice(key);
            }

            public Optional<ItemSpec> items(String key) {
                for (Schema.Setting setting : pattern.knobs().settings()) {
                    if (setting instanceof Schema.Setting.Items slot && slot.key().equals(key)) {
                        return Optional.of(ItemSpec.of(slot.byDefault().get(0).id()));
                    }
                }
                return Optional.empty();
            }
        }).save();
    }

    private static void build(GameTestHelper helper, BuildSite site) {
        ServerLevel level = helper.getLevel();
        SimpleContainer pack = new SimpleContainer(128);
        Mob body = PersonBody.RESIDENT.get().create(level);
        body.setPos(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 7, 1))));
        Worker worker = (Worker) Proxy.newProxyInstance(GrowingGameTests.class.getClassLoader(),
            new Class<?>[] {Worker.class}, (proxy, method, args) -> switch (method.getName()) {
                case "body" -> body;
                case "pack" -> pack;
                case "resident" -> new Resident(body.getUUID(), ResourceLocation.withDefaultNamespace("pig"));
                case "effects" -> Effects.NONE;
                case "placement" -> Placement.NOWHERE;
                case "held" -> new ItemStack(Items.DIAMOND_PICKAXE);
                case "within", "route", "supplied" -> List.of();
                default -> null;
            });
        for (int round = 0; round < 64 && !site.complete(); round++) {
            boolean progressed = false;
            for (BuildSite.Piece piece : site.pieces()) {
                if (!site.before(piece).isEmpty()) {
                    continue;
                }
                pack.clearContent();
                int slot = 0;
                for (var need : piece.node().spec().needs()) {
                    var item = Goods.members(need.spec()).stream().findFirst().orElseThrow();
                    long left = need.count();
                    while (left > 0) {
                        int count = (int) Math.min(left, item.getDefaultMaxStackSize());
                        pack.setItem(slot++, new ItemStack(item, count));
                        left -= count;
                    }
                }
                Outcome outcome = Commit.run(level, piece.node(), worker);
                helper.assertTrue(outcome instanceof Outcome.Done, "headframe work succeeds: " + outcome);
                site.ended(piece.node().id(), Ending.DONE);
                progressed = true;
            }
            site.tick(level);
            helper.assertTrue(progressed || site.complete(), "headframe construction makes progress; left=" + site.left()
                + " refusals=" + site.refusals().entrySet().stream().limit(3).toList());
        }
    }

    @GameTest(template = "empty_large", timeoutTicks = 300)
    public static void raisingAMineContinuesPastTheHeadframeUntilEnded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ground = helper.absolutePos(new BlockPos(10, 24, 10));
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                level.setBlock(ground.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        Pattern mine = Patterns.find(MINE).orElseThrow();
        CompoundTag settings = defaults(mine, ground.getY() - 16);
        settings.putInt("pairs", 1);
        settings.putInt("branch", 4);
        settings.putBoolean("chase", false);
        BlockPos mark = ground.above();
        Hint hint = new Hint.Zone(mark, mark.offset(2, 0, 2));
        Growing growing = Growing.begun(MINE, "", hint, settings, Direction.NORTH, 3L,
            WorldPos.of(level, mark), Optional.empty(), "");
        CompletableFuture<BuildPackets.Raised> raising = BuildPackets.raiseRounds(level, growing);
        helper.startSequence().thenWaitUntil(() -> drawn(helper, raising)).thenExecute(() -> {
            BuildPackets.Raised result = raising.join();
            helper.assertTrue(result.refusal().isEmpty(), "the mine reaches Ended: " + result.refusal());
            helper.assertTrue(result.rounds() > 2, "the mine raises its headframe, flights and drifts");
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 200)
    public static void aMineRaisesItsHeadframeAsAnOrderThenStartsItsStairOnceThatStands(GameTestHelper helper) {
        Rig rig = rig(helper);
        ServerLevel level = rig.level();
        // Survey real ground above the GameTest enclosure, whose barrier roof otherwise looks like terrain.
        BlockPos ground = helper.absolutePos(new BlockPos(10, 24, 10));
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                level.setBlock(ground.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        Pattern mine = Patterns.find(MINE).orElseThrow(() -> new AssertionError("the mine is not loaded"));
        BlockPos mark = ground.above();
        Hint hint = new Hint.Zone(mark, mark.offset(2, 0, 2));
        Growing fresh = Growing.begun(MINE, "", hint, defaults(mine, ground.getY() - 16), Direction.NORTH, 3L,
            WorldPos.of(level, mark), Optional.empty(), "");
        BuildPresence presence = new BuildPresence(rig.colony(), level.getServer());
        CompletableFuture<Optional<Component>> first = presence.grow(level, fresh);
        AtomicReference<CompletableFuture<Round>> again = new AtomicReference<>();
        helper.startSequence().thenWaitUntil(() -> drawn(helper, first)).thenExecute(() -> {
            filed(helper, first, "the headframe is filed");
            helper.assertValueEqual(standing(rig), 1, "one order stands for the headframe");
            again.set(Rounds.draw(level, fresh));
        }).thenWaitUntil(() -> drawn(helper, again.get())).thenExecute(() -> {
            if (!(again.get().join() instanceof Round.Grew head)) {
                helper.fail("the headframe draws again the same way");
                return;
            }
            int placed = 0;
            for (Draft.Cell cell : head.ready().draft().cells()) {
                BlockPos at = head.ready().corner().offset(cell.offset());
                if (!cell.state().isAir()) {
                    helper.assertTrue(!level.getBlockState(at).equals(cell.state()),
                        "nothing of the headframe is there before it is built, at " + at.toShortString());
                    placed++;
                }
            }
            helper.assertTrue(placed > 60, "a headframe of posts, deck, rails and roof, got " + placed + " blocks");
            presence.settle(level.getServer());
            UUID order = rig.saved().get().getCompound("book").getList("buildOrders", Tag.TAG_COMPOUND)
                .getCompound(0).getUUID("id");
            BuildSite site = presence.site(order).orElseThrow();
            build(helper, site);
            helper.assertTrue(site.complete(), "successful headframe operations are latched before growth advances");
            helper.assertValueEqual(round(rig), 1, "the stair is not drawn before the completed round is settled");
            presence.settle(level.getServer());
        }).thenWaitUntil(() -> helper.assertValueEqual(round(rig), 2, "the mine waits on its first flight"))
            .thenExecute(() -> {
                helper.assertValueEqual(standing(rig), 1, "the stair is filed once the headframe stands");
                presence.settle(level.getServer());
                UUID order = rig.saved().get().getCompound("book").getList("buildOrders", Tag.TAG_COMPOUND)
                    .getCompound(0).getUUID("id");
                helper.assertTrue(presence.site(order).isPresent(), "the first stair flight has an active work site");
                helper.assertTrue(Closures.in(level).stream().anyMatch(site ->
                        site.holds(mark.below()) && site.holds(mark.above(4))),
                    "the stair's work area retains the headframe above it so residents can return through completed rounds");
            })
            .thenSucceed();
    }
}
