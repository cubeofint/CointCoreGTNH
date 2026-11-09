package coint.cluster;

import java.util.List;

/**
 * A change to a trade made under the row's lock. Runs on the database worker: it may use only the row, not the game.
 * Returns false to leave the row as it was; deliveries it adds are written in the same transaction.
 */
@FunctionalInterface
public interface TradeMutation {

    boolean apply(TradeRow row, List<TradeDelivery> deliveries);
}
