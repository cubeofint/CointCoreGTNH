package coint.cards;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * One card table: who sits at it and, while a round is played, the hands, the piles and whose turn it is. Only the
 * server has it; players get what they may see through {@link PacketCardState}.
 */
final class CardGame {

    static final int MAX_PLAYERS = 4;
    static final long TURN_MS = 30_000;
    /** How long a bot thinks before it moves. */
    private static final long BOT_MS = 1_500;
    private static final int LOG_LINES = 8;
    /** How long a player left with one card has to say so. */
    static final long CALL_MS = 3_000;
    private static final int CALL_PENALTY = 2;

    enum Phase {
        LOBBY,
        PLAYING
    }

    /** A line of the table's log; shown in the player's language. */
    static final class Line {

        static final byte JOIN = 0, LEAVE = 1, START = 2, PLAY = 3, DRAW = 4, PASS = 5, TIMEOUT = 6, WIN = 7, CALL = 8,
            MISSED = 9, CAUGHT = 10;

        final byte kind;
        final String name;
        /** The card played (PLAY), cards drawn (DRAW), else 0. */
        final int card;
        /** The suit picked for a suitless card (PLAY), else -1. */
        final int suit;

        Line(byte kind, String name, int card, int suit) {
            this.kind = kind;
            this.name = name;
            this.card = card;
            this.suit = suit;
        }
    }

    final int dim, x, y, z;
    Phase phase = Phase.LOBBY;
    /** In seating order. */
    final List<UUID> players = new ArrayList<>();
    final Map<UUID, String> names = new HashMap<>();
    final Map<UUID, List<Integer>> hands = new HashMap<>();
    /** Seats played by the server. */
    final Set<UUID> bots = new HashSet<>();
    UUID host;
    final List<Integer> drawPile = new ArrayList<>();
    /** The top is the last. */
    final List<Integer> discard = new ArrayList<>();
    int turn;
    int direction = 1;
    /** The suit to follow. */
    int suit;
    /** The player on turn has drawn: he may only play the card drawn, or pass. */
    boolean drew;
    long turnEndsAt;
    long turnStartedAt;
    /** How many log lines were ever written: tells clients which lines are new. */
    int logSeq;
    final Deque<Line> log = new ArrayDeque<>();
    /** Players who said "last card" ahead, on their turn with two cards. */
    final Set<UUID> called = new HashSet<>();
    /** The player left with one card who has not said so yet, and until when he may. */
    UUID pendingCall;
    long callDeadline;
    private final Random random = new Random();

    CardGame(int dim, int x, int y, int z) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    // ---- seats

    /** Null when seated, else why not (a translation key). */
    String join(UUID player, String name) {
        if (players.contains(player)) {
            return null;
        }
        if (phase != Phase.LOBBY) {
            return "cointcore.cards.error.running";
        }
        if (players.size() >= MAX_PLAYERS) {
            return "cointcore.cards.error.full";
        }
        players.add(player);
        names.put(player, name);
        if (host == null) {
            host = player;
        }
        log(Line.JOIN, name, 0, -1);
        return null;
    }

    void leave(UUID player) {
        int index = players.indexOf(player);
        if (index < 0) {
            return;
        }
        String name = names.remove(player);
        players.remove(index);
        bots.remove(player);
        log(Line.LEAVE, name, 0, -1);
        if (players.size() == bots.size()) {
            // No one real is left: the bots go too.
            players.clear();
            bots.clear();
            names.clear();
            host = null;
            end();
            return;
        }
        if (player.equals(host)) {
            host = players.stream()
                .filter(p -> !bots.contains(p))
                .findFirst()
                .orElse(null);
        }
        if (phase != Phase.PLAYING) {
            return;
        }
        List<Integer> hand = hands.remove(player);
        if (hand != null) {
            drawPile.addAll(0, hand);
        }
        if (players.size() < 2) {
            if (!players.isEmpty()) {
                win(players.get(0));
            } else {
                end();
            }
            return;
        }
        if (index < turn) {
            turn--;
        } else if (index == turn) {
            // The seat of the one who left now holds the next player that way.
            turn = direction > 0 ? index % players.size() : Math.floorMod(index - 1, players.size());
            newTurn();
        }
    }

    boolean isEmpty() {
        return players.isEmpty();
    }

    /** Null when a bot sat down, else why not (a translation key). */
    String addBot(UUID by) {
        if (!by.equals(host)) {
            return "cointcore.cards.error.notHost";
        }
        int n = 1;
        while (names.containsValue("Bot " + n)) {
            n++;
        }
        UUID bot = new UUID(0xCA4DB07L, n);
        String error = join(bot, "Bot " + n);
        if (error == null) {
            bots.add(bot);
        }
        return error;
    }

