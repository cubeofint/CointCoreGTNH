package coint.network;

import java.util.UUID;

import coint.worldtravel.WorldTravelServerEvents;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class PacketTravelRequest implements IMessage {

    public String destinationId = "";

    public PacketTravelRequest() {}

    public PacketTravelRequest(String destinationId) {
        this.destinationId = destinationId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        destinationId = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, destinationId == null ? "" : destinationId);
    }

    public static class Handler implements IMessageHandler<PacketTravelRequest, IMessage> {

        @Override
        public IMessage onMessage(PacketTravelRequest message, MessageContext ctx) {
            if (ctx.getServerHandler() == null || ctx.getServerHandler().playerEntity == null) {
                return null;
            }
            UUID playerId = ctx.getServerHandler().playerEntity.getUniqueID();
            WorldTravelServerEvents.enqueueTravel(playerId, message.destinationId);
            return null;
        }
    }
}
