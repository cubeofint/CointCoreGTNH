package coint.client;

import net.minecraft.client.Minecraft;

import coint.supply.ContainerSupplyBuffer;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Puts what the server says about an open supply buffer window into that window. */
@SideOnly(Side.CLIENT)
public final class ClientSupplyState {

    private ClientSupplyState() {}

    public static void apply(int windowId, boolean online, int pending, String error, long[] stock, long[] target,
        long[] threshold) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null && mc.thePlayer.openContainer instanceof ContainerSupplyBuffer container
            && container.windowId == windowId) {
            int count = Math.min(stock.length, container.stock.length);
            System.arraycopy(stock, 0, container.stock, 0, count);
            System.arraycopy(target, 0, container.target, 0, count);
            System.arraycopy(threshold, 0, container.threshold, 0, count);
            container.online = online;
            container.pending = pending;
            container.error = error;
        }
    }
}
