package coint.cards;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** No-limit hold'em with table-only play chips. No items, account currency or external payouts. */
final class PokerGame {

    static final int MAX_PLAYERS = 4, STARTING_CHIPS = 1000, SMALL_BLIND = 10, BIG_BLIND = 20;
    static final int LOBBY = 0, PREFLOP = 1, FLOP = 2, TURN = 3, RIVER = 4, FINISHED = 5;
    static final long TURN_MS = 30_000;

    static final class Seat {

        final UUID id;
        final String name;
        final boolean bot;
        final List<Integer> cards = new ArrayList<>();
        int chips = STARTING_CHIPS, street, contributed, actedBet;
        boolean folded, inHand, acted, present = true;

        Seat(UUID id, String name, boolean bot) {
            this.id = id;
            this.name = name;
            this.bot = bot;
        }
    }

    final List<Seat> seats = new ArrayList<>();
    final List<Integer> board = new ArrayList<>();
    final Set<UUID> pending = new HashSet<>();
    private final List<Integer> deck = new ArrayList<>();
    private final Random random = new Random();
    int phase, revision, dealer = -1, turn = -1, bet, lastRaise = BIG_BLIND;
    long deadline, actedAt;
    boolean revealed;
    String result = "";

    Seat seat(UUID id) {
        for (Seat seat : seats) if (seat.id.equals(id)) return seat;
        return null;
    }

    boolean playing() {
        return phase >= PREFLOP && phase <= RIVER;
    }

    String join(UUID id, String name, boolean bot) {
        Seat old = seat(id);
        if (old != null) {
            old.present = true;
            return null;
        }
        if (playing()) return "cointcore.cards.error.running";
        if (seats.size() >= MAX_PLAYERS) return "cointcore.cards.error.full";
        seats.add(new Seat(id, name, bot));
        revision++;
        return null;
    }

    void leave(UUID id) {
        Seat seat = seat(id);
        if (seat == null) return;
        if (!playing()) {
            seats.remove(seat);
            if (dealer >= seats.size()) dealer = -1;
        } else {
            seat.present = false;
            if (seat.chips > 0) seat.folded = true;
            pending.remove(id);
            int after = seats.indexOf(seat) == turn ? turn : Math.floorMod(turn - 1, seats.size());
            advance(after, System.currentTimeMillis());
        }
        revision++;
    }

    String start(long now) {
        if (playing()) return "cointcore.cards.error.running";
        // Absent players leave only after their current pot was settled.
        Seat previousDealer = dealer >= 0 && dealer < seats.size() ? seats.get(dealer) : null;
        seats.removeIf(seat -> !seat.present);
        dealer = previousDealer == null ? -1 : seats.indexOf(previousDealer);
        int players = 0;
        for (Seat seat : seats) if (seat.chips > 0) players++;
        if (players < 2) return "cointcore.table.error.pokerPlayers";
        board.clear();
        deck.clear();
        deck.addAll(PlayingCards.deck(random));
        pending.clear();
        result = "";
        revealed = false;
        for (Seat seat : seats) {
            seat.cards.clear();
            seat.inHand = seat.chips > 0;
            seat.folded = !seat.inHand;
            seat.street = seat.contributed = seat.actedBet = 0;
            seat.acted = false;
            if (seat.inHand) {
                seat.cards.add(take());
                seat.cards.add(take());
            }
        }
        dealer = nextInHand(dealer);
        int small = players == 2 ? dealer : nextInHand(dealer);
        int big = nextInHand(small);
        pay(seats.get(small), SMALL_BLIND);
        pay(seats.get(big), BIG_BLIND);
        // Even a short all-in big blind does not lower the preflop bring-in.
        bet = BIG_BLIND;
        lastRaise = BIG_BLIND;
        phase = PREFLOP;
        for (Seat seat : seats) if (canAct(seat)) pending.add(seat.id);
        revision++;
        advance(big, now);
        return null;
    }

    int pot() {
        int total = 0;
        for (Seat seat : seats) total += seat.contributed;
        return total;
    }

    int toCall(Seat seat) {
        return Math.max(0, bet - seat.street);
    }

    boolean mayRaise(Seat seat) {
        return canAct(seat) && seat.chips > toCall(seat) && (!seat.acted || bet - seat.actedBet >= lastRaise);
    }

    String act(UUID id, int action, int target, int expected, long now) {
        if (revision != expected) return "cointcore.table.error.stale";
        Seat seat = seat(id);
        if (!playing() || seat == null || !seat.present || turn < 0 || seats.get(turn) != seat || !canAct(seat))
            return "cointcore.cards.error.notYourTurn";
        if (action == PacketTableAction.FOLD) seat.folded = true;
        else if (action == PacketTableAction.CALL) pay(seat, Math.min(seat.chips, toCall(seat)));
        else if (action == PacketTableAction.RAISE) {
            long maximum = (long) seat.street + seat.chips;
            if (!mayRaise(seat) || target <= bet || target > maximum || target < bet + lastRaise && target != maximum)
                return "cointcore.table.error.bet";
            int increase = target - bet;
            pay(seat, target - seat.street);
            bet = target;
            if (increase >= lastRaise) {
                lastRaise = increase;
                for (Seat other : seats) if (other != seat && canAct(other)) pending.add(other.id);
            } else {
                for (Seat other : seats)
                    if (other != seat && canAct(other) && other.street < bet) pending.add(other.id);
            }
        } else return "cointcore.table.error.move";
        seat.acted = true;
        seat.actedBet = bet;
        pending.remove(id);
        revision++;
        advance(turn, now);
        return null;
    }

