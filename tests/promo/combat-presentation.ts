import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";

export const CLEAR_STAGING_MESSAGES = defineTask<void, boolean>({
  name: "folkways.promo.combat-clear-staging-messages",
  side: "client",
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft"],
    body: `
var client = Minecraft.getInstance();
client.getToasts().clear();
client.gui.getChat().clearMessages(false);
return true;
`,
  }),
});
