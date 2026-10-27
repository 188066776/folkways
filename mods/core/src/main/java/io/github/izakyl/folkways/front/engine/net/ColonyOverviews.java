package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.front.engine.colony.ColonyLooks;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Shared short-lived snapshots; player-relative selection stays outside the shared cache. */
@EventBusSubscriber(modid = FolkwaysMod.CORE_ID)
public final class ColonyOverviews {

    private static final Map<ServerLevel, Map<UUID, Cached>> BY_LEVEL = new HashMap<>();
    private static final long OVERVIEW_TICKS = 20;
    private static final long LOOK_TICKS = 10;
    private static final int CLEANUP_INTERVAL_TICKS = 20;
    private static final long CACHE_RETENTION_TICKS = 100;

    private static final class Cached {
        long overviewAt = Long.MIN_VALUE;
        ColonyOverviewPacket overview;
        long looksAt = Long.MIN_VALUE;
        List<ColonyView> views;
        final Map<UUID, LookLines> looks = new HashMap<>();
    }

    private ColonyOverviews() {
    }

    private static Cached cached(ServerLevel level, Colony colony) {
        var colonies = BY_LEVEL.computeIfAbsent(level, ignored -> new HashMap<>());
        return colonies.computeIfAbsent(colony.id(), ignored -> new Cached());
    }

    static ColonyOverviewPacket of(ServerPlayer player, Colony colony) {
        ServerLevel level = player.serverLevel();
        Cached cached = cached(level, colony);
        long now = level.getGameTime();
        if (cached.overview == null || now < cached.overviewAt || now - cached.overviewAt >= OVERVIEW_TICKS) {
            cached.overview = FolkwaysUiNetworkEvents.buildOverview(level, colony);
            cached.overviewAt = now;
        }
        var common = cached.overview;
        var near = player.blockPosition();
        var ghosts = new ArrayList<>(common.ghosts());
        if (ghosts.size() > ColonyOverviewPacket.MAX_GHOSTS) {
            ghosts.sort(Comparator.comparingDouble(ghost -> ghost.pos().distSqr(near)));
            ghosts.subList(ColonyOverviewPacket.MAX_GHOSTS, ghosts.size()).clear();
        }
        var placards = new ArrayList<>(common.placards());
        placards.sort(Comparator.comparingDouble(placard ->
            FolkwaysUiNetworkEvents.corner(placard.outline()).distSqr(near)));
        return new ColonyOverviewPacket(common.members(), common.zones(), List.copyOf(ghosts), common.paths(),
            common.notes(), placards);
    }

    // Explicit edits must be visible immediately, including to other viewers of this colony.
    static ColonyOverviewPacket fresh(ServerPlayer player, Colony colony) {
        for (var colonies : BY_LEVEL.values()) {
            colonies.remove(colony.id());
        }
        return of(player, colony);
    }

    static LookAtSnapshotPacket looks(ServerPlayer player, Colony colony) {
        ServerLevel level = player.serverLevel();
        Cached cached = cached(level, colony);
        refreshViews(cached, colony, level);
        List<LookLines> selected = new ArrayList<>();
        for (var body : ColonyLooks.nearest(level, colony, player, LookLines.MAX_RESIDENTS)) {
            selected.add(cached.looks.computeIfAbsent(body.id(), ignored ->
                new LookLines(body.mob().getId(), ColonyLooks.of(colony, body, cached.views))));
        }
        return new LookAtSnapshotPacket(List.copyOf(selected));
    }

    private static void refreshViews(Cached cached, Colony colony, ServerLevel level) {
        long now = level.getGameTime();
        if (cached.views == null || now < cached.looksAt || now - cached.looksAt >= LOOK_TICKS) {
            cached.views = colony.views(level.getServer());
            cached.looks.clear();
            cached.looksAt = now;
        }
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        // Views can also reference other dimensions of this server.
        if (event.getLevel() instanceof ServerLevel) {
            BY_LEVEL.clear();
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % CLEANUP_INTERVAL_TICKS != 0) {
            return;
        }
        BY_LEVEL.entrySet().removeIf(entry -> {
            long now = entry.getKey().getGameTime();
            entry.getValue().values().removeIf(old -> {
                return (old.overview == null || now - old.overviewAt >= CACHE_RETENTION_TICKS)
                    && (old.views == null || now - old.looksAt >= CACHE_RETENTION_TICKS);
            });
            return entry.getValue().isEmpty();
        });
    }

    public static void forgetServer() {
        BY_LEVEL.clear();
    }
}
