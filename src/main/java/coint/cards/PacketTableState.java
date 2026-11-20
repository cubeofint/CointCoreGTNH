package coint.cards;

import java.util.ArrayList;
import java.util.List;

import coint.client.ClientTableGames;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Per-viewer snapshot. Covered solitaire cards and opponents' poker cards are never encoded. */
public class PacketTableState implements IMessage {

    public static final int SOLITAIRE = 1, POKER = 2, MENU = 3;
    public int game = MENU, dim, x, y, z, revision, phase, you = -1, turn = -1, dealer = -1;
    public long session;
    public int pot, call, minRaise, maxRaise, msLeft, moves;
    public boolean open, closed, owner, canUndo, won, canRaise;
    public String error = "", result = "";
    public final List<String> names = new ArrayList<>();
    public final List<Integer> chips = new ArrayList<>(), wagers = new ArrayList<>();
    public final List<Boolean> folded = new ArrayList<>(), bots = new ArrayList<>();
    public final List<int[]> hands = new ArrayList<>(), piles = new ArrayList<>();
    public int[] board = new int[0];

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(game)
            .writeInt(dim)
            .writeInt(x)
            .writeInt(y)
            .writeInt(z)
            .writeInt(revision)
            .writeLong(session)
            .writeByte(phase)
            .writeByte(you)
            .writeByte(turn)
            .writeByte(dealer);
        buf.writeInt(pot)
            .writeInt(call)
            .writeInt(minRaise)
            .writeInt(maxRaise)
            .writeInt(msLeft)
            .writeInt(moves);
        buf.writeBoolean(open)
            .writeBoolean(closed)
            .writeBoolean(owner)
            .writeBoolean(canUndo)
            .writeBoolean(won)
            .writeBoolean(canRaise);
        ByteBufUtils.writeUTF8String(buf, error);
        ByteBufUtils.writeUTF8String(buf, result);
        buf.writeByte(names.size());
        for (int i = 0; i < names.size(); i++) {
            ByteBufUtils.writeUTF8String(buf, names.get(i));
            buf.writeInt(chips.get(i))
                .writeInt(wagers.get(i))
                .writeBoolean(folded.get(i))
                .writeBoolean(bots.get(i));
            cards(buf, hands.get(i));
        }
        cards(buf, board);
        buf.writeByte(piles.size());
        for (int[] pile : piles) cards(buf, pile);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        game = buf.readUnsignedByte();
        dim = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        revision = buf.readInt();
        session = buf.readLong();
        phase = buf.readUnsignedByte();
        you = buf.readByte();
        turn = buf.readByte();
        dealer = buf.readByte();
        pot = buf.readInt();
        call = buf.readInt();
        minRaise = buf.readInt();
        maxRaise = buf.readInt();
        msLeft = buf.readInt();
        moves = buf.readInt();
        open = buf.readBoolean();
        closed = buf.readBoolean();
        owner = buf.readBoolean();
        canUndo = buf.readBoolean();
        won = buf.readBoolean();
        canRaise = buf.readBoolean();
        error = ByteBufUtils.readUTF8String(buf);
        result = ByteBufUtils.readUTF8String(buf);
        int count = buf.readUnsignedByte();
        if (count > PokerGame.MAX_PLAYERS) throw new IllegalArgumentException("Too many seats");
        for (int i = 0; i < count; i++) {
            names.add(ByteBufUtils.readUTF8String(buf));
            chips.add(buf.readInt());
            wagers.add(buf.readInt());
            folded.add(buf.readBoolean());
            bots.add(buf.readBoolean());
            hands.add(cards(buf));
        }
        board = cards(buf);
        count = buf.readUnsignedByte();
        if (count > SolitaireGame.PILES) throw new IllegalArgumentException("Too many piles");
        for (int i = 0; i < count; i++) piles.add(cards(buf));
    }

    private static void cards(ByteBuf buf, int[] cards) {
        buf.writeByte(cards.length);
        for (int card : cards) buf.writeByte(card);
    }

    private static int[] cards(ByteBuf buf) {
        int length = buf.readUnsignedByte();
        if (length > 52) throw new IllegalArgumentException("Too many cards");
        int[] cards = new int[length];
        for (int i = 0; i < length; i++) {
            cards[i] = buf.readByte();
            if (cards[i] < -1 || cards[i] > 51) throw new IllegalArgumentException("Invalid card");
        }
        return cards;
    }

    public static final class Handler implements IMessageHandler<PacketTableState, IMessage> {

        @Override
        public IMessage onMessage(PacketTableState message, MessageContext context) {
            ClientTableGames.receive(message);
            return null;
        }
    }
}
