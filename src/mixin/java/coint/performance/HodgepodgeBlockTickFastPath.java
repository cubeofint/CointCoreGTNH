package coint.performance;

import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

public final class HodgepodgeBlockTickFastPath {

    private static final Logger LOG = LogManager.getLogger("cointcore");
    private static final ThreadLocal<CallState> CALL_STATE = new ThreadLocal<CallState>() {

        @Override
        protected CallState initialValue() {
            return new CallState();
        }
    };
    private static boolean logged;

    private HodgepodgeBlockTickFastPath() {}

    public static void run(WorldServer owner, Block block, World world, int x, int y, int z, Random random,
        Operation<Void> original) {
        if (!logged) {
            logged = true;
            LOG.info("[Perf] Hodgepodge block update fast path active");
        }

        ChunkProviderServer provider = owner.theChunkProviderServer;
        boolean restoreChunkLoading = provider.loadChunkOnProvideRequest;
        if (restoreChunkLoading) {
            provider.loadChunkOnProvideRequest = false;
        }

        CallState state = null;
        Object[] args = null;
        try {
            state = CALL_STATE.get();
            args = state.acquire();
            args[0] = block;
            args[1] = world;
            args[2] = state.box(x);
            args[3] = state.box(y);
            args[4] = state.box(z);
            args[5] = random;
            original.call(args);
        } finally {
            if (args != null) {
                args[0] = null;
                args[1] = null;
                args[2] = null;
                args[3] = null;
                args[4] = null;
                args[5] = null;
                state.release();
            }
            if (restoreChunkLoading) {
                provider.loadChunkOnProvideRequest = true;
            }
        }
    }

    private static final class CallState {

        private static final int BOX_CACHE_MASK = 4095;

        private Object[][] args = { new Object[6], new Object[6], new Object[6], new Object[6] };
        private final int[] boxedValues = new int[BOX_CACHE_MASK + 1];
        private final Integer[] boxedIntegers = new Integer[BOX_CACHE_MASK + 1];
        private int depth;

        private Object[] acquire() {
            if (depth == args.length) {
                Object[][] expanded = new Object[args.length << 1][];
                System.arraycopy(args, 0, expanded, 0, args.length);
                for (int i = args.length; i < expanded.length; i++) {
                    expanded[i] = new Object[6];
                }
                args = expanded;
            }
            return args[depth++];
        }

        private Integer box(int value) {
            int index = value & BOX_CACHE_MASK;
            Integer boxed = boxedIntegers[index];
            if (boxed == null || boxedValues[index] != value) {
                boxed = Integer.valueOf(value);
                boxedValues[index] = value;
                boxedIntegers[index] = boxed;
            }
            return boxed;
        }

        private void release() {
            depth--;
        }
    }
}
