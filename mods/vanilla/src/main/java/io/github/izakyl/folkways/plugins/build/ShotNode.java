package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Recourse;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * One shot of an order, done by a resident already standing on the site.
 *
 * <p>Materials reach a node only when the core commits it (see {@code Commit}), so a shot has to be a node of its own
 * with its own cost: that is what brings the goods and what spends them. The work is done where the resident stands,
 * not where the block goes, so taking a shot never sends it walking.
 */
final class ShotNode implements Node {

    private final EfficientBuilder site;
    private final Layering.Work work;
    private final BuildJob job;
    private final NodeSpec spec;

    ShotNode(EfficientBuilder site, Layering.Work work, BuildJob job, BlockPos stance, List<Need> cost,
            ServerLevel level) {
        this.site = site;
        this.work = work;
        this.job = job;
        this.spec = NodeSpec.of(UUID.randomUUID(), BuildContent.ID,
                new WorkSite.AtBlock(site.target().cell(stance)),
                Stances.at(level, stance),
                Workload.Once.of(EfficientConstruction.settings().ticksPerBlock()))
            .needs(cost)
            .vocation(BuildContent.trade())
            .gesture(WorkGesture.SWING)
            .focus(job.focus().cell())
            .doing(BuildContent.BUILDING)
            .done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public boolean ready(ServerLevel level) {
        return true;
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        return site.shoot(work, job, level, who);
    }

    @Override
    public Recourse failing(ServerLevel level, RefusalKind why) {
        return Recourse.FAIL;
    }

    @Override
    public void ended(ServerLevel level, Ending how) {
        site.release(work);
    }
}
