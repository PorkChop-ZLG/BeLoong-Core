package com.zonlong.beloong.gametest;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(BeLoongCore.MODID)
@PrefixGameTestTemplate(false)
public final class BeloongWaterGameTests {

    private BeloongWaterGameTests() {}

    @GameTest(template = "beloong_water_game_test")
    public static void smokeTest(GameTestHelper helper) {
        helper.succeed();
    }
}
