package coint.blocklog;

/** One record read back. */
final class BlockLogRow {

    final long at;
    final String node;
    final int dim, x, y, z, action;
    final String actor, what;
    final int meta, amount;

    BlockLogRow(long at, String node, int dim, int x, int y, int z, int action, String actor, String what, int meta,
        int amount) {
        this.at = at;
        this.node = node;
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.action = action;
        this.actor = actor;
        this.what = what;
        this.meta = meta;
        this.amount = amount;
    }
}
