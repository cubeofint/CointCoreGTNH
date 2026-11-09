package coint.cluster;

import java.util.List;

/**
 * A trade between players on two servers, as kept in the shared database. Each field is a pair: [0] the player who
 * asked, [1] the one who accepted. Each server writes only its own player's half, except when finishing or calling the
 * trade off.
 */
public final class TradeRow {

    public static final String OPEN = "open", COMMITTED = "committed", DONE = "done", ABORTED = "aborted";

    public long id;
    public final String[] uuid = new String[2], name = new String[2], node = new String[2];
    /** What each offers ({@code coint.trade.TradeItems}); once committed, what was taken from him. */
    public final byte[][] offer = new byte[2][];
    /** Each player's epoch priority, -1 for none: the other side checks what he may receive. */
    public final int[] epoch = { -1, -1 };
    public final boolean[] ready = new boolean[2], confirm = new boolean[2];
    /** OPEN, or COMMITTED once his items were taken and are held here. */
    public final String[] state = { OPEN, OPEN };
    public final String[] notice = { "", "" };
    /** OPEN, DONE or ABORTED. */
    public String status = OPEN;
    /** Lines, newest last. */
    public String chat = "";
    /** Grows whenever what is on the table or who is ready changes: a commit holds only for the deal it saw. */
    public long deal;
    /** Grows with every change. */
    public long version;

    public boolean open() {
        return OPEN.equals(status);
    }

    public boolean anyCommitted() {
        return COMMITTED.equals(state[0]) || COMMITTED.equals(state[1]);
    }

    /** Takes back both "ready" and both final presses. */
    public void resetDeal() {
        ready[0] = ready[1] = false;
        confirm[0] = confirm[1] = false;
        deal++;
    }

    /** Calls the trade off; what was already taken goes back to its owner. */
    public void abort(String why, List<TradeDelivery> deliveries) {
        status = ABORTED;
        notice[0] = notice[1] = why == null ? "" : why;
        for (int s = 0; s < 2; s++) {
            if (COMMITTED.equals(state[s]) && offer[s] != null) {
                deliveries.add(new TradeDelivery(uuid[s], name[s], id, offer[s], "Возврат: обмен отменён"));
            }
        }
    }

    public void say(String line, int max) {
        String all = chat == null || chat.isEmpty() ? line : chat + "\n" + line;
        String[] lines = all.split("\n");
        if (lines.length > max) {
            all = String.join("\n", java.util.Arrays.copyOfRange(lines, lines.length - max, lines.length));
        }
        chat = all;
    }
}
