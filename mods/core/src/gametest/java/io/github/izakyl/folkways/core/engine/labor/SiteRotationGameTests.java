package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.FolkwaysMod;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SiteRotationGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(SiteRotationGameTests.class);
    }

    @GameTest(template = "empty")
    public static void everySiteStartsOnce(GameTestHelper helper) {
        boolean[] seen = new boolean[5];
        int cursor = 0;
        for (int tick = 0; tick < seen.length; tick++) {
            int start = Labor.startSite(cursor, seen.length);
            helper.assertTrue(!seen[start], "site started twice");
            seen[start] = true;
            cursor = (start + 1) % seen.length;
        }
        for (boolean started : seen) {
            helper.assertTrue(started, "site never started");
        }
        helper.succeed();
    }
}
