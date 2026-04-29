package coint.integration.serverutilities;

import java.util.Arrays;
import java.util.List;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import serverutils.events.RegisterRankConfigEvent;
import serverutils.lib.config.ConfigInt;
import serverutils.lib.config.ConfigStringEnum;
import serverutils.lib.config.ConfigTimer;
import serverutils.lib.math.Ticks;

@EventBusSubscriber
public final class CointRankConfigs {

    public static final String REPAIR_MODE = "cointcore.repair.level";
    public static final String REPAIR_COOLDOWN = "cointcore.repair.cooldown";
    public static final String HEAL_COOLDOWN = "cointcore.heal.cooldown";
    public static final String FEED_COOLDOWN = "cointcore.feed.cooldown";

    /**
     * Bonus claim chunks added on top of serverutilities.claims.max_chunks.
     * The highest value among all team members is used (max-based logic).
     * Set per-rank in ranks.txt.
     */
    public static final String BONUS_CLAIM_CHUNKS = "cointcore.bonus_chunks";
    public static final String EBOBONUS_CLAIM_CHUNKS = "cointcore.ebobonus_chunks";

    /**
     * Bonus force-load chunks added on top of serverutilities.chunkloader.max_chunks.
     * The highest value among all team members is used (max-based logic).
     * Set per-rank in ranks.txt.
     */
    public static final String BONUS_FORCELOAD_CHUNKS = "cointcore.bonus_forceload_chunks";
    public static final String EBOBONUS_FORCELOAD_CHUNKS = "cointcore.ebobonus_forceload_chunks";

    /**
     * Bonus homes added on top of serverutilities.homes.max.
     * Applied per-player from their assigned rank.
     * Set per-rank in ranks.txt.
     */
    public static final String BONUS_HOMES = "cointcore.bonus_homes";

    private static final List<String> REPAIR_MODES = Arrays.asList("inventory", "all");

    @SubscribeEvent
    public static void onRegisterRankConfigs(RegisterRankConfigEvent event) {
        event.register(
            REPAIR_MODE,
            new ConfigStringEnum(REPAIR_MODES, "inventory"),
            new ConfigStringEnum(REPAIR_MODES, "inventory"));
        event.register(REPAIR_COOLDOWN, new ConfigTimer(Ticks.NO_TICKS, Ticks.DAY), new ConfigTimer(Ticks.NO_TICKS));
        event.register(HEAL_COOLDOWN, new ConfigTimer(Ticks.NO_TICKS, Ticks.DAY), new ConfigTimer(Ticks.NO_TICKS));
        event.register(FEED_COOLDOWN, new ConfigTimer(Ticks.NO_TICKS, Ticks.DAY), new ConfigTimer(Ticks.NO_TICKS));
        // Bonus claim chunks: default 0, max 10000
        event.register(BONUS_CLAIM_CHUNKS, new ConfigInt(0, 0, 10000), new ConfigInt(0, 0, 10000));
        // Bonus force-load chunks: default 0, max 10000
        event.register(BONUS_FORCELOAD_CHUNKS, new ConfigInt(0, 0, 10000), new ConfigInt(0, 0, 10000));
        // Bonus homes: default 0, max 30000
        event.register(BONUS_HOMES, new ConfigInt(0, 0, 30000), new ConfigInt(0, 0, 30000));
    }
}
