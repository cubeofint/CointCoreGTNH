package coint.commands.temprank;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class DonorQueueTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static int checks;
    private static TempRankEntry rank(String name, long end) {
        return new TempRankEntry(PLAYER, "QueueTest", name, end, "test");
    }
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("Check " + checks); }
    public static void main(String[] args) {
        TempRankEntry low = rank("uranium", 11000), high = rank("fusion", 6000);
        List<TempRankEntry> entries = new ArrayList<>(Arrays.asList(low, high));
        DonorQueue.settle(entries, PLAYER, 1000);
        check(low.pausedMillis == 10000 && low.expiresAt == 0);
        check(high.pausedMillis == 0 && high.expiresAt == 6000);
        DonorQueue.settle(entries, PLAYER, 2000);
        check(low.pausedMillis == 10000);
        DonorQueue.settle(entries, PLAYER, 8000);
        check(!entries.contains(high) && low.expiresAt == 16000 && low.pausedMillis == 0);
        DonorQueue.settle(entries, PLAYER, 17000);
        check(entries.isEmpty());

        high = rank("fusion", 10000); low = rank("uranium", 9000);
        TempRankEntry staff = rank("moderator", 20000);
        entries = new ArrayList<>(Arrays.asList(high, low, staff));
        DonorQueue.settle(entries, PLAYER, 5000);
        check(low.pausedMillis == 4000 && staff.expiresAt == 20000 && staff.pausedMillis == 0);
        entries.remove(high); DonorQueue.settle(entries, PLAYER, 6000);
        check(low.expiresAt == 10000 && low.pausedMillis == 0);

        high = rank("fusion", -1); low = rank("uranium", -1);
        entries = new ArrayList<>(Arrays.asList(low, high));
        DonorQueue.settle(entries, PLAYER, 1000);
        check(low.pausedMillis == -1 && high.expiresAt == -1);
        entries.remove(high); DonorQueue.settle(entries, PLAYER, 5000);
        check(low.pausedMillis == 0 && low.expiresAt == -1);

        low = rank("uranium", 0); low.pausedMillis = 1000;
        TempRankEntry middle = rank("quantum", 0); middle.pausedMillis = 2000;
        high = rank("fusion", 3000);
        entries = new ArrayList<>(Arrays.asList(low, middle, high));
        DonorQueue.settle(entries, PLAYER, 5500);
        check(entries.size() == 1 && entries.get(0) == low && low.expiresAt == 6000);
        DonorQueue.settle(entries, PLAYER, 6000);
        check(entries.isEmpty());
        System.out.println("DONOR_QUEUE_PASS checks=" + checks);
    }
}
