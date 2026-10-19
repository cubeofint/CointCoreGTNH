package coint.cluster;

import java.util.Locale;

import net.minecraft.command.ICommand;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.EntityInteractEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import coint.CointConfig;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * What a guest may not do on a server that is not his home: pick up or throw away items, break or place blocks, use
 * blocks or entities (chests, machines, portals, villagers, frames), attack, and use commands other than
 * {@link CointConfig.Cluster#guestCommands}. Quest rewards are held back by a mixin on BetterQuesting.
 */
@EventBusSubscriber
public final class GuestGuard {

    private static final long NOTICE_EVERY_MS = 3000;
    private static final java.util.Map<java.util.UUID, Long> lastNotice = new java.util.HashMap<>();

    private GuestGuard() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPickup(EntityItemPickupEvent event) {
        if (ClusterHomes.isGuest(event.entityPlayer)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onToss(ItemTossEvent event) {
        if (!ClusterHomes.isGuest(event.player)) {
            return;
        }
        // Back into the inventory: cancelling alone would destroy it.
        EntityItem dropped = event.entityItem;
        ItemStack stack = dropped.getEntityItem();
        if (stack != null && event.player.inventory.addItemStackToInventory(stack)) {
            event.setCanceled(true);
            event.player.inventoryContainer.detectAndSendChanges();
        }
        notice(event.player, "выбрасывать предметы");
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (ClusterHomes.isGuest(event.getPlayer())) {
            event.setCanceled(true);
            notice(event.getPlayer(), "ломать блоки");
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlace(BlockEvent.PlaceEvent event) {
        if (ClusterHomes.isGuest(event.player)) {
            event.setCanceled(true);
            notice(event.player, "ставить блоки");
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteract(PlayerInteractEvent event) {
        if (ClusterHomes.isGuest(event.entityPlayer)) {
            event.setCanceled(true);
            if (event.action != PlayerInteractEvent.Action.LEFT_CLICK_BLOCK) {
                notice(event.entityPlayer, "пользоваться блоками и предметами");
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteract(EntityInteractEvent event) {
        if (ClusterHomes.isGuest(event.entityPlayer)) {
            event.setCanceled(true);
            notice(event.entityPlayer, "взаимодействовать с существами");
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttack(AttackEntityEvent event) {
        if (ClusterHomes.isGuest(event.entityPlayer)) {
            event.setCanceled(true);
            notice(event.entityPlayer, "атаковать");
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onCommand(CommandEvent event) {
        if (!(event.sender instanceof EntityPlayerMP player) || !ClusterHomes.isGuest(player)) {
            return;
        }
        if (allowed(event.command)) {
            return;
        }
        event.setCanceled(true);
        player.addChatMessage(
            new ChatComponentText("§cВ гостях эта команда недоступна. Можно: §f" + String.join(", ", allowedList())));
    }

    private static boolean allowed(ICommand command) {
        String[] list = CointConfig.cluster.guestCommands;
        if (list == null) {
            return false;
        }
        String name = command.getCommandName()
            .toLowerCase(Locale.ROOT);
        for (String allowed : list) {
            if (allowed.trim()
                .toLowerCase(Locale.ROOT)
                .equals(name)) {
                return true;
            }
        }
        // An alias of an allowed command is the same command.
        java.util.List<?> aliases = command.getCommandAliases();
        if (aliases != null) {
            for (Object alias : aliases) {
                for (String allowed : list) {
                    if (allowed.trim()
                        .equalsIgnoreCase(String.valueOf(alias))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static java.util.List<String> allowedList() {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (CointConfig.cluster.guestCommands != null) {
            for (String command : CointConfig.cluster.guestCommands) {
                result.add("/" + command.trim());
            }
        }
        return result;
    }

    /** Tells a guest why nothing happened, now and then: some of these fire every tick while a button is held. */
    static void notice(EntityPlayer player, String what) {
        if (!(player instanceof EntityPlayerMP)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastNotice.get(player.getUniqueID());
        if (last != null && now - last < NOTICE_EVERY_MS) {
            return;
        }
        lastNotice.put(player.getUniqueID(), now);
        player.addChatMessage(new ChatComponentText("§eВы в гостях: здесь нельзя " + what + "."));
    }
}
