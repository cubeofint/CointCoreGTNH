package coint.network;

import coint.client.ClientRestartState;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class PacketRestartState implements IMessage {

    private boolean active;
    private int secondsRemaining;
    private String phase;

    public PacketRestartState() {}

    public PacketRestartState(boolean active, int secondsRemaining, String phase) {
        this.active = active;
        this.secondsRemaining = secondsRemaining;
        this.phase = phase == null ? "" : phase;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        active = buf.readBoolean();
        secondsRemaining = buf.readInt();
        phase = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(active);
        buf.writeInt(secondsRemaining);
        ByteBufUtils.writeUTF8String(buf, phase);
    }

    public static class Handler implements IMessageHandler<PacketRestartState, IMessage> {

        @Override
        public IMessage onMessage(PacketRestartState message, MessageContext ctx) {
            ClientRestartState.update(message.active, message.secondsRemaining, message.phase);
            return null;
        }
    }
}
