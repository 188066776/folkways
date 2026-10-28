package io.github.izakyl.folkways.core.engine.inventory;

import io.github.izakyl.folkways.core.engine.EngineMode;
import io.github.izakyl.folkways.core.engine.travel.TravelBudget;
import net.minecraft.Util;

public final class ObservationBudget {

    private long until;
    private long remaining;
    private int steps;
    private boolean taken;

    public ObservationBudget(int steps) {
        this.steps = steps;
        remaining = EngineMode.singleThread() ? -1 : TravelBudget.wallClockNanos();
        resume();
    }

    public void resume() {
        until = remaining < 0 ? -1 : Util.getNanos() + remaining;
    }

    public void pause() {
        if (until >= 0) {
            remaining = Math.max(0, until - Util.getNanos());
        }
    }

    public boolean spent() {
        if (!taken) {
            taken = true;
            return steps-- <= 0;
        }
        return steps-- <= 0 || until >= 0 && Util.getNanos() >= until;
    }
}
