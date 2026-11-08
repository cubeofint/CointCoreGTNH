package coint.cards;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;

/**
 * What lies on a card table for everyone around to see: the top card and how many cards each seat holds. Kept in
 * memory only; the game itself is in {@link CardTables}.
 */
public class TileCardTable extends TileEntity {

    /** -1 when no round is played. */
    public int top = -1;
    public int[] counts = new int[0];

    @Override
    public boolean canUpdate() {
        return false;
    }

    void show(int top, int[] counts) {
        if (this.top == top && java.util.Arrays.equals(this.counts, counts)) {
            return;
        }
        this.top = top;
        this.counts = counts;
        if (worldObj != null) {
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        }
    }

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setShort("top", (short) top);
        tag.setIntArray("counts", counts);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        NBTTagCompound tag = pkt.func_148857_g();
        top = tag.getShort("top");
        counts = tag.getIntArray("counts");
    }
}
