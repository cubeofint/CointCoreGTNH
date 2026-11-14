package coint.migration;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

/** Standalone filesystem regression test; no Minecraft runtime or production world is used. */
public final class MigrationRegionResetTest {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("migration-region-reset-test-");
        Path region = dir.resolve("r.-1.-1.mca");
        byte[] original = new byte[16384];
        Arrays.fill(original, 8192, original.length, (byte) 73);
        ByteBuffer header = ByteBuffer.wrap(original);
        // (-1,-1) is slot 1023, not a negative offset. Its neighbour must remain byte-for-byte intact.
        header.putInt(1023 * 4, (2 << 8) | 1);
        header.putInt(1022 * 4, (3 << 8) | 1);
        header.putInt(4096 + 1023 * 4, 12345);
        header.putInt(4096 + 1022 * 4, 67890);
        Files.write(region, original);
        int[] selected = {-1, -1, -1, 1};
        MigrationRegionReset.erase(region.toFile(), Collections.singletonList(selected));
        byte[] expected = original.clone();
        ByteBuffer.wrap(expected).putInt(1023 * 4, 0).putInt(4096 + 1023 * 4, 0);
        check(Arrays.equals(expected, Files.readAllBytes(region)), "Only selected header entries may change");
        MigrationRegionReset.erase(region.toFile(), Collections.singletonList(selected));
        check(Arrays.equals(expected, Files.readAllBytes(region)), "Repeated erase must be idempotent");
        byte[] invalid = original.clone();
        ByteBuffer.wrap(invalid).putInt(1022 * 4, (99 << 8) | 1);
        Files.write(region, invalid);
        try {
            MigrationRegionReset.erase(region.toFile(), Arrays.asList(selected, new int[]{-1, -2, -1, 0}));
            throw new AssertionError("Invalid allocation must be rejected");
        } catch (IOException expectedFailure) {
            check(Arrays.equals(invalid, Files.readAllBytes(region)), "Validation must precede deletion");
        }
        Files.write(region, new byte[100]);
        try {
            MigrationRegionReset.erase(region.toFile(), Collections.singletonList(selected));
            throw new AssertionError("Truncated header must be rejected");
        } catch (IOException expectedFailure) {
            check(Files.size(region) == 100, "Truncated file must not be extended");
        }
        Files.delete(region);
        try {
            MigrationRegionReset.erase(region.toFile(), Collections.singletonList(selected));
            throw new AssertionError("Missing region must be rejected");
        } catch (IOException expectedFailure) {
            check(!Files.exists(region), "Missing file must not be created");
        }
        Files.delete(dir);
        System.out.println("PASS: negative coordinates, neighbouring data, idempotency, invalid allocation, truncated/missing regions");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
