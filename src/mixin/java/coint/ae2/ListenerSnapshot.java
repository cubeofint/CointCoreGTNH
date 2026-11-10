package coint.ae2;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Walks a copy of a listener map, so listeners added or removed during the walk do not break it; {@link #remove()}
 * removes the current one from the real map.
 */
public final class ListenerSnapshot<K, V> implements Iterator<Entry<K, V>> {

    private final Map<K, V> live;
    private final Iterator<Entry<K, V>> it;
    private Entry<K, V> last;

    public ListenerSnapshot(Map<K, V> live) {
        this.live = live;
        List<Entry<K, V>> copy = new ArrayList<>(live.size());
        for (Entry<K, V> e : live.entrySet()) {
            copy.add(new AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue()));
        }
        this.it = copy.iterator();
    }

    @Override
    public boolean hasNext() {
        return it.hasNext();
    }

    @Override
    public Entry<K, V> next() {
        last = it.next();
        return last;
    }

    @Override
    public void remove() {
        if (last != null) {
            live.remove(last.getKey());
            last = null;
        }
    }
}
