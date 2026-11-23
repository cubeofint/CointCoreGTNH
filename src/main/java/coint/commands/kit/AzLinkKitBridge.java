package coint.commands.kit;

import java.util.UUID;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointCore;
import coint.cluster.DonationDelivery;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Optional AzLink bridge. The website never supplies executable commands or item NBT. */
@EventBusSubscriber
public final class AzLinkKitBridge {

    private AzLinkKitBridge() {}

    public static int credit(UUID player, String kit, int amount, String receipt) {
        return DonationDelivery.credit(player, kit, amount, receipt);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) DonationDelivery.localTick();
    }

    public static void changed(String name, boolean removed) {
        try {
            Class.forName("com.azuriom.azlink.forge.legacy.kits.LegacyKitHooks")
                .getMethod(removed ? "removed" : "saved", String.class)
                .invoke(null, name);
        } catch (ClassNotFoundException absent) {
            // AzLink is optional; the normal kit command remains usable without it.
        } catch (ReflectiveOperationException error) {
            CointCore.LOG.warn("[KitManifest] Publication hook failed for {}", name, error);
        }
    }
}
