package coint.integration.galacticraft;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import micdoodle8.mods.galacticraft.api.event.oxygen.GCCoreOxygenSuffocationEvent;
import serverutils.lib.util.NBTUtils;

/**
 * Extends the god-mode protection to Galacticraft's oxygen suffocation mechanic.
 *
 * <p>
 * {@link GCCoreOxygenSuffocationEvent.Pre} is the official GC extension point fired
 * inside {@code GCPlayerHandler.checkOxygen()} just before the suffocation hit. When
 * this event is cancelled, GC sets {@code playerStats.oxygenSetupValid = true}, which:
 * <ul>
 * <li>suppresses the "OXYGEN SETUP INVALID!" HUD warning</li>
 * <li>prevents {@code damageCounter} and {@code incrementalDamage} from being set</li>
 * <li>skips the actual {@code attackEntityFrom} call (already covered by
 * {@code MixinEntityPlayer}, but avoiding it entirely is cleaner)</li>
 * </ul>
 *
 * <p>
 * Thermal damage and its side-effect potions are handled separately by
 * {@code MixinGCPlayerHandler}, since GC provides no
 * Forge event for the thermal system.
 */
public class GalacticraftGodHandler {

    @SubscribeEvent
    public void onOxygenSuffocation(GCCoreOxygenSuffocationEvent.Pre event) {
        if (!(event.entityLiving instanceof EntityPlayerMP player)) {
            return;
        }
        NBTTagCompound persisted = NBTUtils.getPersistedData(player, false);
        if (persisted.getBoolean("god")) {
            event.setCanceled(true);
        }
    }
}
