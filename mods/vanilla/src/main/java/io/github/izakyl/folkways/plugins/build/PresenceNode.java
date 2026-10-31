package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Recourse;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * A resident standing on a site that raises itself: what it is here for is the materials in its pack and the look
 * of someone at work, not the laying of any particular block.
 *
 * <p>It stays while the site has something to build and something still in the pack, and goes back for more when
 * the pack is empty. What it carried out of the colony and did not spend goes back.
 */
final class PresenceNode implements Node, Workload.Within {

    private static final int SWING_TICKS = 10;

    private final EfficientBuilder site;
    private final NodeSpec spec;
    private List<ItemStack> held = List.of();
    private boolean arrived;
    private long swungAt = Long.MIN_VALUE;

    PresenceNode(EfficientBuilder site, BlockPos stance) {
        this.site = site;
        this.spec = site.spec(this, stance);
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
    public Workload.Within.Turn next(ServerLevel level, Worker who) {
        if (!arrived) {
            arrived = true;
            held = new ArrayList<>(who.supplied());
            site.touched();
            site.staff();
        }
        if (site.complete() || site.idle(level) || empty()) {
            site.leave(this);
            return Workload.Within.LEAVE;
        }
        return Workload.Within.WAIT;
    }

    private boolean empty() {
        return held.stream().allMatch(ItemStack::isEmpty);
    }

    // The resident keeps at it while it stands there: the work itself is the site's.
    @Override
    public void tick(ServerLevel level, Worker who) {
        long now = level.getGameTime();
        if (now - swungAt < SWING_TICKS) {
            return;
        }
        swungAt = now;
        who.body().swing(InteractionHand.MAIN_HAND);
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        return new Outcome.Done(List.of(), List.of(), Optional.empty(), List.copyOf(held));
    }

    @Override
    public Recourse failing(ServerLevel level, RefusalKind why) {
        return Recourse.waitFor(100);
    }

    @Override
    public void ended(ServerLevel level, Ending how) {
        site.leave(this);
    }

    /** Whether this resident has actually come to the site yet. */
    boolean arrived() {
        return arrived;
    }

    /** How much of {@code spec} this pack still holds. */
    long holding(ItemSpec spec) {
        long count = 0L;
        for (ItemStack stack : held) {
            if (!stack.isEmpty() && Goods.matches(stack, spec)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** Takes up to {@code count} of {@code spec} out of the pack, leaving what was taken in {@code paid}. */
    long take(ItemSpec spec, long count, List<ItemStack> paid) {
        long taken = 0L;
        for (ItemStack stack : held) {
            if (taken >= count || stack.isEmpty() || !Goods.matches(stack, spec)) {
                continue;
            }
            long wanted = Math.min(count - taken, stack.getCount());
            if (wanted <= 0) {
                continue;
            }
            ItemStack piece = stack.copyWithCount((int) wanted);
            paid.add(piece);
            stack.shrink((int) wanted);
            taken += wanted;
        }
        return taken;
    }
}
