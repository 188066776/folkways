package io.github.izakyl.folkways.plugins.damagecontrol;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Worker;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public interface SuppressionSession extends AutoCloseable {
    Outcome suppress(ServerLevel level, BlockPos target);

    // True only after a paid native pulse was emitted at the requested target.
    default boolean performed() {
        return false;
    }

    default boolean valid() {
        return true;
    }

    // Empty means the session can act at its opening footing. A hose supplies its connected spray footings.
    default Set<BlockPos> footings(ServerLevel level, Worker who, WorldPos target) {
        return Set.of();
    }

    @Override
    void close();
}
