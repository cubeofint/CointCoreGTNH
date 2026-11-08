package coint.cards;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import coint.client.ClientCards;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: a card table as one player may see it (his own hand only), or that he no longer sits at one.
 */
public class PacketCardState implements IMessage {

    public static final byte PHASE_NONE = 0, PHASE_LOBBY = 1, PHASE_PLAYING = 2;

    /** Open the table window (the player just used the table). */
    public boolean open;
    public byte phase;
    /** A translation key for what went wrong, or empty. */
    public String error = "";
    public boolean host;
    public int you = -1;
    public final List<String> names = new ArrayList<>();
    public final List<Integer> counts = new ArrayList<>();
    public final List<Boolean> bots = new ArrayList<>();
    public int turn = -1;
    public int direction = 1;
    public int top = -1;
    public int suit = -1;
    public boolean drew;
    public int msLeft;
    /** The table log's running line count. */
    public int logSeq;
    /** "Last card!" can be said now; said ahead; time left to say it after playing down to one. */
    public boolean callOpen, called;
    public int callMsLeft;
    public final List<Integer> hand = new ArrayList<>();
    public final List<Line> log = new ArrayList<>();

    /** A log line: what happened, who, and the card or count it was about. */
    public static final class Line {

        public final byte kind;
        public final String name;
        public final int card, suit;

        public Line(byte kind, String name, int card, int suit) {
            this.kind = kind;
            this.name = name;
            this.card = card;
            this.suit = suit;
        }
    }

    public PacketCardState() {}

    static PacketCardState closed(String error) {
        PacketCardState state = new PacketCardState();
        state.phase = PHASE_NONE;
        state.error = error == null ? "" : error;
        return state;
    }

    static PacketCardState of(CardGame game, UUID viewer, boolean open, String error) {
        PacketCardState state = new PacketCardState();
        state.open = open;
        state.error = error == null ? "" : error;
        state.phase = game.phase == CardGame.Phase.PLAYING ? PHASE_PLAYING : PHASE_LOBBY;
        state.host = viewer.equals(game.host);
        state.logSeq = game.logSeq;
        state.you = game.players.indexOf(viewer);
        for (UUID p : game.players) {
            state.names.add(game.names.get(p));
            List<Integer> hand = game.hands.get(p);
            state.counts.add(hand == null ? 0 : hand.size());
            state.bots.add(game.bots.contains(p));
        }
        if (game.phase == CardGame.Phase.PLAYING) {
            state.turn = game.turn;
            state.direction = game.direction;
            state.top = game.top();
            state.suit = game.suit;
            state.drew = game.drew;
            long now = System.currentTimeMillis();
            state.msLeft = (int) Math.max(0, game.turnEndsAt - now);
            List<Integer> mine = game.hands.get(viewer);
            boolean pending = viewer.equals(game.pendingCall);
            state.called = game.called.contains(viewer);
            state.callMsLeft = pending ? (int) Math.max(0, game.callDeadline - now) : 0;
            state.callOpen = pending
                || viewer.equals(game.current()) && mine != null && mine.size() == 2 && !state.called;
            List<Integer> hand = game.hands.get(viewer);
            if (hand != null) {
                state.hand.addAll(hand);
            }
        }
        for (CardGame.Line line : game.log) {
            state.log.add(new Line(line.kind, line.name, line.card, line.suit));
        }
        return state;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        open = buf.readBoolean();
        phase = buf.readByte();
        error = ByteBufUtils.readUTF8String(buf);
        host = buf.readBoolean();
        you = buf.readByte();
        int players = buf.readByte();
        for (int i = 0; i < players; i++) {
            names.add(ByteBufUtils.readUTF8String(buf));
            counts.add((int) buf.readShort());
            bots.add(buf.readBoolean());
        }
        turn = buf.readByte();
        direction = buf.readByte();
        top = buf.readShort();
        suit = buf.readByte();
        drew = buf.readBoolean();
        msLeft = buf.readInt();
        logSeq = buf.readInt();
        callOpen = buf.readBoolean();
        called = buf.readBoolean();
        callMsLeft = buf.readInt();
        int cards = buf.readShort();
        for (int i = 0; i < cards; i++) {
            hand.add((int) buf.readUnsignedByte());
        }
        int lines = buf.readByte();
        for (int i = 0; i < lines; i++) {
            log.add(new Line(buf.readByte(), ByteBufUtils.readUTF8String(buf), buf.readShort(), buf.readByte()));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(open);
        buf.writeByte(phase);
        ByteBufUtils.writeUTF8String(buf, error);
        buf.writeBoolean(host);
        buf.writeByte(you);
        buf.writeByte(names.size());
        for (int i = 0; i < names.size(); i++) {
            ByteBufUtils.writeUTF8String(buf, names.get(i));
            buf.writeShort(counts.get(i));
            buf.writeBoolean(bots.get(i));
        }
        buf.writeByte(turn);
        buf.writeByte(direction);
        buf.writeShort(top);
        buf.writeByte(suit);
        buf.writeBoolean(drew);
        buf.writeInt(msLeft);
        buf.writeInt(logSeq);
        buf.writeBoolean(callOpen);
        buf.writeBoolean(called);
        buf.writeInt(callMsLeft);
        buf.writeShort(hand.size());
        for (int card : hand) {
            buf.writeByte(card);
        }
        buf.writeByte(log.size());
        for (Line line : log) {
            buf.writeByte(line.kind);
            ByteBufUtils.writeUTF8String(buf, line.name);
            buf.writeShort(line.card);
            buf.writeByte(line.suit);
        }
    }

    public static class Handler implements IMessageHandler<PacketCardState, IMessage> {

        @Override
        public IMessage onMessage(PacketCardState m, MessageContext ctx) {
            ClientCards.receive(m);
            return null;
        }
    }
}
