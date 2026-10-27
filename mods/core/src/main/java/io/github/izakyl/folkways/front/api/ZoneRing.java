package io.github.izakyl.folkways.front.api;

import net.minecraft.core.BlockPos;

/** A server-described radius shown while its zone is hovered or selected. */
public record ZoneRing(BlockPos centre, double radius) {
}
