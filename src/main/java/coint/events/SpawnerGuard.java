package coint.events;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.world.BlockEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.integration.serverutilities.CointSUPermissions;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import serverutils.lib.util.permission.PermissionAPI;

/**
 * Players may not place vanilla mob spawners (they were picked up and carried to bases). The JABBA dolly side is
 * covered by {@code MixinJabbaDollySpawnerGuard}.
 */
@EventBusSubscriber
public final class SpawnerGuard {

    public static final String DENY_MESSAGE = "§cВанильные спавнеры нельзя ставить и переносить.";

    private SpawnerGuard() {}

    /**
     * Creative mode is required on top of the permission: staff ranks often hold every node ("*"), and moving spawners
     * should stay a deliberate admin action, not something a staff member does while playing survival.
     */
    public static boolean canHandleSpawners(EntityPlayer player) {
        return player instanceof EntityPlayerMP && !(player instanceof FakePlayer)
            && player.capabilities.isCreativeMode
            && PermissionAPI.hasPermission(player, CointSUPermissions.SPAWNER_PLACE);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPlace(BlockEvent.PlaceEvent event) {
        if (event.block != Blocks.mob_spawner || event.world.isRemote || canHandleSpawners(event.player)) {
            return;
        }
        event.setCanceled(true);
        if (event.player != null && !(event.player instanceof FakePlayer)) {
            event.player.addChatMessage(new ChatComponentText(DENY_MESSAGE));
        }
    }
}
