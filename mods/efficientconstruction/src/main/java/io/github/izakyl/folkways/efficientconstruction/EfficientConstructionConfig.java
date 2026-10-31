package io.github.izakyl.folkways.efficientconstruction;

import net.neoforged.neoforge.common.ModConfigSpec;

// What the efficient site is tuned by. The defaults are the ones the extension was designed around.
public final class EfficientConstructionConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static final ModConfigSpec.IntValue TICKS_PER_BLOCK;
    static final ModConfigSpec.IntValue WORKERS_PER_SITE;
    static final ModConfigSpec.IntValue BASKET_KINDS;
    static final ModConfigSpec.IntValue BASKET_ITEMS;
    static final ModConfigSpec SPEC;

    static {
        BUILDER.comment("How a build site raises itself while residents stand on it.").push("efficientConstruction");

        TICKS_PER_BLOCK = BUILDER
            .comment("Ticks each resident at the site adds per block placed. 16 is a quarter of a player's own pace;"
                + " one resident is one unit, so two residents at 16 place a block every 8 ticks.")
            .defineInRange("ticksPerBlock", 16, 1, 400);

        WORKERS_PER_SITE = BUILDER
            .comment("How many residents one site asks for at a time. Standing room on the site can ask for fewer.")
            .defineInRange("workersPerSite", 8, 1, 64);

        BASKET_KINDS = BUILDER
            .comment("How many kinds of material one resident carries on a trip. Each resident is given a run of the"
                + " plan and asks for exactly what that run spends, so a wide list is filled only when the stores"
                + " hold it. Raise it when the colony's stores are deep, lower it when only one resident turns up.")
            .defineInRange("basketKinds", 6, 1, 8);

        BASKET_ITEMS = BUILDER
            .comment("How many of each kind one resident carries. More means fewer trips, and a harder list to fill.")
            .defineInRange("basketItemsPerKind", 64, 1, 64);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private EfficientConstructionConfig() {
    }
}
