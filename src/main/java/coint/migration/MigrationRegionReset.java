package coint.migration;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.List;

/** Removes only selected Anvil index entries; neighbouring chunk payloads are never rewritten. */
final class MigrationRegionReset {

    private MigrationRegionReset() {}

    static void erase(File region, List<int[]> chunks) throws IOException {
        if (!region.isFile()) throw new IOException("Missing source region: " + region);
        try (RandomAccessFile file = new RandomAccessFile(region, "rw")) {
            if (file.length() < 8192) throw new IOException("Truncated source region: " + region);
            // Validate every requested entry before modifying this region.
            for (int[] c : chunks) {
                file.seek(slot(c) * 4L);
                int location = file.readInt();
                int sector = location >>> 8, count = location & 255;
                if (location != 0 && (sector < 2 || count == 0 || (sector + (long) count) * 4096 > file.length()))
                    throw new IOException("Invalid source chunk allocation: " + region + " slot " + slot(c));
            }
            for (int[] c : chunks) {
                file.seek(slot(c) * 4L);
                file.writeInt(0);
                file.seek(4096 + slot(c) * 4L);
                file.writeInt(0);
            }
            file.getFD()
                .sync();
        }
    }

    private static int slot(int[] c) {
        return (c[1] & 31) + (c[2] & 31) * 32;
    }
}
