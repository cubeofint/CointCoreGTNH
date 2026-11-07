package coint.cluster;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

import appeng.api.storage.data.IAEItemStack;
import appeng.container.implementations.ContainerMEMonitorable;
import appeng.helpers.MonitorableAction;

/**
 * AE2's own ME terminal window onto a network on another server. The client opens AE2's window as for any wireless
 * terminal; here its clicks become operations in the cluster database instead of moves in a local network: taken items
 * arrive in the inventory a moment later, never on the cursor, and items put in leave the player at once.
 */
public class ContainerRemoteMeNative extends ContainerMEMonitorable implements RemoteMeWindow {

    private final RemoteMe.View view;

    ContainerRemoteMeNative(InventoryPlayer inventory, RemoteTerminalObject host, RemoteMe.View view) {
        super(inventory, host);
        this.view = view;
    }

    @Override
    public RemoteMe.View remoteView() {
        return view;
    }

    @Override
    public void doMonitorableAction(MonitorableAction action, int custom, EntityPlayerMP player) {
        IAEItemStack target = getTargetStack() instanceof IAEItemStack ais && ais.getStackSize() > 0 ? ais : null;
        ItemStack hand = player.inventory.getItemStack();
        switch (action) {
            case SET_PIN, UNSET_PIN -> super.doMonitorableAction(action, custom, player);
            case PICKUP_OR_SET_DOWN -> {
                if (hand != null) {
                    RemoteMe.insertCursor(player, view, false);
                } else {
                    take(player, target, Integer.MAX_VALUE);
                }
            }
            case SPLIT_OR_PLACE_SINGLE -> {
                if (hand != null) {
                    RemoteMe.insertCursor(player, view, true);
                } else if (target != null) {
                    ItemStack kind = target.getItemStack();
                    take(player, target, (kind.getMaxStackSize() + 1) / 2);
                }
            }
            case SHIFT_CLICK -> take(player, target, Integer.MAX_VALUE);
            case PICKUP_SINGLE, ROLL_UP -> take(player, target, 1);
            case ROLL_DOWN -> {
                if (hand != null) {
                    RemoteMe.insertCursor(player, view, true);
                }
            }
            default -> {}
        }
    }

    private void take(EntityPlayerMP player, IAEItemStack target, int amount) {
        if (target == null) {
            return;
        }
        ItemStack kind = target.getItemStack();
        kind.stackSize = 1;
        RemoteMe.extract(player, view, kind, (int) Math.min(amount, target.getStackSize()));
    }

    @Override
    protected ItemStack shiftStoreItem(ItemStack input) {
        EntityPlayer player = getInventoryPlayer().player;
        return player instanceof EntityPlayerMP mp && RemoteMe.insertStack(mp, view, input) ? null : input;
    }

    @Override
    public void onContainerClosed(EntityPlayer player) {
        super.onContainerClosed(player);
        RemoteMe.closed(view);
    }
}
