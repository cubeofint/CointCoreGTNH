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
