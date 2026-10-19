package coint.integration.twilightforest;

import java.lang.reflect.Field;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.stats.Achievement;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointCore;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;

/**
 * Twilight Forest achievements form a chain, and an achievement is only given once its parent is: Portal, then
 * Arrival, then everything else, boss progression included. TF gives Portal only when a portal is lit (and Arrival
 * not even then), so a player brought in by world travel never gets them and can't progress through the mod. Who is in
 * the Twilight Forest, by any way, gets both, in order.
 */
@EventBusSubscriber
public final class TwilightArrivalAchievements {

    private static final String MOD_ID = "TwilightForest";

    private static boolean resolved;
    private static Field dimensionField;
    private static Achievement portal;
    private static Achievement arrival;

    private TwilightArrivalAchievements() {}

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        grantIfInForest(event.player, event.toDim);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        grantIfInForest(event.player, event.player.dimension);
    }

    private static void grantIfInForest(EntityPlayer player, int dimension) {
        if (!(player instanceof EntityPlayerMP mp) || !resolve()) {
            return;
        }
        try {
            if (dimension != dimensionField.getInt(null)) {
                return;
            }
        } catch (IllegalAccessException e) {
            return;
        }
        // The parent first: the child is refused while the parent is missing.
        if (!mp.func_147099_x()
            .hasAchievementUnlocked(portal)) {
            mp.triggerAchievement(portal);
        }
        if (!mp.func_147099_x()
            .hasAchievementUnlocked(arrival)) {
            mp.triggerAchievement(arrival);
        }
    }

    /** Finds TF's dimension and the two achievements once; false when TF is absent or they are not there. */
    private static boolean resolve() {
        if (resolved) {
            return portal != null && arrival != null && dimensionField != null;
        }
        resolved = true;
        if (!Loader.isModLoaded(MOD_ID)) {
            return false;
        }
        try {
            dimensionField = Class.forName("twilightforest.TwilightForestMod")
                .getField("dimensionID");
            Class<?> page = Class.forName("twilightforest.TFAchievementPage");
            portal = (Achievement) page.getField("twilightPortal")
                .get(null);
            arrival = (Achievement) page.getField("twilightArrival")
                .get(null);
        } catch (ReflectiveOperationException | ClassCastException e) {
            CointCore.LOG.warn("[TwilightForest] Arrival achievements not found: {}", e.toString());
            portal = arrival = null;
            return false;
        }
        return portal != null && arrival != null;
    }
}
