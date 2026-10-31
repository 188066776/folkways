package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Recourse;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * A resident standing on a site that raises itself: what it is here for is being here, carrying the basket, and
 * looking busy, not the laying of any particular block.
 *
 * <p>The stay asks the colony for the materials the next stretch of the plan spends and carries them in its pack.
 * Each block is then handed to the resident as a shot of its own ({@link ShotNode}), and that shot draws what it
 * costs from the pack. A stay whose basket is spent goes back for another.
 */
final class PresenceNode implements Node, Workload.Within {

    private static final int SWING_TICKS = 10;

    /** Long enough with nothing this hand can pay for: it is better sent back for a basket that fits what is left. */
    private static final int BORED_TICKS = 200;

    private final EfficientBuilder site;
    private final BlockPos stance;
    private final NodeSpec spec;
    private boolean arrived;
    private boolean drained;
    private Worker who;
    private long waitingSince = Long.MIN_VALUE;
    private long swungAt = Long.MIN_VALUE;

    PresenceNode(EfficientBuilder site, BlockPos stance) {
        this.site = site;
        this.stance = stance;
        this.spec = site.staySpec(this, stance);
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
            this.who = who;
            site.touched();
            site.handArrived(this, who);
        }
        // This hand's own basket is spent: it goes back for another. What another hand could not carry says
        // nothing about this one.
        if (drained) {
            drained = false;
            site.rest(100);
            return Workload.Within.LEAVE;
        }
        return site.hand(level, this);
    }

    /**
     * Whether this hand carries what a piece costs. A piece needing what this one did not bring is not handed to
     * it: the core would draw nothing, the shot would come back for want of goods, and the site would be no further
     * on. The next basket is reckoned from what is still up, so what is left out this time comes in with another.
     */
    boolean canPay(List<Need> cost) {
        if (who == null) {
            return false;
        }
        Container pack = who.pack();
        for (Need need : cost) {
            long have = 0L;
            for (int slot = 0; slot < pack.getContainerSize(); slot++) {
                ItemStack stack = pack.getItem(slot);
                if (!stack.isEmpty() && Goods.matches(stack, need.spec())) {
                    have += stack.getCount();
                }
            }
            if (have < need.count()) {
                return false;
            }
        }
        return true;
    }

    /** A shot was handed over: the hand is not idle. */
    void paid() {
        waitingSince = Long.MIN_VALUE;
    }

    /** Nothing up could be paid for with what this hand carries. */
    void waited(ServerLevel level) {
        if (waitingSince == Long.MIN_VALUE) {
            waitingSince = level.getGameTime();
        }
    }

    /** Long enough with nothing it can build: it is better sent back for a basket that fits what is left. */
    boolean bored(ServerLevel level) {
        return waitingSince != Long.MIN_VALUE && level.getGameTime() - waitingSince > BORED_TICKS;
    }

    /** The shot this hand was given came back for want of goods: its basket, not the site's, is spent. */
    void drain() {
        drained = true;
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
        return Outcome.done();
    }

    @Override
    public Recourse failing(ServerLevel level, RefusalKind why) {
        return Recourse.waitFor(100);
    }

    @Override
    public void ended(ServerLevel level, Ending how) {
        site.leave(this);
    }

    // Every shot handed to this stay comes back here, taken or dropped.
    @Override
    public void ended(ServerLevel level, Node piece, Ending how) {
        if (piece instanceof ShotNode shot) {
            site.dropped(shot, how);
        }
    }

    boolean arrived() {
        return arrived;
    }

    BlockPos stance() {
        return stance;
    }
}
