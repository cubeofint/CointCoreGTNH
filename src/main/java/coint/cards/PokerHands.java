package coint.cards;

import java.util.List;

/** Compare the best five cards out of seven; higher packed rank wins, suits never break a tie. */
final class PokerHands {

    private PokerHands() {}

    static long best(List<Integer> cards) {
        if (cards.size() < 5 || cards.size() > 7) throw new IllegalArgumentException("Expected five to seven cards");
        long best = 0;
        for (int a = 0; a < cards.size() - 4; a++)
            for (int b = a + 1; b < cards.size() - 3; b++) for (int c = b + 1; c < cards.size() - 2; c++)
                for (int d = c + 1; d < cards.size() - 1; d++) for (int e = d + 1; e < cards.size(); e++)
                    best = Math.max(best, five(cards.get(a), cards.get(b), cards.get(c), cards.get(d), cards.get(e)));
        return best;
    }

    static long five(int... cards) {
        int[] counts = new int[15];
        boolean flush = true;
        for (int card : cards) {
            int rank = PlayingCards.rank(card);
            counts[rank == 1 ? 14 : rank]++;
            flush &= PlayingCards.suit(card) == PlayingCards.suit(cards[0]);
        }
        int straight = 0;
        for (int high = 14; high >= 5; high--) {
            boolean run = true;
            for (int i = 0; i < 5; i++) run &= counts[high - i == 1 ? 14 : high - i] > 0;
            if (run) {
                straight = high;
                break;
            }
        }
        if (flush && straight > 0) return pack(8, straight);
        int four = 0, three = 0;
        java.util.List<Integer> pairs = new java.util.ArrayList<>(), singles = new java.util.ArrayList<>();
        for (int rank = 14; rank >= 2; rank--) {
            if (counts[rank] == 4) four = rank;
            if (counts[rank] == 3) three = rank;
            if (counts[rank] == 2) pairs.add(rank);
            if (counts[rank] == 1) singles.add(rank);
        }
        if (four > 0) return pack(7, four, singles.get(0));
        if (three > 0 && !pairs.isEmpty()) return pack(6, three, pairs.get(0));
        if (flush) return packList(5, singles);
        if (straight > 0) return pack(4, straight);
        if (three > 0) return pack(3, three, singles.get(0), singles.get(1));
        if (pairs.size() == 2) return pack(2, pairs.get(0), pairs.get(1), singles.get(0));
        if (pairs.size() == 1) return pack(1, pairs.get(0), singles.get(0), singles.get(1), singles.get(2));
        return packList(0, singles);
    }

    private static long packList(int kind, java.util.List<Integer> ranks) {
        int[] values = new int[ranks.size()];
        for (int i = 0; i < values.length; i++) values[i] = ranks.get(i);
        return pack(kind, values);
    }

    private static long pack(int kind, int... values) {
        long result = kind;
        for (int i = 0; i < 5; i++) result = result * 15 + (i < values.length ? values[i] : 0);
        return result;
    }
}
