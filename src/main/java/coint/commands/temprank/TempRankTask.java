package coint.commands.temprank;

import serverutils.lib.data.Universe;
import serverutils.lib.math.Ticks;
import serverutils.task.Task;

/**
 * Repeatable SU {@link Task} that fires every minute and removes any
 * {@link TempRankEntry} entries that have expired.
 */
public class TempRankTask extends Task {

    public TempRankTask() {
        super(Ticks.MINUTE);
    }

    @Override
    public void execute(Universe universe) {
        TempRankManager.get()
            .checkExpired();
    }
}
