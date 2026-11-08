package coint.cards;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The cards of the card table game. A card is an int: its suit times 16 plus its value. Four metal suits (bronze,
 * steel, aluminium, titanium) with 0-9, skip, reverse and +2; suitless cards that pick a suit (one plain, one +4).
 */
public final class Cards {

    public static final int SUITS = 4, WILD_SUIT = 4;
    public static final int SKIP = 10, REVERSE = 11, DRAW_TWO = 12, WILD = 13, WILD_DRAW_FOUR = 14;
    /** Cards dealt to each player at the start. */
    public static final int HAND = 7;

    private Cards() {}

    public static int of(int suit, int value) {
        return suit * 16 + value;
    }

    public static int suit(int card) {
        return card >> 4;
    }

    public static int value(int card) {
        return card & 15;
    }

    public static boolean isWild(int card) {
        return suit(card) == WILD_SUIT;
    }

    /** The card may go on {@code top} while {@code suit} is the suit to follow. */
    public static boolean playable(int card, int top, int suit) {
        return isWild(card) || suit(card) == suit || !isWild(top) && value(card) == value(top);
    }

    /** A full shuffled deck: per suit one 0 and two of each other value; four of each suitless card. */
    public static List<Integer> deck(Random random) {
        List<Integer> deck = new ArrayList<>();
        for (int suit = 0; suit < SUITS; suit++) {
            deck.add(of(suit, 0));
            for (int value = 1; value <= DRAW_TWO; value++) {
                deck.add(of(suit, value));
                deck.add(of(suit, value));
            }
        }
        for (int i = 0; i < 4; i++) {
            deck.add(of(WILD_SUIT, WILD));
            deck.add(of(WILD_SUIT, WILD_DRAW_FOUR));
        }
        Collections.shuffle(deck, random);
        return deck;
    }
}
