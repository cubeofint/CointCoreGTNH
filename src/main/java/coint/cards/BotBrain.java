package coint.cards;

import java.util.List;
import java.util.Random;

/** How a bot picks its card: a simple player who keeps wild cards for when nothing else fits. */
final class BotBrain {

    private BotBrain() {}

    /**
     * The hand index to play, or -1 to draw (or pass after drawing).
     *
     * @param nextHand how many cards the next player holds: with few left, the bot hits him with skips and draws
     */
    static int choose(List<Integer> hand, int top, int suit, boolean drew, int nextHand) {
        int best = -1, bestScore = Integer.MIN_VALUE;
        for (int i = drew ? hand.size() - 1 : 0; i < hand.size(); i++) {
            int card = hand.get(i);
            if (!Cards.playable(card, top, suit)) {
                continue;
            }
            int score = score(card, nextHand);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private static int score(int card, int nextHand) {
        int value = Cards.value(card);
        boolean danger = nextHand <= 2;
        return switch (value) {
            case Cards.WILD_DRAW_FOUR -> danger ? 50 : -20;
            case Cards.WILD -> -10;
            case Cards.DRAW_TWO -> danger ? 40 : 15;
            case Cards.SKIP, Cards.REVERSE -> danger ? 30 : 12;
            // Get rid of the high numbers first.
            default -> value;
        };
    }

    /** The suit to name for a wild card: the one the bot holds most of (besides the card played). */
    static int suitFor(List<Integer> hand, int played, Random random) {
        if (!Cards.isWild(hand.get(played))) {
            return -1;
        }
        int[] count = new int[Cards.SUITS];
        for (int i = 0; i < hand.size(); i++) {
            int suit = Cards.suit(hand.get(i));
            if (i != played && suit < Cards.SUITS) {
                count[suit]++;
            }
        }
        int best = random.nextInt(Cards.SUITS);
        for (int s = 0; s < Cards.SUITS; s++) {
            if (count[s] > count[best]) {
                best = s;
            }
        }
        return best;
    }
}