    boolean tick(long now) {
        if (!playing() || turn < 0) return false;
        Seat seat = seats.get(turn);
        if (now >= deadline) {
            act(seat.id, toCall(seat) == 0 ? PacketTableAction.CALL : PacketTableAction.FOLD, 0, revision, now);
            return true;
        }
        if (!seat.bot || now - actedAt < 1500) return false;
        // Bots only inspect their own cards and the public board; no access to opponents' hands.
        boolean strong = seat.cards.size() == 2
            && (PlayingCards.rank(seat.cards.get(0)) == PlayingCards.rank(seat.cards.get(1))
                || PlayingCards.rank(seat.cards.get(0)) == 1
                || PlayingCards.rank(seat.cards.get(1)) == 1);
        int call = toCall(seat);
        if (strong && mayRaise(seat) && random.nextInt(5) == 0) {
            int target = Math.min(seat.street + seat.chips, bet + lastRaise);
            act(seat.id, PacketTableAction.RAISE, target, revision, now);
        } else act(
            seat.id,
            call == 0 || strong || call <= Math.max(BIG_BLIND, seat.chips / 4) ? PacketTableAction.CALL
                : PacketTableAction.FOLD,
            0,
            revision,
            now);
        return true;
    }

    private boolean canAct(Seat seat) {
        return seat.inHand && !seat.folded && seat.chips > 0;
    }

    private int nextInHand(int after) {
        for (int step = 1; step <= seats.size(); step++) {
            int index = Math.floorMod(after + step, seats.size());
            if (seats.get(index).inHand) return index;
        }
        return -1;
    }

    private int take() {
        return deck.remove(deck.size() - 1);
    }

    private void pay(Seat seat, int amount) {
        seat.chips -= amount;
        seat.street += amount;
        seat.contributed += amount;
    }

    private void advance(int after, long now) {
        while (playing()) {
            int remaining = 0, actionable = 0;
            Seat last = null, onlyActor = null;
            for (Seat seat : seats) {
                if (seat.inHand && !seat.folded) {
                    remaining++;
                    last = seat;
                }
                if (canAct(seat)) {
                    actionable++;
                    onlyActor = seat;
                }
                if (!canAct(seat)) pending.remove(seat.id);
            }
            if (remaining <= 1) {
                if (last != null) {
                    int chips = pot();
                    last.chips += chips;
                    result = last.name + " +" + chips;
                }
                finish();
                return;
            }
            if (actionable == 1 && onlyActor.street >= bet) pending.clear();
            if (!pending.isEmpty()) {
                for (int step = 1; step <= seats.size(); step++) {
                    int candidate = Math.floorMod(after + step, seats.size());
                    if (pending.contains(seats.get(candidate).id)) {
                        turn = candidate;
                        actedAt = now;
                        deadline = now + TURN_MS;
                        return;
                    }
                }
            }
            if (phase == RIVER) {
                showdown();
                return;
            }
            take(); // Burn before every public street.
            for (int i = 0; i < (phase == PREFLOP ? 3 : 1); i++) board.add(take());
            phase++;
            bet = 0;
            lastRaise = BIG_BLIND;
            for (Seat seat : seats) {
                seat.street = seat.actedBet = 0;
                seat.acted = false;
                if (canAct(seat)) pending.add(seat.id);
            }
            if (actionable < 2) pending.clear();
            after = dealer;
        }
    }

    private void showdown() {
        revealed = true;
        Map<Seat, Integer> payouts = new LinkedHashMap<>();
        TreeSet<Integer> levels = new TreeSet<>();
        for (Seat seat : seats) if (seat.contributed > 0) levels.add(seat.contributed);
        int previous = 0;
        for (int level : levels) {
            int contributors = 0;
            List<Seat> eligible = new ArrayList<>();
            for (int i = 1; i <= seats.size(); i++) {
                Seat seat = seats.get((dealer + i) % seats.size());
                if (seat.contributed < level) continue;
                contributors++;
                if (seat.inHand && !seat.folded) eligible.add(seat);
            }
            int amount = (level - previous) * contributors;
            if (contributors == 1 || eligible.isEmpty()) {
                // Uncalled excess is returned, including an absent player's uncalled chips.
                for (Seat seat : seats) if (seat.contributed >= level) seat.chips += level - previous;
            } else {
                long best = -1;
                List<Seat> winners = new ArrayList<>();
                for (Seat seat : eligible) {
                    List<Integer> cards = new ArrayList<>(board);
                    cards.addAll(seat.cards);
                    long score = PokerHands.best(cards);
                    if (score > best) {
                        best = score;
                        winners.clear();
                    }
                    if (score == best) winners.add(seat);
                }
                int remainder = amount % winners.size();
                for (Seat winner : winners) {
                    int chips = amount / winners.size() + (remainder-- > 0 ? 1 : 0);
                    winner.chips += chips;
                    payouts.put(winner, payouts.getOrDefault(winner, 0) + chips);
                }
            }
            previous = level;
        }
        List<String> names = new ArrayList<>();
        for (Map.Entry<Seat, Integer> entry : payouts.entrySet())
            names.add(entry.getKey().name + " +" + entry.getValue());
        result = String.join(", ", names);
        finish();
    }

    private void finish() {
        phase = FINISHED;
        turn = -1;
        pending.clear();
        for (Seat seat : seats) seat.contributed = seat.street = 0;
    }

    int[] visibleHand(Seat seat, UUID viewer) {
        if (!seat.id.equals(viewer) && !(phase == FINISHED && revealed && !seat.folded)) return new int[0];
        int[] result = new int[seat.cards.size()];
        for (int i = 0; i < result.length; i++) result[i] = seat.cards.get(i);
        return result;
    }
}
