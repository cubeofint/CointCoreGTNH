# Cluster admission and server-list status

All nodes must run the updated build. Stop all nodes before the first rollout so that
players cannot remain connected through an older node without a session lease.
Back up the cluster database before upgrading. Start Alpha first to migrate schema
version 14, then start Hub and Endgame. No new configuration keys are required.

The server-list ping advertises the union of visible players and the sum of
`server.properties` player capacities of currently available nodes. Local capacity
is read directly; remote data follows the existing heartbeat/timeout. Offline nodes
do not contribute slots. Reserved slots and SU team limits are not added. Actual
admission limits remain local. Hover samples contain player names, with vanished
players excluded and duplicate names folded case-insensitively.

Before Forge creates a player, login waits asynchronously for an exclusive UUID
lease in `cluster_login_sessions`. Separate connection tokens prevent an old logout
from deleting a newer session. The initial admission can wait up to eight seconds
for a normal server transfer to finish saving and disconnecting on the old node.
An occupied account is then rejected without kicking the original connection.

Leases renew every ten seconds, expire in the database after 120 seconds, and stop
being usable locally after 60 seconds without confirmation. A watchdog closes an
expired connection even if the tick thread stalls; queued packets are also checked
when the server resumes. Admission fails closed when the database is unavailable.
Renewal failures disconnect affected sessions. After a hard process crash a lease
can remain occupied for up to two minutes. Normal shutdown queues release of its own
tokens after player data has been saved. If database work cannot drain before the
shutdown timeout, expiration is the fallback and a reconnect can also be delayed
by up to two minutes. Failed logout releases are retried while running.

Local completed trades label both participants with the local node ID, matching
the format already used by cross-node trades.

## Deployment checks

- Connect once, then try the same account from another client on the same node and
  on another node: only the original connection should remain playable.
- Run `/server` between Hub, Alpha and Endgame, then reconnect after a normal logout.
- Hover over server-list counts: all visible names should appear. With three live
  nodes configured for 30 players each, the maximum should be 90.
- Complete one local and one cross-node trade; both log participant node IDs.
- Check startup logs for schema or session-query failures before opening access.

Local verification uses a dedicated Forge server and concurrent JDBC tests with
H2 in MySQL compatibility mode. This is not a live multi-node MySQL/client test.
