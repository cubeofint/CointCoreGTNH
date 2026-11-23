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

## Confirmed high-speed batch problem

The production diagnostic report then showed a positive transfer budget of
67,108,865 items, 54,966 completed imports after 33 seconds, and exactly 64 items
consumed from the budget by each import. Network depth remained at two while
the number of storage simulations grew into millions. The work was progressing:
AE2 was splitting one high-speed bus operation into over a million stack-sized
transfers, repeating its source/destination checks and priority scans for each.

`MixinPartImportBusBulkTransfer` changes the batch ceiling in
`calculateMaximumAmountToImport` for the exact stock `AdaptorIInventory` class.
It uses the bus's remaining budget, already limited by installed upgrades and
available energy in `doBusWork`. AE2's existing source simulation, destination
capacity checks, fuzzy/exact filtering, actual extraction, powered insertion,
and stop results still run. Unknown adaptor subclasses retain their original
64-item batches. An inventory whose occupied slots reject returning items also
retains the old batches, because it cannot safely receive a refused bulk stack.
There is no tick quota, sleep, delayed machine work or reduction in the bus's
configured throughput.

Two return-path details matter with large batches:

- Once items have been extracted, do not truncate their stack if energy falls.
  Native `Platform.poweredInsert` checks energy again and returns the uninserted
  remainder; truncating first would silently delete the unpaid portion.
- Record item types that already occupied oversized source stacks. If the
  normal return only accepts a vanilla stack of such an item, return the
  remainder through AE2's inventory-limit-aware adaptor constructor. This keeps
  the source's own slot validation, inventory limit and dirty notifications.
  Ordinary items do not acquire oversized-stack permission this way.

The return context is cleared/restored in `finally`, including errors and nested
calls. The progress guard and diagnostic sampler remain available.

Verification on a separate Forge server with AE2 rv3-beta-1074-GTNH:

- Before: 1,048,577 identical items required 16,385 actual insertions and 49,155
  insertion simulations. After: one actual insertion and three simulations.
- The production-sized 67,108,865-item budget also completes in one insertion
  when source stock, energy and destination space permit it.
- 64 checks exercise the real `doBusWork`/`importStuff` implementations: quantity
  and NBT conservation, mixed/damaged items, exact and fuzzy filters, no upgrades,
  limited/zero/fractional power, full/partial destinations, a destination changing
  acceptance after simulation, large remainder restoration, and power loss
  during extraction. Custom/extract-only source fallback is checked too.

These are isolated regression results, not a production TPS measurement. Verify
startup and the affected bus on the deployed server before declaring the
production hang resolved.

### Infinity Chest integration verification

A read-only inspection of the affected saved chunk identified
`Avaritiaddons:InfinityChest` above the import bus. The installed client's
Avaritiaddons 1.9.6-GTNH binary and the matching cached sources were inspected;
the fix was then tested against that version's actual `TileEntityInfinityChest`
on a separate Forge server, not only a stand-in inventory.

The integration test passed 58 checks. Native `InventoryAdaptor.getAdaptor`
selects the reviewed `AdaptorIInventory`, and a 67,108,865-item budget finishes
in one insertion. Cases include a remaining oversized source stack, limited
power, full/partial destination capacity, destination acceptance changing
after simulation, returning to empty and already-occupied slots, distinct item
NBT, an empty chest, and saving/reloading the real chest's large `intCount`
values. All checked item totals, tags and energy accounting are preserved.
The test loads Avaritiaddons 1.9.6, with cached WanionLib 1.10.0 and Avaritia
1.100 as support dependencies. It does not copy or modify production worlds.
