package io.github.izakyl.folkways.capture.mixin;

import net.neoforged.neoforge.client.loading.NoVizFallback;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Blockwright's GLFW window has NO_API: its GL context is a separate, current EGL context. NeoForge
// normally reads GLFW's window attributes, which truthfully report 0.0 for that placeholder window.
@Mixin(value = NoVizFallback.class, remap = false)
public abstract class NoVizFallbackMixin {

    @Unique
    private static final Logger folkways$captureLogger = LoggerFactory.getLogger("folkways-capture");

    @Inject(method = "glVersion", at = @At("HEAD"), cancellable = true, remap = false)
    private static void folkways$readActiveContext(CallbackInfoReturnable<String> result) {
        if (!Boolean.getBoolean("blockwright.enabled")) {
            return;
        }
        int major = GL11.glGetInteger(GL30.GL_MAJOR_VERSION);
        int minor = GL11.glGetInteger(GL30.GL_MINOR_VERSION);
        if (major <= 0) {
            folkways$captureLogger.warn("No OpenGL version from offscreen context; retaining the feature check");
            return;
        }
        String feature = major + "." + minor;
        folkways$captureLogger.info("OpenGL feature {} read from the active render context: {} ({} / {})",
            feature, GL11.glGetString(GL11.GL_VERSION), GL11.glGetString(GL11.GL_VENDOR),
            GL11.glGetString(GL11.GL_RENDERER));
        result.setReturnValue(feature);
    }
}
