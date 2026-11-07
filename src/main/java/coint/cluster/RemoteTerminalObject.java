package coint.cluster;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.features.IWirelessTermHandler;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStackType;
import appeng.helpers.WirelessTerminalGuiObject;
import appeng.util.item.AEItemStackType;

/**
 * The wireless terminal as AE2's terminal window sees it, with the network on another server: its items come from a
 * {@link RemoteItemMonitor}, there is no local grid, and the access point is never out of range. Power, settings, view
 * cells and pins stay those of the terminal item.
 */
final class RemoteTerminalObject extends WirelessTerminalGuiObject {

    /** The drain rate is figured from the range; a remote network costs as much as one 16 blocks away. */
    private static final double RANGE = 16;

    private final RemoteItemMonitor monitor;

    RemoteTerminalObject(IWirelessTermHandler handler, ItemStack terminal, EntityPlayer player, World world, int slot,
        RemoteItemMonitor monitor) {
        super(handler, terminal, player, world, slot, 0, 0);
        this.monitor = monitor;
    }

    @Override
    public IMEMonitor<IAEItemStack> getItemInventory() {
        return monitor;
    }

    @Override
    public IMEMonitor<IAEFluidStack> getFluidInventory() {
        return null;
    }

    @Override
    public IMEMonitor<?> getMEMonitor(IAEStackType<?> type) {
        return type == AEItemStackType.ITEM_STACK_TYPE ? monitor : null;
    }

    @Override
    public boolean rangeCheck() {
        return true;
    }

    @Override
    public double getRange() {
        return RANGE;
    }

    @Override
    public IGridNode getActionableNode() {
        return null;
    }

    @Override
    public IGridNode getGridNode(ForgeDirection dir) {
        return null;
    }

    @Override
    public IGrid getGrid() {
        return null;
    }
}
