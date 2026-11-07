package coint.cluster;

import java.util.Date;

/** A vanilla ban entry, made readable by a mixin. */
public interface ClusterBanEntry {

    /** The banned one: a GameProfile, or an IP as a string. */
    Object cointcore$key();

    Date cointcore$created();

    String cointcore$source();
}
