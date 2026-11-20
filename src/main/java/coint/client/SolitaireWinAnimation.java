package coint.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/** Client-only victory cascade. Absolute-time trajectories and bounded trails do not depend on frame rate. */
final class SolitaireWinAnimation {

    static final int LAUNCH_MS = 140, SAMPLE_MS = 90, MAX_TRAIL = 160;
    private static final int HISTORY_MS = 1500;

    static final class Stamp {

        final int card;
        final double x, y;

        Stamp(int card, double x, double y) {
            this.card = card;
            this.x = x;
            this.y = y;
        }
    }

    private static final class Flight {

        final int card, foundation, delay;
        final double x, y, vx, vy, gravity, floor, duration;

        Flight(int card, int foundation, int delay, SolitaireBoard board, Random random) {
            this.card = card;
            this.foundation = foundation;
            this.delay = delay;
            SolitaireBoard.Card source = board.targets[foundation + 2];
            x = source.x;
            y = source.y;
            floor = Math.max(y, board.height - 42 - board.h);
            double direction = x + board.w / 2.0 < board.width / 2.0 ? 1 : -1;
            vx = direction * board.width * (0.12 + random.nextDouble() * 0.12);
            vy = -16 - random.nextDouble() * 30;
            gravity = Math.max(180, (floor - y) * 2.5);
            duration = vx > 0 ? (board.width + 2 - x) / vx : (x + board.w + 2) / -vx;
        }

        Stamp at(long elapsed) {
            double age = (elapsed - delay) / 1000.0;
            if (age < 0 || age >= duration) return null;
            double py = y, velocity = vy, remaining = age;
            for (int bounce = 0; bounce < 16; bounce++) {
                double impact = Math.sqrt(velocity * velocity + 2 * gravity * Math.max(0, floor - py));
                double fall = (impact - velocity) / gravity;
                if (remaining <= fall) return new Stamp(
                    card,
                    x + vx * age,
                    Math.min(floor, py + velocity * remaining + gravity * remaining * remaining / 2));
                remaining -= fall;
                py = floor;
                velocity = -impact * 0.72;
                if (-velocity < 8) break;
            }
            return new Stamp(card, x + vx * age, floor);
        }
    }

    final int width, height, w, h;
    final long startedAt;
    final List<Stamp> flying = new ArrayList<>();
    final Deque<Stamp> trail = new ArrayDeque<>();
    private final List<Flight> flights = new ArrayList<>();
    private final int[][] foundations = new int[4][];
    private final int[] launched = new int[4];
    private long elapsed = -1, sampled = -SAMPLE_MS, endAt;

    SolitaireWinAnimation(SolitaireBoard board, long now) {
        width = board.width;
        height = board.height;
        w = board.w;
        h = board.h;
        startedAt = now;
        Random random = new Random(0x534F4C4954414952L);
        for (int i = 0; i < 4; i++) foundations[i] = board.piles.get(i + 2)
            .clone();
        // Peel kings first, alternating between all four foundations, without changing the server snapshot.
        for (int depth = 0; depth < 13; depth++) for (int pile = 0; pile < 4; pile++) {
            int index = foundations[pile].length - depth - 1;
            if (index < 0) continue;
            Flight flight = new Flight(foundations[pile][index], pile, flights.size() * LAUNCH_MS, board, random);
            flights.add(flight);
            endAt = Math.max(endAt, flight.delay + (long) Math.ceil(flight.duration * 1000));
        }
        advance(now);
    }

    void advance(long now) {
        long next = Math.min(endAt, Math.max(0, now - startedAt));
        if (next <= elapsed) return;
        elapsed = next;
        // Skip stale samples after a long pause instead of trying to replay every missed frame.
        long first = Math.max(sampled + SAMPLE_MS, Math.max(0, (elapsed - HISTORY_MS) / SAMPLE_MS * SAMPLE_MS));
        for (long time = first; time <= elapsed; time += SAMPLE_MS) {
            for (Flight flight : flights) {
                Stamp stamp = flight.at(time);
                if (stamp == null) continue;
                if (trail.size() == MAX_TRAIL) trail.removeFirst();
                trail.addLast(stamp);
            }
            sampled = time;
        }
        flying.clear();
        for (int i = 0; i < 4; i++) launched[i] = 0;
        for (Flight flight : flights) {
            if (elapsed >= flight.delay) launched[flight.foundation]++;
            Stamp stamp = flight.at(elapsed);
            if (stamp != null) flying.add(stamp);
        }
    }

    int remainingTop(int pile) {
        int index = foundations[pile].length - launched[pile] - 1;
        return index >= 0 ? foundations[pile][index] : -2;
    }

    SolitaireWinAnimation resized(SolitaireBoard board, long now) {
        if (board.width == width && board.height == height) return this;
        return new SolitaireWinAnimation(board, startedAt).advancedTo(now);
    }

    private SolitaireWinAnimation advancedTo(long now) {
        advance(now);
        return this;
    }
}
