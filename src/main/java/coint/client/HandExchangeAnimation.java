package coint.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Client-only hand flights. Other players are represented by names and public card counts, never card faces. */
final class HandExchangeAnimation {

    static final int FLIGHT_MS = 680, STAGGER_MS = 28, MAX_FAN = 6;

    static final class Transfer {

        final String from, to;
        final int count;

        Transfer(String from, String to, int count) {
            this.from = from;
            this.to = to;
            this.count = count;
        }

        int shown() {
            return Math.min(MAX_FAN, count);
        }
    }

    final boolean rotation;
    final long start, end;
    final List<Transfer> transfers;
    final Map<String, Integer> beforeCounts;
    final List<Integer> beforeHand;

    private HandExchangeAnimation(boolean rotation, long start, List<Transfer> transfers, Map<String, Integer> counts,
        List<Integer> hand) {
        this.rotation = rotation;
        this.start = start;
        this.transfers = Collections.unmodifiableList(transfers);
        this.beforeCounts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
        this.beforeHand = Collections.unmodifiableList(new ArrayList<>(hand));
        int fan = 1;
        for (Transfer transfer : transfers) fan = Math.max(fan, transfer.shown());
        end = start + FLIGHT_MS + (fan - 1) * STAGGER_MS;
    }

    static HandExchangeAnimation swap(String a, String b, Map<String, Integer> counts, List<Integer> hand, long start) {
        if (a.equals(b) || !counts.containsKey(a) || !counts.containsKey(b)) return null;
        List<Transfer> transfers = new ArrayList<>();
        transfers.add(new Transfer(a, b, counts.get(a)));
        transfers.add(new Transfer(b, a, counts.get(b)));
        return new HandExchangeAnimation(false, start, transfers, counts, hand);
    }

    static HandExchangeAnimation rotate(List<String> seats, int direction, Map<String, Integer> counts,
        List<Integer> hand, long start) {
        if (seats.size() < 2 || Math.abs(direction) != 1) return null;
        List<Transfer> transfers = new ArrayList<>();
        for (int i = 0; i < seats.size(); i++) {
            String from = seats.get(i), to = seats.get(Math.floorMod(i + direction, seats.size()));
            if (!counts.containsKey(from)) return null;
            transfers.add(new Transfer(from, to, counts.get(from)));
        }
        return new HandExchangeAnimation(true, start, transfers, counts, hand);
    }

    boolean involves(String player) {
        for (Transfer transfer : transfers) if (transfer.from.equals(player)) return true;
        return false;
    }

    void applyCounts(Map<String, Integer> counts) {
        for (Transfer transfer : transfers) counts.put(transfer.to, transfer.count);
    }

    static float progress(long now, long start, int card) {
        return Math.max(0, Math.min(1, (now - start - card * STAGGER_MS) / (float) FLIGHT_MS));
    }

    /** Ease in and out, including zero speed at both endpoints. */
    static float ease(float progress) {
        float t = Math.max(0, Math.min(1, progress));
        return t * t * (3 - 2 * t);
    }

    /** Curved flights: opposite sides for a swap, outside the piles for a rotation. */
    static float[] position(float progress, float x0, float y0, float x1, float y1, float cx, float cy,
        boolean rotation, int players) {
        float t = ease(progress), midX = (x0 + x1) / 2, midY = (y0 + y1) / 2;
        float controlX, controlY;
        if (rotation && players > 2) {
            controlX = midX + (midX - cx) * 0.8F;
            controlY = midY + (midY - cy) * 0.8F;
        } else {
            float dx = x1 - x0, dy = y1 - y0;
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            float bend = Math.min(70, distance * 0.35F) / Math.max(1, distance);
            controlX = midX - dy * bend;
            controlY = midY + dx * bend;
        }
        float u = 1 - t;
        return new float[] { u * u * x0 + 2 * u * t * controlX + t * t * x1,
            u * u * y0 + 2 * u * t * controlY + t * t * y1 };
    }
}
