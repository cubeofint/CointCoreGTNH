package coint.cluster;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;

import appeng.api.config.SecurityPermissions;
import appeng.container.implementations.ContainerCraftAmount;

/**
 * AE2's "how many to craft" window for an order to a network on another server. Its "Next" is caught by the mixin on
 * AE2's craft request packet, which asks the remote network for a plan instead of planning on a local one.
 */
public class ContainerRemoteCraftAmount extends ContainerCraftAmount implements RemoteMeWindow {

    private final RemoteMe.View view;

    ContainerRemoteCraftAmount(InventoryPlayer inventory, RemoteTerminalObject host, RemoteMe.View view) {
        super(inventory, host);
        this.view = view;
    }

    @Override
    public RemoteMe.View remoteView() {
        return view;
    }

    /** There is no local network to check rights on; the remote one checks them. */
    @Override
    public void verifyPermissions(SecurityPermissions security, boolean requirePower) {}

    @Override
    public void onContainerClosed(EntityPlayer player) {
        super.onContainerClosed(player);
        RemoteMe.closed(view);
    }
}
