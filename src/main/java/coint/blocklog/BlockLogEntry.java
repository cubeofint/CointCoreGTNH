package coint.blocklog;

/** One record to be written: who did what where. */
final class BlockLogEntry {

    static final int BREAK = 0, PLACE = 1, EXPLODE = 2, TAKE = 3, PUT = 4;

    final long at;
    final String node;
    final int dim, x, y, z, action;
    /** Player name, or "#" and what it was when not a player (an explosion, a creeper). */
    final String actor;
    /** Item registry name, or "b:" and the block's when the block has no item. */
    final String what;
    final int meta, amount;

    BlockLogEntry(String node, int dim, int x, int y, int z, int action, String actor, String what, int meta,
        int amount) {
        this.at = System.currentTimeMillis();
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
