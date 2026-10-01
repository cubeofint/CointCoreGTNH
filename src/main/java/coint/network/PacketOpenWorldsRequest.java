package coint.network;

import java.util.UUID;

import coint.worldtravel.WorldTravelServerEvents;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class PacketOpenWorldsRequest implements IMessage {

    @Override
    public void fromBytes(ByteBuf buf) {}

    @Override
    public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<PacketOpenWorldsRequest, IMessage> {

        @Override
        public IMessage onMessage(PacketOpenWorldsRequest message, MessageContext ctx) {
            if (ctx.getServerHandler() == null || ctx.getServerHandler().playerEntity == null) {
                return null;
            }
            UUID playerId = ctx.getServerHandler().playerEntity.getUniqueID();
            WorldTravelServerEvents.enqueueOpen(playerId);
            return null;
        }
    }
}
