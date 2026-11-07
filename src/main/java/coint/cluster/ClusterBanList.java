package coint.cluster;

import java.util.Collection;

/** A vanilla ban list (players or IPs), made readable by a mixin. */
public interface ClusterBanList {

    /** A copy of its entries. */
    Collection<Object> cointcore$entries();
}
