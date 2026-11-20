# Cluster status ping snapshots

Minecraft 1.7.10 replaces `MinecraftServer`'s shared `ServerStatusResponse` player counts
with local online/capacity every five seconds. CointCore publishes the cluster counts
once per second. Returning the shared response to external pings therefore allowed a
local Hub reply such as `0/50` between cluster refreshes. A queued packet also retained
that shared response until Netty serialized it, which widened the race.

`ClusterPingOnline` now retains the completed cluster `PlayerCountData` separately and
uses it when copying a response for each external/proxy ping. Loopback pings still use
the local snapshot for MCSManager. MOTD, protocol, icon and player hover samples are
preserved. There are no database queries or player-list reads on the network path.

The first available snapshot is captured immediately instead of waiting twenty ticks.
Genuine zero online and capacity changes continue to be advertised; this does not pin
the last nonzero count or assume a fixed cluster capacity.

## Verification

The regression test first reproduced the old failure after a native count reset. It
checks public/local replies, queued-packet isolation, sample/metadata preservation,
real departures, empty clusters, changed capacity and standalone behavior:

```text
gradlew -I tools/tests/server-ping.gradle testServerPing
```

The separate Forge probe invokes the transformed `NetHandlerStatusServer`, captures its
queued packet, resets the native status again, and checks actual wire serialization.
It uses an isolated local world under `run/ping-regression-smoke`:

```text
gradlew -I tools/tests/ping/forge.gradle runServer25
```

## Deployment

For the current site's GTNH monitor, update and restart Hub first. The ping correction
does not change CointCore packets or its compatibility suffix; Hub accepts existing
clients with that same protocol. The other nodes can receive the same server fix with
their next coordinated client/server update.

After Hub is ready, replace only the GTNH website cache entry using a new bridge reply.
On coint-proxy-d2:

```sh
cd /var/www/main-site
php artisan tinker --execute='$s=\Azuriom\Models\Server::findOrFail(2); $data=$s->bridge()->getServerData(); if($data===null){dump("Hub unavailable; cache unchanged");}else{$s->updateData($data); dump(\Illuminate\Support\Arr::only($data,["players","max_players"]));}'
```

This updates the existing monitoring cache without changing RCON credentials, shop,
database rows, ranks or world data. The homepage refresh patch remains useful for live
updates, but cannot correct a wrong ping reply at its source. Verify the public website
over several minutes; a correctly updated Hub should no longer alternate between local
and aggregate capacity while all nodes remain online.