    /** Null when the last bot stood up, else why not (a translation key). */
    String removeBot(UUID by) {
        if (!by.equals(host)) {
            return "cointcore.cards.error.notHost";
        }
        if (phase != Phase.LOBBY) {
            return "cointcore.cards.error.running";
        }
        for (int i = players.size() - 1; i >= 0; i--) {
            if (bots.contains(players.get(i))) {
                leave(players.get(i));
                return null;
            }
        }
        return "cointcore.cards.error.noBots";
    }

    /** A bot on turn makes its move once it has thought. True when it did. */
    boolean botMove(long now) {
        UUID bot = current();
        if (bot == null || !bots.contains(bot) || now < turnStartedAt + BOT_MS) {
            return false;
        }
        List<Integer> hand = hands.get(bot);
        int pick = BotBrain.choose(hand, top(), suit, drew, nextHandSize());
        if (pick >= 0) {
            play(bot, pick, BotBrain.suitFor(hand, pick, random));
        } else if (!drew) {
            draw(bot);
            // A playable card drawn is played on the next move, after a little thought.
            turnStartedAt = now;
        } else {
            pass(bot);
        }
        return true;
    }

    private int nextHandSize() {
        List<Integer> hand = hands.get(players.get(Math.floorMod(turn + direction, players.size())));
        return hand == null ? 0 : hand.size();
    }

    // ---- a round

    /** Null when started, else why not (a translation key). */
    String start(UUID by) {
        if (!by.equals(host)) {
            return "cointcore.cards.error.notHost";
        }
        if (phase != Phase.LOBBY) {
            return "cointcore.cards.error.running";
        }
        if (players.size() < 2) {
            return "cointcore.cards.error.alone";
        }
        drawPile.clear();
        discard.clear();
        hands.clear();
        drawPile.addAll(Cards.deck(random));
        for (UUID p : players) {
            List<Integer> hand = new ArrayList<>();
            for (int i = 0; i < Cards.HAND; i++) {
                hand.add(take());
            }
            hands.put(p, hand);
        }
        // The first card on the table is a number.
        int first = take();
        while (Cards.value(first) > 9) {
            drawPile.add(0, first);
            first = take();
        }
        discard.add(first);
        suit = Cards.suit(first);
        direction = 1;
        turn = random.nextInt(players.size());
        phase = Phase.PLAYING;
        log(Line.START, names.get(players.get(turn)), 0, -1);
        newTurn();
        return null;
    }

    int top() {
        return discard.isEmpty() ? -1 : discard.get(discard.size() - 1);
    }

    UUID current() {
        return phase == Phase.PLAYING && !players.isEmpty() ? players.get(turn) : null;
    }

    /** Null when played, else why not (a translation key). */
    String play(UUID player, int index, int pickedSuit) {
        if (!player.equals(current())) {
            return "cointcore.cards.error.notYourTurn";
        }
        List<Integer> hand = hands.get(player);
        if (index < 0 || index >= hand.size()) {
            return "cointcore.cards.error.noCard";
        }
        if (drew && index != hand.size() - 1) {
            return "cointcore.cards.error.onlyDrawn";
        }
        int card = hand.get(index);
        if (!Cards.playable(card, top(), suit)) {
            return "cointcore.cards.error.cantPlay";
        }
        if (Cards.isWild(card) && (pickedSuit < 0 || pickedSuit >= Cards.SUITS)) {
            return "cointcore.cards.error.pickSuit";
        }
        hand.remove(index);
        discard.add(card);
        suit = Cards.isWild(card) ? pickedSuit : Cards.suit(card);
        log(Line.PLAY, names.get(player), card, Cards.isWild(card) ? pickedSuit : -1);
        if (hand.isEmpty()) {
            win(player);
            return null;
        }
        if (hand.size() == 1) {
            // Bots forget now and then too.
            if (called.remove(player) || bots.contains(player) && random.nextInt(100) < 85) {
                log(Line.CALL, names.get(player), 0, -1);
            } else {
                pendingCall = player;
                callDeadline = System.currentTimeMillis() + CALL_MS;
            }
        }
        switch (Cards.value(card)) {
            case Cards.SKIP -> advance(2);
            case Cards.REVERSE -> {
                direction = -direction;
                // With two players it skips the other one.
                advance(players.size() == 2 ? 2 : 1);
            }
            case Cards.DRAW_TWO -> penalize(2);
            case Cards.WILD_DRAW_FOUR -> penalize(4);
            default -> advance(1);
        }
        return null;
    }

    /** Null when drawn, else why not (a translation key). */
    String draw(UUID player) {
        if (!player.equals(current())) {
            return "cointcore.cards.error.notYourTurn";
        }
        if (drew) {
            return "cointcore.cards.error.drewAlready";
        }
        int card = take();
        if (card < 0) {
            // No cards left anywhere: the turn just passes.
            log(Line.PASS, names.get(player), 0, -1);
            advance(1);
            return null;
        }
        hands.get(player)
            .add(card);
        log(Line.DRAW, names.get(player), 1, -1);
        if (Cards.playable(card, top(), suit)) {
            drew = true;
        } else {
            advance(1);
        }
        return null;
    }

