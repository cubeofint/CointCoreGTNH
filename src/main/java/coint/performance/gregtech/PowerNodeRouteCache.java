package coint.performance.gregtech;

import gregtech.api.graphs.Node;
import gregtech.api.graphs.NodeList;
import gregtech.api.graphs.PowerNode;
import gregtech.api.graphs.PowerNodes;
import gregtech.api.graphs.consumers.ConsumerNode;
import gregtech.api.graphs.paths.PowerNodePath;

public final class PowerNodeRouteCache {

    private static final ThreadLocal<CursorPool> CURSORS = new ThreadLocal<CursorPool>() {

        @Override
        protected CursorPool initialValue() {
            return new CursorPool();
        }
    };

    private PowerNodeRouteCache() {}

    public static long powerNode(Node current, Node previous, NodeList consumers, Node[] consumerNodes, int counter,
        long voltage, long maxAmps) {
        if (!(current instanceof PowerNode) || previous != null
            || consumers == null
            || consumerNodes == null
            || counter != 0
            || maxAmps <= 0) {
            return PowerNodes.powerNode(current, previous, consumers, voltage, maxAmps);
        }

        CursorPool pool = CURSORS.get();
        Cursor cursor = pool.acquire(consumerNodes, counter);
        try {
            return powerNode(current, previous, cursor, voltage, maxAmps);
        } finally {
            pool.release(cursor);
        }
    }

    private static long powerNode(Node current, Node previous, Cursor consumers, long voltage, long maxAmps) {
        long ampsUsed = 0;
        ConsumerNode consumer = asConsumer(consumers.getNode());
        int loopProtection = 0;

        while (consumer != null) {
            int targetValue = consumer.mNodeValue;
            if (targetValue < current.mNodeValue || targetValue > current.mHighestNodeValue) {
                int side = cache(current).cointcore$getParentSide();
                if (side >= 0) {
                    Node next = current.mNeighbourNodes[side];
                    if (next.mNodeValue == consumer.mNodeValue) {
                        ampsUsed += processNodeInject(current, consumer, side, maxAmps - ampsUsed, voltage);
                        consumer = asConsumer(consumers.getNextNode());
                    } else {
                        if (previous == next) return ampsUsed;
                        ampsUsed += processNextNode(current, next, consumers, side, maxAmps - ampsUsed, voltage);
                        consumer = asConsumer(consumers.getNode());
                    }
                }
            } else {
                int side = cache(current).cointcore$getHigherSide(targetValue);
                if (side >= 0) {
                    Node next = current.mNeighbourNodes[side];
                    if (next.mNodeValue > current.mNodeValue && next.mNodeValue < targetValue) {
                        if (next == previous) return ampsUsed;
                        ampsUsed += processNextNodeAbove(current, next, consumers, side, maxAmps - ampsUsed, voltage);
                        consumer = asConsumer(consumers.getNode());
                    } else if (next.mNodeValue == targetValue) {
                        ampsUsed += processNodeInject(current, consumer, side, maxAmps - ampsUsed, voltage);
                        consumer = asConsumer(consumers.getNextNode());
                    }
                }
            }

            if (maxAmps - ampsUsed <= 0) return ampsUsed;
            if (loopProtection++ > 20) throw new NullPointerException("infinite loop in powering nodes ");
        }

        return ampsUsed;
    }

    private static long powerNodeAbove(Node current, Node previous, Cursor consumers, long voltage, long maxAmps) {
        long ampsUsed = 0;
        int loopProtection = 0;
        ConsumerNode consumer = asConsumer(consumers.getNode());

        while (consumer != null) {
            int targetValue = consumer.mNodeValue;
            if (targetValue > current.mHighestNodeValue || targetValue < current.mNodeValue) return ampsUsed;

            int side = cache(current).cointcore$getHigherSide(targetValue);
            if (side >= 0) {
                Node next = current.mNeighbourNodes[side];
                if (next.mNodeValue > current.mNodeValue && next.mNodeValue < targetValue) {
                    if (next == previous) return ampsUsed;
                    ampsUsed += processNextNodeAbove(current, next, consumers, side, maxAmps - ampsUsed, voltage);
                    consumer = asConsumer(consumers.getNode());
                } else if (next.mNodeValue == targetValue) {
                    ampsUsed += processNodeInject(current, consumer, side, maxAmps - ampsUsed, voltage);
                    consumer = asConsumer(consumers.getNextNode());
                }
            }

            if (maxAmps - ampsUsed <= 0) return ampsUsed;
            if (loopProtection++ > 20) throw new NullPointerException("infinite loop in powering nodes ");
        }

        return ampsUsed;
    }

