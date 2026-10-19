package coint.supply;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.gtnewhorizon.gtnhlib.eventbus.EventBusSubscriber;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Runs on the server thread what packets bring in on the network thread. */
@EventBusSubscriber
public final class SupplyServerTasks {

    private static final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

    private SupplyServerTasks() {}

    public static void run(Runnable task) {
        tasks.add(task);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        Runnable task;
        while ((task = tasks.poll()) != null) {
            task.run();
        }
    }
}
