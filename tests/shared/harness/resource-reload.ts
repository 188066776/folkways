import { defineTask, javaTask, type MinecraftClient } from "@izakyl/blockwright-minecraft";

type ResourceReload = { pending: boolean; overlay: string };

const RESOURCE_RELOAD = defineTask<void, ResourceReload>({
  name: "folkways.client.resource-reload",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft"],
    body: `
Minecraft minecraft = Minecraft.getInstance();
var pending = Minecraft.class.getDeclaredField("pendingReload");
pending.setAccessible(true);
var overlay = minecraft.getOverlay();
return Map.of("pending", pending.get(minecraft) != null,
    "overlay", overlay == null ? "none" : overlay.getClass().getName());
`,
  }),
});

/** Waits for queued reloads and the loading overlay's fade to finish before using the client. */
export async function waitForResourceReload(client: MinecraftClient, timeoutMs = 180_000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (true) {
    const state = await client.run(RESOURCE_RELOAD, undefined, { timeoutMs: 20_000 });
    if (!state.pending && state.overlay === "none") {
      return;
    }
    if (Date.now() >= deadline) {
      throw new Error(`resource reload still running: ${state.overlay}`
        + (state.pending ? " (reload pending)" : ""));
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
}
