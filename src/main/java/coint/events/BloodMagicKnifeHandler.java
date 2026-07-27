package coint.events;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import WayofTime.alchemicalWizardry.api.event.SacrificeKnifeUsedEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import serverutils.lib.util.NBTUtils;

@EventBusSubscriber
public class BloodMagicKnifeHandler {

    @SubscribeEvent
    public static void onKnife(SacrificeKnifeUsedEvent event) {
        var god = NBTUtils.getPersistedData(event.player, false)
            .getBoolean("god");
        if (god) {
            event.shouldDrainHealth = false;
            event.shouldFillAltar = true;
        }
    }
}
