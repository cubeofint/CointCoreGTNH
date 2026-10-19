package coint.events;

import net.minecraft.block.Block;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * AE2 security terminals no longer secure networks (see the mixin on AE2's security cache): who opens one is told so,
 * and how wireless terminals are linked now.
 */
@EventBusSubscriber
public final class SecurityTerminalNotice {

    private static final String SECURITY_BLOCK = "appeng.block.misc.BlockSecurity";

    private SecurityTerminalNotice() {}

    @SubscribeEvent
    public static void onPlayerInteract(PlayerInteractEvent event) {
        if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK || event.world.isRemote) {
            return;
        }
        Block block = event.world.getBlock(event.x, event.y, event.z);
        if (block != null && SECURITY_BLOCK.equals(
            block.getClass()
                .getName())) {
            event.entityPlayer.addChatMessage(
                new ChatComponentText(
                    "§eТерминал безопасности отключён: он больше не защищает сеть. §fБеспроводной терминал "
                        + "привязывается ПКМ по беспроводной точке доступа с ним в руке."));
        }
    }
}
