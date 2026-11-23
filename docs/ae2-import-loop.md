# AE2 import loop progress guard

The two `PartBaseImportBus.doBusWork` loops repeat `importStuff` while the
remaining transfer budget is positive. An implementation returning `false`
(continue) without decreasing `itemToSend` can keep one server tick busy
indefinitely. A thread dump then repeatedly shows an import bus inserting into
network storage, including the simulated extraction used for priority checks.

`MixinImportBusProgressGuard` wraps the two loop calls in AE2 rv3-beta-1074-GTNH.
It preserves the original stop result and every successful operation. Only a
continue result with an unchanged/increased positive budget becomes a stop.
There is no time quota, transfer cap, skipped machine tick or forced chunk
unloading. Exceptions propagate normally. A warning reports the dimension and
bus coordinates, limited to once per minute per bus.

The reported production thread dump identifies the import path, but cannot show
the remaining budget. Deployment must confirm whether the warning appears and
the hang disappears. If the server is still stuck with no warning, collect a new
thread dump; do not assume that every long-running import is a no-progress loop.

## Sampling a slow operation

The follow-up production dump contains the guard's wrapper, but is inside
`NetworkInventoryHandler.injectItems` called by `PartImportBus.canInsert`.
The wrapper cannot stop an operation that has not returned. This dump confirms
the insertion/simulation path; it does not establish a particular bus, transfer
budget, recursive cycle or throughput problem.

`general.aeImportDiagnostics=true` enables `AEImportWatch` on server startup.
For an import taking over three seconds, a separate daemon samples the import
and writes a warning, at most once per fifteen seconds for that operation:

- dimension, block coordinates and part side;
- initial and last observed transfer budget;
- attempted and completed `importStuff` calls in the two ordinary import loops;
- simulated and real network insertion/extraction calls;
- current/maximum network call depth and maximum handler count;
- a short stack of the server thread.

The counters help distinguish many completed small transfers from one expensive
storage operation. For an ore-filter import, the ordinary-loop budget/call
counters can remain `-1`/zero; network counters and the stack are still sampled.
The background thread reads diagnostic fields and Java stack frames only. It
does not access Minecraft worlds, storage objects, inventories or live AE2
lists. The mixins delegate each original operation exactly once and preserve
its result and exception. No work is cancelled, deferred, throttled or cached.
Disable the setting and restart after collecting the required evidence to
remove the temporary counters' overhead.

Local verification uses the cached AE2 rv3-beta-1074-GTNH binary. A synthetic
subnet traversal test exercises repeated queries, but does not reproduce the
reported production hang. A separate Forge smoke test verifies both network
wrappers, samples a deliberately blocked simulation while the server thread is
unavailable, and checks the original result, exceptions, scope cleanup and log
rate limit. Production cause/fix confirmation still requires the diagnostic
report from the affected server.
