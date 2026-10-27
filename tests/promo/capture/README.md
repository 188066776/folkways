# Offscreen capture fixture

This is a test fixture for the `promo-combat` client, not a Folkways product mod.
Its jar stays under `tests/promo/capture/build/libs/`, outside the product artifacts. The `promo-combat` profile installs it only on the client.

Blockwright renders through a real EGL OpenGL context while its GLFW window uses
`GLFW_NO_API`. With NeoForge's early loading window disabled, `NoVizFallback.glVersion()`
reads that GLFW window's context attributes and returns `0.0`. Consequently mods
such as Superb Warfare cannot pass their OpenGL feature requirement, even though
the active EGL context meets it.

The fixture's client-only Mixin changes the version lookup only when the launcher
sets `blockwright.enabled=true`. It queries `GL_MAJOR_VERSION` and `GL_MINOR_VERSION`
from the current OpenGL context, after Minecraft has initialized its renderer and
before NeoForge validates mod feature requirements. It logs the feature value and
the actual driver version, vendor and renderer as `folkways-capture` evidence.
It supplies no hard-coded version, bypasses no capability requirement, and leaves
the original lookup unchanged if no positive major version is available.
Ordinary Minecraft launches keep their original behavior.

This workaround belongs in Blockwright's `NoVizFallback` integration long term.
It does not modify Superb Warfare, NeoForge jars, FML configuration files or another
process's files during startup. Remove the fixture after Blockwright learns to
report its active EGL context through NeoForge's fallback version lookup.
