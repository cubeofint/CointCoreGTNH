package coint.network;

import net.minecraft.item.ItemStack;

import coint.client.GuiRemoteCraftAmount;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Server to client: craft was asked for in AE2's window onto a remote network; the client asks for the amount. */
public class PacketRemoteMeCraftAsk implements IMessage {

    private int windowId;
    private ItemStack kind;

    public PacketRemoteMeCraftAsk() {}

    public PacketRemoteMeCraftAsk(int windowId, ItemStack kind) {
        this.windowId = windowId;
        this.kind = kind;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        windowId = buf.readInt();
        kind = ByteBufUtils.readItemStack(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(windowId);
        ByteBufUtils.writeItemStack(buf, kind);
    }

    public static class Handler implements IMessageHandler<PacketRemoteMeCraftAsk, IMessage> {

        @Override
        public IMessage onMessage(PacketRemoteMeCraftAsk m, MessageContext ctx) {
            GuiRemoteCraftAmount.ask(m.windowId, m.kind);
            return null;
        }
    }
}
