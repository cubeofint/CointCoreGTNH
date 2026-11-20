package coint.cards;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Ordinary 52-card deck, independent of UNO's colour/action card encoding. */
public final class PlayingCards {

    private PlayingCards() {}

    public static int rank(int card) {
        return card % 13 + 1;
    }

    public static int suit(int card) {
        return card / 13;
    }

    public static boolean red(int card) {
        return suit(card) < 2;
    }

    public static String label(int card) {
        int rank = rank(card);
        return switch (rank) {
            case 1 -> "A";
            case 11 -> "J";
            case 12 -> "Q";
            case 13 -> "K";
            default -> Integer.toString(rank);
        };
    }

    public static List<Integer> deck(Random random) {
        List<Integer> cards = new ArrayList<>();
        for (int i = 0; i < 52; i++) cards.add(i);
        Collections.shuffle(cards, random);
        return cards;
    }
}
