package coint.network;

import coint.CointConfig;
import coint.CointCore;
import cpw.mods.fml.relauncher.Side;

/** Compatibility of CointCore packets, independently of the Git build number. */
public final class NetworkCompatibility {

    // Bump before changing packet IDs/layouts or GUI/container messages incompatibly.
    // Builds with the same value must remain usable together on a hub in both directions.
    public static final String PROTOCOL_SUFFIX = "+cointnet.2";
    public static final String UPDATE_MESSAGE = "Перезапустите сборку через лаунчер, чтобы обновить моды.";

    private NetworkCompatibility() {}

    public static boolean accepts(String remoteVersion, Side remoteSide) {
        // Preserve Forge's previous client behaviour for servers without CointCore.
        if (remoteVersion == null) return remoteSide == Side.SERVER;
        if (CointCore.VERSION.equals(remoteVersion)) return true;
        if (remoteVersion.length() <= PROTOCOL_SUFFIX.length() || !remoteVersion.endsWith(PROTOCOL_SUFFIX))
            return false;

        // Clients cannot know a server's role from Forge's mod list. The destination checks
        // whether it is a hub; game servers still require the exact CointCore build.
        if (remoteSide == Side.SERVER) return true;
        String hub = CointConfig.cluster.hubNodeId;
        String node = CointConfig.cluster.nodeId;
        return CointConfig.cluster.enabled && hub != null
            && node != null
            && !hub.trim()
                .isEmpty()
            && hub.trim()
                .equalsIgnoreCase(node.trim());
    }
}
