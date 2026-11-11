package coint.commands.kit;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.stats.StatList;
import net.minecraft.util.ChatComponentText;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import coint.CointCore;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import serverutils.lib.util.NBTUtils;

/**
 * The starter kit ({@code general.starterKit}): given once to a player on his first join here. Players who played here
 * before it was set up are only marked, not given it.
 */
@EventBusSubscriber
public final class StarterKit {

    private static final String TAG = "cointcore_starter_kit";
    /** Played here longer than this: not new. */
    private static final int NEW_PLAYER_TICKS = 20 * 60 * 5;

    private StarterKit() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        String name = CointConfig.general.starterKit;
        if (name == null || name.isEmpty() || !(event.player instanceof EntityPlayerMP player)) {
            return;
        }
        NBTTagCompound data = NBTUtils.getPersistedData(player, true);
        if (data.getBoolean(TAG)) {
            return;
        }
        data.setBoolean(TAG, true);
        int played = player.func_147099_x()
            .writeStat(StatList.minutesPlayedStat);
        if (played > NEW_PLAYER_TICKS) {
            return;
        }
        KitDefinition kit = KitManager.getKit(MinecraftServer.getServer(), name);
        if (kit == null) {
            CointCore.LOG.warn("[StarterKit] Kit '{}' does not exist (general.starterKit)", name);
            return;
        }
        for (ItemStack stack : kit.getItems()) {
            ItemStack copy = stack.copy();
            if (!player.inventory.addItemStackToInventory(copy) && copy.stackSize > 0) {
                player.dropPlayerItemWithRandomChoice(copy, false);
            }
        }
        player.inventoryContainer.detectAndSendChanges();
        player.addChatMessage(new ChatComponentText("§aДобро пожаловать! Вы получили стартовый набор."));
        CointCore.LOG.info("[StarterKit] {} got the starter kit '{}'", player.getCommandSenderName(), name);
    }
}