    /**
     * "Last card!": ahead, on your turn with two cards, or in time after playing down to one. Said by the player on
     * turn
     * while someone else still has not said it, it catches him: he takes the penalty at once. Null when said, else why
     * not (a translation key).
     */
    String call(UUID player) {
        if (player.equals(pendingCall)) {
            pendingCall = null;
            log(Line.CALL, names.get(player), 0, -1);
            return null;
        }
        List<Integer> hand = hands.get(player);
        boolean onTurn = player.equals(current());
        boolean caught = false;
        if (onTurn && canCatch(player)) {
            UUID forgot = pendingCall;
            pendingCall = null;
            log(Line.CAUGHT, names.get(player), 0, -1);
            missed(forgot);
            caught = true;
        }
        if (onTurn && hand != null && hand.size() == 2) {
            called.add(player);
            return null;
        }
        return caught ? null : "cointcore.cards.error.cantCall";
    }

    /** Someone else played down to one card without saying so, and {@code player} may still catch him. */
    boolean canCatch(UUID player) {
        return pendingCall != null && !pendingCall.equals(player) && phase == Phase.PLAYING;
    }

    /** Whoever let the time to say "last card" run out takes the penalty. True when someone did. */
    boolean checkCall(long now) {
        if (pendingCall == null || now < callDeadline) {
            return false;
        }
        UUID player = pendingCall;
        pendingCall = null;
        return missed(player);
    }

    /** {@code player} did not say "last card": he draws the penalty. True when he did. */
    private boolean missed(UUID player) {
        List<Integer> hand = hands.get(player);
        if (phase != Phase.PLAYING || hand == null || hand.size() != 1) {
            return false;
        }
        int drawn = 0;
        for (int i = 0; i < CALL_PENALTY; i++) {
            int card = take();
            if (card < 0) {
                break;
            }
            hand.add(card);
            drawn++;
        }
        log(Line.MISSED, names.get(player), drawn, -1);
        return true;
    }

    /** Null when passed, else why not (a translation key). */
    String pass(UUID player) {
        if (!player.equals(current())) {
            return "cointcore.cards.error.notYourTurn";
        }
        if (!drew) {
            return "cointcore.cards.error.drawFirst";
        }
        log(Line.PASS, names.get(player), 0, -1);
        advance(1);
        return null;
    }

    /** The player on turn ran out of time: he draws a card and the turn passes. True when it did. */
    boolean timeout(long now) {
        if (phase != Phase.PLAYING || now < turnEndsAt) {
            return false;
        }
        UUID player = current();
        log(Line.TIMEOUT, names.get(player), 0, -1);
        if (!drew) {
            int card = take();
            if (card >= 0) {
                hands.get(player)
                    .add(card);
            }
        }
        advance(1);
        return true;
    }

    private void penalize(int cards) {
        int next = Math.floorMod(turn + direction, players.size());
        UUID victim = players.get(next);
        List<Integer> hand = hands.get(victim);
        int drawn = 0;
        for (int i = 0; i < cards; i++) {
            int card = take();
            if (card < 0) {
                break;
            }
            hand.add(card);
            drawn++;
        }
        log(Line.DRAW, names.get(victim), drawn, -1);
        advance(2);
    }

    private void advance(int steps) {
        turn = Math.floorMod(turn + direction * steps, players.size());
        newTurn();
    }

    private void newTurn() {
        drew = false;
        called.clear();
        turnStartedAt = System.currentTimeMillis();
        turnEndsAt = turnStartedAt + TURN_MS;
    }

    /** The next card to draw, -1 when there is none: the discard pile under its top is shuffled in when empty. */
    private int take() {
        if (drawPile.isEmpty() && discard.size() > 1) {
            int top = discard.remove(discard.size() - 1);
            drawPile.addAll(discard);
            discard.clear();
            discard.add(top);
            Collections.shuffle(drawPile, random);
        }
        return drawPile.isEmpty() ? -1 : drawPile.remove(drawPile.size() - 1);
    }

    private void win(UUID player) {
        log(Line.WIN, names.get(player), 0, -1);
        end();
    }

    private void end() {
        phase = Phase.LOBBY;
        pendingCall = null;
        called.clear();
        hands.clear();
        drawPile.clear();
        discard.clear();
        drew = false;
    }

    private void log(byte kind, String name, int card, int suit) {
        logSeq++;
        log.addLast(new Line(kind, name == null ? "?" : name, card, suit));
        while (log.size() > LOG_LINES) {
            log.removeFirst();
        }
    }
}
