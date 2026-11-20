package coint.cards;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** One player against a dealer. Chips belong only to this in-memory table session. */
final class BlackjackGame {

    static final int BET = 20, STARTING_CHIPS = 1000, LOBBY = 0, PLAYING = 1, FINISHED = 5;
    final List<Integer> player = new ArrayList<>(), dealer = new ArrayList<>(), deck = new ArrayList<>();
    private final Random random;
    int phase = LOBBY, revision, chips = STARTING_CHIPS;
    String result = "";

    BlackjackGame() {
        this(new Random());
    }

    BlackjackGame(Random random) {
        this.random = random;
    }

    String act(int action, int expectedRevision) {
        if (revision != expectedRevision) return "cointcore.table.error.stale";
        if (action == PacketTableAction.DEAL) {
            if (phase == PLAYING) return "cointcore.table.error.move";
            if (chips < BET) return "cointcore.table.blackjack.noChips";
            chips -= BET;
            player.clear();
            dealer.clear();
            deck.clear();
            deck.addAll(PlayingCards.deck(random));
            result = "";
            phase = PLAYING;
            for (int i = 0; i < 2; i++) {
                draw(player);
                draw(dealer);
            }
            boolean mine = total(player) == 21, theirs = total(dealer) == 21;
            if (mine || theirs) {
                if (mine && theirs) finish("push", BET);
                else if (mine) finish("natural", BET + BET * 3 / 2);
                else finish("dealerNatural", 0);
            }
        } else {
            if (phase != PLAYING) return "cointcore.table.error.move";
            if (action == PacketTableAction.HIT) {
                draw(player);
                if (total(player) > 21) finish("bust", 0);
                else if (total(player) == 21) settle();
            } else if (action == PacketTableAction.STAND) settle();
            else return "cointcore.table.error.move";
        }
        revision++;
        return null;
    }

    private void settle() {
        // Stand on all 17s, including a soft 17. A fresh 52-card deck cannot run out in this game.
        while (total(dealer) < 17) draw(dealer);
        int mine = total(player), theirs = total(dealer);
        if (theirs > 21) finish("dealerBust", BET * 2);
        else if (mine > theirs) finish("win", BET * 2);
        else if (mine == theirs) finish("push", BET);
        else finish("lose", 0);
    }

    private void draw(List<Integer> hand) {
        hand.add(deck.remove(deck.size() - 1));
    }

    private void finish(String outcome, int returned) {
        phase = FINISHED;
        result = "cointcore.table.blackjack." + outcome;
        chips = (int) Math.min(Integer.MAX_VALUE, (long) chips + returned);
    }

    static int total(List<Integer> hand) {
        int[] cards = new int[hand.size()];
        for (int i = 0; i < cards.length; i++) cards[i] = hand.get(i);
        return PlayingCards.blackjackTotal(cards);
    }

    int[] visibleHand(boolean dealersHand) {
        List<Integer> hand = dealersHand ? dealer : player;
        int[] cards = new int[hand.size()];
        for (int i = 0; i < cards.length; i++) cards[i] = dealersHand && phase == PLAYING && i > 0 ? -1 : hand.get(i);
        return cards;
    }
}
