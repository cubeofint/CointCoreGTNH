package coint.client;

import coint.network.PacketOpenWorlds;

public final class ClientWorldTravelState {

    private static volatile PacketOpenWorlds pending;

    private ClientWorldTravelState() {}

    public static void queue(PacketOpenWorlds packet) {
        pending = packet;
    }

    static PacketOpenWorlds take() {
        PacketOpenWorlds packet = pending;
        pending = null;
        return packet;
    }
}
