# Joining an existing Endgame team from Alpha

An Alpha player may accept an explicit ServerUtilities invitation on Endgame. The team selector's
`/team join <team-id>` command starts a personal migration proposal instead of changing membership
immediately. Open teams without an explicit invitation do not authorize migration. Other guest commands
and guest protections keep their existing restrictions.

The destination verifies the player's authoritative home and the destination owner's home. Alpha creates
the proposal from its saved player profile. Only the joining player can approve it; the old team's leader
cannot approve it on their behalf. The player can reopen a pending proposal with `/migrate ui`.

After approval, both nodes run preflight checks. A successful check starts the existing 15-second warning,
disconnects only the migrating player wherever they are connected, and waits for them to be offline before
exporting. The existing move journal, snapshots, backups, import validation and source retirement perform
the transfer. Membership changes only during import; the authoritative home changes after source retirement.
The destination invitation and owner are checked again before installing membership. An unrelated existing
destination team is a conflict, even if its ID happens to match the source team.

Only personal data travels: no source personal worlds, stations, claimed chunks or whole BetterQuesting
party. The player joins the destination party. The previous base and its claims remain on Alpha. If the
departing player owned the old team, another member takes ownership. If nobody remains, the retired source
profile stays as the owner so ServerUtilities keeps the team and its claims valid after a restart. This
source ownership record does not change the player's new home or grant guest access to the old base.

Shared external data retains its existing safeguards. In particular, a VendingMachine wallet shared with
players who are not migrating is a preflight conflict: it is never silently split, discarded or duplicated.
Canceling, expiring or rejecting a proposal leaves the player's home and membership unchanged. The player
may accept the invitation again to create a fresh proposal. A running or failed journaled move must be
inspected with the existing move-status/recovery commands rather than retried through a new invitation.

## Deployment and checks

Update CointCore on Alpha, Endgame, Hub and the clients together during the planned restart. All nodes
must use the same shared migration directory and have migration enabled. The configured quest source and
target node IDs also define the permitted personal joining direction (normally `alpha` to `endgame`).
No new permission that opens all team commands to guests is needed.

1. Use an Alpha test account and an existing Endgame team. Invite it on Endgame and accept through the SU
   team selector. Check that a personal confirmation opens and that no membership or home changes yet.
2. Cancel the proposal. Verify Alpha still holds the personal inventory and old team/base. Accept the
   invitation again and test reopening the pending confirmation with `/migrate ui`.
3. Confirm. Verify preflight happens before disconnecting, then the warning lasts 15 seconds. Another
   member of the Alpha team must remain connected and keep their inventory, base, claims and world binding.
4. Reconnect after completion. Verify Endgame is now home, the invited team and quest party are assigned,
   and personal inventory, equipment, quest progress, backpacks and supported personal balances survived.
   Alpha must apply its usual guest restrictions when the player visits it.
5. Try an uninvited/open team, revoke an invitation before confirmation, or change the destination owner.
   These cases must not install membership or reassign home. Check the preflight report ID in the message
   and `[TeamJoin]`, `[MigrationConsent]`, `[TeamMove]` messages in the server logs for failures.
6. Repeat with a non-leader and a player without an SU team. Verify the old leader cannot approve their
   proposal. Retest ordinary whole-team migration separately; it still requires the team's leader.

Local validation uses an isolated Forge server with real SU teams, command events, consent files and
membership operations. Database routing is mocked in that probe; it is not a live multi-node migration
or a production test. Those checks require the deployed cluster and test accounts.
