package coint.performance;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Compresses and writes NBT files off the server thread, in submission order. The worker is not a daemon, so pending
 * writes finish before the JVM exits, and it stops by itself once idle, so it never holds a shutdown open.
 */
public final class AsyncNbtWriter {

    private static final Logger LOG = LogManager.getLogger("cointcore");
    private static final ThreadPoolExecutor EXECUTOR;

    static {
        EXECUTOR = new ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread thread = new Thread(r, "CointCore NBT writer");
            thread.setDaemon(false);
            return thread;
        });
        EXECUTOR.allowCoreThreadTimeOut(true);
    }

    private AsyncNbtWriter() {}

    /** Takes a snapshot of {@code tag} now and writes it to {@code out} (closing it) later. */
    public static void writeCompressed(NBTTagCompound tag, OutputStream out, String name) {
        NBTTagCompound snapshot = (NBTTagCompound) tag.copy();
        EXECUTOR.execute(() -> {
            try {
                CompressedStreamTools.writeCompressed(snapshot, out);
            } catch (IOException | RuntimeException e) {
                LOG.error("[Perf] Async write of {} failed", name, e);
                try {
                    out.close();
                } catch (IOException ignored) {}
            }
        });
    }
}
