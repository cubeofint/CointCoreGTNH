package coint.cards;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/** Klondike, draw one, unlimited stock redeals. Only the server owns the covered cards. */
final class SolitaireGame {

    static final int STOCK = 0, WASTE = 1, FOUNDATION = 2, TABLEAU = 6, PILES = 13;
    final List<List<Integer>> piles = new ArrayList<>();
    final int[] hidden = new int[7];
    private final Deque<Snapshot> undo = new ArrayDeque<>();
    int revision, moves;
    boolean won;

    private static final class Snapshot {

        final List<List<Integer>> piles = new ArrayList<>();
        final int[] hidden;
        final int moves;

        Snapshot(SolitaireGame game) {
            for (List<Integer> pile : game.piles) piles.add(new ArrayList<>(pile));
            hidden = game.hidden.clone();
            moves = game.moves;
        }
    }

    SolitaireGame() {
        for (int i = 0; i < PILES; i++) piles.add(new ArrayList<>());
        deal();
    }

    void deal() {
        for (List<Integer> pile : piles) pile.clear();
        List<Integer> deck = PlayingCards.deck(new Random());
        for (int col = 0; col < 7; col++) {
            for (int row = 0; row <= col; row++) piles.get(TABLEAU + col)
                .add(deck.remove(deck.size() - 1));
            hidden[col] = col;
        }
        piles.get(STOCK)
            .addAll(deck);
        undo.clear();
        won = false;
        moves = 0;
        revision++;
    }

    private void save() {
        if (undo.size() == 32) undo.removeFirst();
        undo.addLast(new Snapshot(this));
    }

    boolean canUndo() {
        return !undo.isEmpty();
    }

    String draw(int expected) {
        if (expected != revision) return "cointcore.table.error.stale";
        if (won || piles.get(STOCK)
            .isEmpty()
            && piles.get(WASTE)
                .isEmpty())
            return "cointcore.table.error.move";
        save();
        if (piles.get(STOCK)
            .isEmpty()) {
            List<Integer> waste = piles.get(WASTE);
            while (!waste.isEmpty()) piles.get(STOCK)
                .add(waste.remove(waste.size() - 1));
        } else {
            List<Integer> stock = piles.get(STOCK);
            piles.get(WASTE)
                .add(stock.remove(stock.size() - 1));
        }
        moves++;
        revision++;
        return null;
    }

    String undo(int expected) {
        if (expected != revision) return "cointcore.table.error.stale";
        if (undo.isEmpty()) return "cointcore.table.error.move";
        Snapshot before = undo.removeLast();
        for (int i = 0; i < PILES; i++) {
            piles.get(i)
                .clear();
            piles.get(i)
                .addAll(before.piles.get(i));
        }
        System.arraycopy(before.hidden, 0, hidden, 0, hidden.length);
        moves = before.moves;
        won = false;
        revision++;
        return null;
    }

    String move(int from, int to, int count, int expected) {
        if (expected != revision) return "cointcore.table.error.stale";
        if (won || from < WASTE || from >= PILES || to < FOUNDATION || to >= PILES || from == to || count < 1)
            return "cointcore.table.error.move";
        List<Integer> source = piles.get(from), target = piles.get(to);
        if (count > source.size() || from < TABLEAU && count != 1) return "cointcore.table.error.move";
        int start = source.size() - count;
        if (from >= TABLEAU && start < hidden[from - TABLEAU]) return "cointcore.table.error.move";
        for (int i = start + 1; i < source.size(); i++) {
            if (!descending(source.get(i - 1), source.get(i))) return "cointcore.table.error.move";
        }
        int card = source.get(start);
        if (to < TABLEAU) {
            if (count != 1 || (target.isEmpty() ? PlayingCards.rank(card) != 1
                : PlayingCards.suit(card) != PlayingCards.suit(target.get(target.size() - 1))
                    || PlayingCards.rank(card) != PlayingCards.rank(target.get(target.size() - 1)) + 1))
                return "cointcore.table.error.move";
        } else if (target.isEmpty() ? PlayingCards.rank(card) != 13 : !descending(target.get(target.size() - 1), card))
            return "cointcore.table.error.move";
        save();
        target.addAll(new ArrayList<>(source.subList(start, source.size())));
        source.subList(start, source.size())
            .clear();
        if (from >= TABLEAU && !source.isEmpty() && hidden[from - TABLEAU] == source.size()) hidden[from - TABLEAU]--;
        won = true;
        for (int f = FOUNDATION; f < TABLEAU; f++) won &= piles.get(f)
            .size() == 13;
        moves++;
        revision++;
        return null;
    }

    private static boolean descending(int above, int below) {
        return PlayingCards.red(above) != PlayingCards.red(below)
            && PlayingCards.rank(above) == PlayingCards.rank(below) + 1;
    }

    List<int[]> visiblePiles() {
        List<int[]> result = new ArrayList<>();
        for (int i = 0; i < PILES; i++) {
            int[] cards = new int[piles.get(i)
                .size()];
            for (int j = 0; j < cards.length; j++) cards[j] = i == STOCK || i >= TABLEAU && j < hidden[i - TABLEAU] ? -1
                : piles.get(i)
                    .get(j);
            result.add(cards);
        }
        return result;
    }
}