    private static long processNextNode(Node current, Node next, Cursor consumers, int side, long maxAmps,
        long voltage) {
        if (current.locks[side].isLocked()) {
            consumers.getNextNode();
            return 0;
        }

        PowerNodePath path = (PowerNodePath) current.mNodePaths[side];
        PowerNodePath selfPath = (PowerNodePath) current.mSelfPath;
        long voltageLoss = 0;

        if (selfPath != null) {
            voltageLoss += selfPath.getLoss();
            selfPath.applyVoltage(voltage, false);
        }

        path.applyVoltage(voltage - voltageLoss, true);
        voltageLoss += path.getLoss();
        long amps = powerNode(next, current, consumers, voltage - voltageLoss, maxAmps);
        path.addAmps(amps);
        if (selfPath != null) selfPath.addAmps(amps);
        return amps;
    }

    private static long processNextNodeAbove(Node current, Node next, Cursor consumers, int side, long maxAmps,
        long voltage) {
        if (current.locks[side].isLocked()) {
            consumers.getNextNode();
            return 0;
        }

        PowerNodePath path = (PowerNodePath) current.mNodePaths[side];
        PowerNodePath selfPath = (PowerNodePath) current.mSelfPath;
        long voltageLoss = 0;

        if (selfPath != null) {
            voltageLoss += selfPath.getLoss();
            selfPath.applyVoltage(voltage, false);
        }

        path.applyVoltage(voltage - voltageLoss, true);
        voltageLoss += path.getLoss();
        long amps = powerNodeAbove(next, current, consumers, voltage - voltageLoss, maxAmps);
        path.addAmps(amps);
        if (selfPath != null) selfPath.addAmps(amps);
        return amps;
    }

    private static long processNodeInject(Node current, ConsumerNode consumer, int side, long maxAmps, long voltage) {
        if (current.locks[side].isLocked()) return 0;

        PowerNodePath path = (PowerNodePath) current.mNodePaths[side];
        PowerNodePath selfPath = (PowerNodePath) current.mSelfPath;
        long voltageLoss = 0;

        if (selfPath != null) {
            voltageLoss += selfPath.getLoss();
            selfPath.applyVoltage(voltage, false);
        }

        path.applyVoltage(voltage - voltageLoss, true);
        voltageLoss += path.getLoss();
        long amps = consumer.injectEnergy(voltage - voltageLoss, maxAmps);
        path.addAmps(amps);
        if (selfPath != null) selfPath.addAmps(amps);
        return amps;
    }

    private static ConsumerNode asConsumer(Node node) {
        return (ConsumerNode) node;
    }

    private static NodeCacheAccess cache(Node node) {
        return (NodeCacheAccess) (Object) node;
    }

    public interface NodeCacheAccess {

        int cointcore$getParentSide();

        int cointcore$getHigherSide(int targetValue);
    }

    private static final class Cursor {

        private Node[] nodes;
        private int counter;

        private void reset(Node[] nodes, int counter) {
            this.nodes = nodes;
            this.counter = counter;
        }

        private Node getNode() {
            return counter < nodes.length ? nodes[counter] : null;
        }

        private Node getNextNode() {
            return ++counter < nodes.length ? nodes[counter] : null;
        }

        private void clear() {
            nodes = null;
            counter = 0;
        }
    }

    private static final class CursorPool {

        private Cursor[] cursors = new Cursor[] { new Cursor(), new Cursor() };
        private int depth;

        private Cursor acquire(Node[] nodes, int counter) {
            if (depth == cursors.length) {
                Cursor[] grown = new Cursor[cursors.length << 1];
                System.arraycopy(cursors, 0, grown, 0, cursors.length);
                for (int i = cursors.length; i < grown.length; i++) grown[i] = new Cursor();
                cursors = grown;
            }
            Cursor cursor = cursors[depth++];
            cursor.reset(nodes, counter);
            return cursor;
        }

        private void release(Cursor cursor) {
            cursor.clear();
            depth--;
        }
    }
}
