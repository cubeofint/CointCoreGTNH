package coint.supply;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ICrafting;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import coint.network.PacketSupplyState;
import coint.network.WorldTravelNetwork;

/**
 * The window of a remote buffer: a row of filters (click an item on to name it, an empty hand to clear), under each
 * the stock it holds, then the send slots and the player's inventory. The stock counts and the link state come in
 * {@link PacketSupplyState}; the wheel over a filter changes its target ({@link #enchantItem}).
 */
public class ContainerSupplyBuffer extends Container {

    public static final int FILTER_SLOTS = 0, STOCK_SLOTS = 9, SEND_SLOTS = 18, PLAYER_SLOTS = 36;
    private static final int STATE_EVERY = 10;

    final TileSupplyBuffer tile;
    /** Client side: what the server last said. */
    public final long[] stock = new long[TileSupplyBuffer.FILTERS];
    public final long[] target = new long[TileSupplyBuffer.FILTERS];
    public final long[] threshold = new long[TileSupplyBuffer.FILTERS];
    public boolean online;
    public int pending;
    public String error = "";
    private int ticks;

    public ContainerSupplyBuffer(InventoryPlayer playerInventory, TileSupplyBuffer tile) {
        this.tile = tile;
        IInventory filters = new FilterView(tile);
        for (int i = 0; i < TileSupplyBuffer.FILTERS; i++) {
            addSlotToContainer(new GhostSlot(filters, i, 8 + i * 18, 18));
        }
        for (int i = 0; i < TileSupplyBuffer.FILTERS; i++) {
            addSlotToContainer(new StockSlot(tile, TileSupplyBuffer.SLOTS + i, 8 + i * 18, 36));
        }
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 9; col++) {
                addSlotToContainer(new Slot(tile, row * 9 + col, 8 + col * 18, 72 + row * 18));
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlotToContainer(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 121 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlotToContainer(new Slot(playerInventory, col, 8 + col * 18, 179));
        }
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return tile.isUseableByPlayer(player);
    }

    @Override
    public ItemStack slotClick(int slotId, int button, int mode, EntityPlayer player) {
        if (slotId >= FILTER_SLOTS && slotId < STOCK_SLOTS) {
            // An item names the filter; an empty hand selects it (in the window), or clears it with the right button.
            ItemStack held = player.inventory.getItemStack();
            if (!player.worldObj.isRemote && tile.canEdit(player) && (held != null || button == 1)) {
                tile.setFilter(slotId, held, false);
            }
            return null;
        }
        return super.slotClick(slotId, button, mode, player);
    }

    /** An item dropped from NEI, or a container set with Ctrl (then its fluid). */
    public void setFilter(EntityPlayer player, int filter, ItemStack item, boolean asFluid) {
        if (filter >= 0 && filter < TileSupplyBuffer.FILTERS && tile.canEdit(player)) {
            tile.setFilter(filter, item, asFluid);
        }
    }

    /** Typed in the window: how much a filter keeps, and at what stock it orders more. */
    public void setAmounts(EntityPlayer player, int filter, long newTarget, long newThreshold) {
        if (filter >= 0 && filter < TileSupplyBuffer.FILTERS && tile.canEdit(player)) {
            tile.setAmounts(filter, newTarget, newThreshold);
        }
    }

    /** The wheel over a filter: id = filter * 2 + (1 up, 0 down). */
    @Override
    public boolean enchantItem(EntityPlayer player, int id) {
        int filter = id / 2;
        if (filter >= 0 && filter < TileSupplyBuffer.FILTERS && tile.canEdit(player)) {
            tile.cycleTarget(filter, id % 2 == 1);
        }
        return true;
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        Slot slot = (Slot) inventorySlots.get(index);
        if (slot == null || !slot.getHasStack() || index < STOCK_SLOTS) {
            return null;
        }
        ItemStack stack = slot.getStack();
        ItemStack copy = stack.copy();
        if (index < PLAYER_SLOTS) {
            // From the stock or the send slots into the player's inventory.
            if (!mergeItemStack(stack, PLAYER_SLOTS, inventorySlots.size(), true)) {
                return null;
            }
            if (index < SEND_SLOTS) {
                // A stock slot shows a copy: take what went over. Saying what moved makes the server send the window
                // again, the client having had nothing there to move.
                int moved = copy.stackSize - stack.stackSize;
                if (moved <= 0) {
                    return null;
                }
                tile.decrStackSize(TileSupplyBuffer.SLOTS + index - STOCK_SLOTS, moved);
                copy.stackSize = moved;
                return copy;
            }
        } else if (!mergeItemStack(stack, SEND_SLOTS, PLAYER_SLOTS, false)) {
            return null;
        }
        if (stack.stackSize == 0) {
            slot.putStack(null);
        } else {
            slot.onSlotChanged();
        }
        return copy;
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        if (++ticks % STATE_EVERY != 1) {
            return;
        }
        long[] stocks = new long[TileSupplyBuffer.FILTERS], targets = new long[TileSupplyBuffer.FILTERS],
            thresholds = new long[TileSupplyBuffer.FILTERS];
        for (int i = 0; i < TileSupplyBuffer.FILTERS; i++) {
            stocks[i] = tile.getStock(i);
            targets[i] = tile.getTarget(i);
            thresholds[i] = tile.getThreshold(i);
        }
        PacketSupplyState state = new PacketSupplyState(
            windowId,
            tile.isLinkOnline(),
            tile.pendingCount(),
            tile.lastError(),
            stocks,
            targets,
            thresholds);
        for (Object crafter : crafters) {
            if (crafter instanceof EntityPlayerMP player) {
                WorldTravelNetwork.CHANNEL.sendTo(state, player);
            }
        }
    }

    @Override
    public void addCraftingToCrafters(ICrafting crafter) {
        super.addCraftingToCrafters(crafter);
        ticks = 0;
    }

    /** The filters as an inventory the slots can show. */
    private static final class FilterView extends InventoryBasic {

        private final TileSupplyBuffer tile;

        FilterView(TileSupplyBuffer tile) {
            super("filters", false, TileSupplyBuffer.FILTERS);
            this.tile = tile;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return tile.getFilter(slot);
        }

        @Override
        public void setInventorySlotContents(int slot, ItemStack stack) {
            // The client gets the filters this way when the window syncs.
            if (tile.getWorldObj() != null && tile.getWorldObj().isRemote) {
                tile.setFilterClient(slot, stack);
            }
        }
    }

    private static final class GhostSlot extends Slot {

        GhostSlot(IInventory inventory, int index, int x, int y) {
            super(inventory, index, x, y);
        }

        @Override
        public boolean isItemValid(ItemStack stack) {
            return false;
        }

        @Override
        public boolean canTakeStack(EntityPlayer player) {
            return false;
        }
    }

    private static final class StockSlot extends Slot {

        StockSlot(IInventory inventory, int index, int x, int y) {
            super(inventory, index, x, y);
        }

        /** Nothing for the client to draw: the window draws the stock itself, with its real count. */
        @Override
        public ItemStack getStack() {
            if (inventory instanceof TileSupplyBuffer tile && tile.getWorldObj() != null
                && tile.getWorldObj().isRemote) {
                return null;
            }
            return super.getStack();
        }

        @Override
        public boolean isItemValid(ItemStack stack) {
            return false;
        }
    }
}
