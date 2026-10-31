package io.github.izakyl.folkways.plugins.build;

/**
 * The switch an optional add-on flips to build an order with the site raising itself instead of the residents
 * placing one block at a time.
 *
 * <p>Nothing here is on by default: a world without the add-on installed never calls {@link #activate}, so every
 * site is the ordinary {@link BuildSite} and the build behaves exactly as it always has.
 */
public final class EfficientConstruction {

    /**
     * What the add-on asks of a site. Read once, when the add-on activates.
     *
     * <p>{@code basketKinds} and {@code basketItemsPerKind} say how much a hand carries on one trip. They are a
     * trade-off the colony makes, not a preference: a node's whole list is reserved before anyone is sent, and a
     * list too wide to fill leaves the node waiting for goods instead of sending a hand. Wide is fewer trips; narrow
     * is more hands working at once.
     */
    public record Settings(int ticksPerBlock, int workersPerSite, int basketKinds, int basketItemsPerKind) {

        public static final Settings DEFAULTS = new Settings(16, 8, 6, 64);

        public Settings {
            if (ticksPerBlock < 1) {
                throw new IllegalArgumentException("a block cannot be laid faster than once a tick");
            }
            if (workersPerSite < 1) {
                throw new IllegalArgumentException("a site needs at least one worker to start");
            }
            if (basketKinds < 1 || basketItemsPerKind < 1) {
                throw new IllegalArgumentException("a hand must be able to bring something to build with");
            }
        }
    }

    private static volatile Settings settings = Settings.DEFAULTS;

    private static volatile boolean active;

    private EfficientConstruction() {
    }

    /** Turns the efficient site on for every order from now on. Called by the add-on's mod constructor. */
    public static void activate(Settings requested) {
        settings = requested == null ? Settings.DEFAULTS : requested;
        active = true;
    }

    public static boolean active() {
        return active;
    }

    public static Settings settings() {
        return settings;
    }
}
