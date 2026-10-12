package coint.events;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Keeps /nv night vision on while it is enabled. The effect is given with a finite duration and refreshed, since a
 * longer one is cut to 32767 ticks in the effect packet and silently ends on the client while the server keeps it.
 * The flag lives in the persisted player data, so it survives death and relog.
 */
@EventBusSubscriber
public final class NightVisionHandler {

    private static final String TAG = "cointcore_nv";
    /** Long enough to never show the end-of-effect flicker (last 10 s) between refreshes. */
    private static final int DURATION = 20 * 60;
    private static final int REFRESH_INTERVAL = 200;

    private NightVisionHandler() {}

    public static boolean isEnabled(EntityPlayer player) {
        return persisted(player).getBoolean(TAG);
    }

    public static void setEnabled(EntityPlayerMP player, boolean enabled) {
        NBTTagCompound data = persisted(player);
        if (enabled) {
            data.setBoolean(TAG, true);
            // Drop any old effect (e.g. an "infinite" one from the previous /nv the client no longer shows)
            player.removePotionEffect(Potion.nightVision.id);
            apply(player);
        } else {
            data.removeTag(TAG);
            player.removePotionEffect(Potion.nightVision.id);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof EntityPlayerMP player)
            || player.ticksExisted % REFRESH_INTERVAL != 0
            || !isEnabled(player)) {
            return;
        }
        apply(player);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP player && isEnabled(player)) {
            apply(player);
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.player instanceof EntityPlayerMP player && isEnabled(player)) {
            apply(player);
        }
    }

    private static void apply(EntityPlayerMP player) {
        PotionEffect current = player.getActivePotionEffect(Potion.nightVision);
        if (current != null && current.getDuration() > DURATION - REFRESH_INTERVAL) {
            return;
        }
        // Combines with a running effect (longer duration wins) and re-sends it to the client
        player.addPotionEffect(new PotionEffect(Potion.nightVision.id, DURATION, 0, true));
    }

    private static NBTTagCompound persisted(EntityPlayer player) {
        NBTTagCompound root = player.getEntityData();
        if (!root.hasKey(EntityPlayer.PERSISTED_NBT_TAG)) {
            root.setTag(EntityPlayer.PERSISTED_NBT_TAG, new NBTTagCompound());
        }
        return root.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
    }
}
