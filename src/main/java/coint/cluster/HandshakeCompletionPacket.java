package coint.cluster;

import net.minecraft.network.INetHandler;

import cpw.mods.fml.common.network.handshake.NetworkDispatcher;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.Unpooled;

/**
 * Local callback queued by Forge, never sent over the wire. Owns its connection instead of a shared channel attribute.
 */
public final class HandshakeCompletionPacket extends FMLProxyPacket {

    private final NetworkDispatcher connection;

    public HandshakeCompletionPacket(NetworkDispatcher connection) {
        super(Unpooled.wrappedBuffer(new byte[] { 0, (byte) Side.SERVER.ordinal() }), "FML");
        this.connection = connection;
        setDispatcher(connection);
        setTarget(Side.SERVER);
    }

    @Override
    public void processPacket(INetHandler handler) {
        // NetworkManager drains this on the server thread, after the existing cluster session check.
        connection.completeHandshake(Side.SERVER);
    }
}
