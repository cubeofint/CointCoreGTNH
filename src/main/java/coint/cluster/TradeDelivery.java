package coint.cluster;

/** Items waiting for a player, given on whichever server of the network he is on. */
public final class TradeDelivery {

    public long id;
    public final String uuid, name;
    public final long tradeId;
    public final byte[] payload;
    public final String note;

    public TradeDelivery(String uuid, String name, long tradeId, byte[] payload, String note) {
        this.uuid = uuid;
        this.name = name;
        this.tradeId = tradeId;
        this.payload = payload;
        this.note = note;
    }
}
