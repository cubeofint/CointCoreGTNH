package coint.network;

import net.minecraft.command.CommandException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;

import coint.integration.personalspace.PersonalSpaceBinding;
import coint.player.PDimDeleteRequests;
import coint.supply.SupplyServerTasks;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import me.eigenraven.personalspace.block.PortalTileEntity;

/**
 * Client to server: the delete button of a personal space portal, confirmed. The server works out from the portal
 * itself which dimension that is: the one it stands in when that is a personal space, else the one it leads to.
 */
public class PacketPDimDelete implements IMessage {

    private static final double MAX_DISTANCE_SQ = 10 * 10;

    private int x, y, z;

    public PacketPDimDelete() {}

    public PacketPDimDelete(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
    }

    public static class Handler implements IMessageHandler<PacketPDimDelete, IMessage> {

        @Override
        public IMessage onMessage(PacketPDimDelete message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler() == null ? null : ctx.getServerHandler().playerEntity;
            if (player == null) {
                return null;
            }
            SupplyServerTasks.run(() -> handle(player, message));
            return null;
        }

        private static void handle(EntityPlayerMP player, PacketPDimDelete message) {
            if (player.getDistanceSq(message.x + 0.5, message.y + 0.5, message.z + 0.5) > MAX_DISTANCE_SQ) {
                return;
            }
            TileEntity tile = player.worldObj.getTileEntity(message.x, message.y, message.z);
            if (!(tile instanceof PortalTileEntity portal)) {
                return;
            }
            int here = player.worldObj.provider.dimensionId;
            int dimId = PersonalSpaceBinding.isPersonalDimension(here) ? here
                : PersonalSpaceBinding.getTargetDimension(portal);
            try {
                PDimDeleteRequests.requestFromPortal(player, dimId);
            } catch (CommandException e) {
                player.addChatMessage(new ChatComponentText("§c" + e.getMessage()));
            }
        }
    }
}
