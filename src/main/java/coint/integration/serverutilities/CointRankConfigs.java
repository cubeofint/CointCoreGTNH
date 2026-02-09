package coint.integration.serverutilities;

import java.util.Arrays;
import java.util.List;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import serverutils.events.RegisterRankConfigEvent;
import serverutils.lib.config.ConfigStringEnum;
import serverutils.lib.config.ConfigTimer;
import serverutils.lib.math.Ticks;

public final class CointRankConfigs {

    public static final String REPAIR_MODE = "cointcore.repair.level";
    public static final String REPAIR_COOLDOWN = "cointcore.repair.cooldown";
    public static final String HEAL_COOLDOWN = "cointcore.heal.cooldown";
    public static final String FEED_COOLDOWN = "cointcore.feed.cooldown";

    private static final List<String> REPAIR_MODES = Arrays.asList("hand", "all");

    @SubscribeEvent
    public void onRegisterRankConfigs(RegisterRankConfigEvent event) {
        event.register(
            REPAIR_MODE,
            new ConfigStringEnum(REPAIR_MODES, "all"),
            new ConfigStringEnum(REPAIR_MODES, "all"));
        event.register(REPAIR_COOLDOWN, new ConfigTimer(Ticks.NO_TICKS, Ticks.DAY), new ConfigTimer(Ticks.NO_TICKS));
        event.register(HEAL_COOLDOWN, new ConfigTimer(Ticks.NO_TICKS, Ticks.DAY), new ConfigTimer(Ticks.NO_TICKS));
        event.register(FEED_COOLDOWN, new ConfigTimer(Ticks.NO_TICKS, Ticks.DAY), new ConfigTimer(Ticks.NO_TICKS));
    }
}
