package coint.cluster;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * The remote ME window: only the player's own inventory is made of slots; the network's items are drawn by the client
 * from what the server sends. Shift-click puts a stack into the remote network.
 */
public class ContainerRemoteMe extends Container implements RemoteMeWindow {

    public static final int INVENTORY_X = 8, INVENTORY_Y = 139;

    /** Null on the client. */
    private final RemoteMe.View view;

    public ContainerRemoteMe(InventoryPlayer inventory, RemoteMe.View view) {
        this.view = view;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlotToContainer(
                    new Slot(inventory, col + row * 9 + 9, INVENTORY_X + col * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlotToContainer(new Slot(inventory, col, INVENTORY_X + col * 18, INVENTORY_Y + 58));
        }
    }

    RemoteMe.View view() {
        return view;
    }

    @Override
    public RemoteMe.View remoteView() {
        return view;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return true;
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        if (view == null || !(player instanceof EntityPlayerMP mp) || index < 0 || index >= inventorySlots.size()) {
            return null;
        }
        Slot slot = (Slot) inventorySlots.get(index);
        ItemStack stack = slot.getStack();
        if (stack != null && RemoteMe.insertStack(mp, view, stack)) {
            slot.putStack(null);
            slot.onSlotChanged();
        }
        return null;
    }

    @Override
    public void onContainerClosed(EntityPlayer player) {
        super.onContainerClosed(player);
        if (view != null) {
            RemoteMe.closed(view);
        }
    }
}
