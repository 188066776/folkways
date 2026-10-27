package io.github.izakyl.folkways.plugins.combat.ui;

import io.github.izakyl.folkways.front.api.panel.PageKind;
import net.minecraft.resources.ResourceLocation;

public final class LoadoutContent {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "loadouts");
    public static final PageKind PAGE =
        PageKind.boarded(ID, "folkways.loadouts.title", ResourceLocation.withDefaultNamespace("armor_stand"))
            .inNavigation();

    private LoadoutContent() {
    }
}
