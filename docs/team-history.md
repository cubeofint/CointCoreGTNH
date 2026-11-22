# Team history

`/teamhistory` or **Team history / История команды** in the servers menu opens a read-only timeline for the
player's own ServerUtilities team. It works from Alpha, Endgame and Hub after the home server has published
the player's current team. An empty/unavailable state is shown explicitly.

The first version records:

- New highest team epochs, observed at configured BetterQuesting epoch quest completions after successful
  rank assignment. Several members completing the same milestone produce one entry. Joining an advanced
  team, replaying an earlier quest, rank synchronization and importing a world do not invent unlock dates.
- Successfully completed team migrations created with this version. The receiving node adds one entry with
  the original source, destination and initiating actor. Failed/preflight-only operations and older move
  journals do not generate completion entries.

The date in the header is when observation of this team began, **not its founding date**. Existing teams do
not receive reconstructed past achievements. Event times are stored as epoch milliseconds and displayed
in the client's local time zone. Hover a row for its complete title, participant and server. The mouse wheel
scrolls within a page; Older/Newer navigate the history and Refresh returns to the newest page.

## Storage and compatibility

The shared cluster database schema advances from 16 to 17, creating `cluster_team_histories`,
`cluster_team_history` and `cluster_team_history_members`. Existing data/tables are retained. SQL runs on
the existing cluster worker, not the game tick. History access uses the authenticated player UUID and its
current home server; clients cannot request arbitrary team IDs. Offline members are included in membership
updates from the home server. An old source node cannot overwrite a member whose home changed.

SU team `Data.cointcore_history` contains a persistent random identity, milestone deduplication keys and
unsent events. The existing team export/import carries these fields to the destination. SQL upserts use
stable event UUIDs: crash/reconnect/replayed acknowledgement does not duplicate or redate events. Entries
stay in the SU outbox until SQL commit is confirmed, then only that captured batch is acknowledged.
Keep world and cluster database backups together; the database holds the full acknowledged timeline.

The optional `cointhistory` channel leaves existing packet IDs and `+cointnet.3` unchanged. A server sends
history packets only to clients advertising the channel. Older clients get an update notice when using
the command; a new client's button on an older hub invokes an ordinary unknown command, never an unknown
packet on the existing travel channel. Update clients and nodes to use the new window.

This feature requires the cluster database; standalone worlds show an unavailable state. No website,
Discord messages, sounds, quest completion rules or world/chunk simulation settings are changed.

## Verification and rollout

Local checks: `gradlew --offline -I tools/tests/team-history.gradle testTeamHistory`. These use actual
Minecraft NBT and packet codecs plus JDBC contract/failure doubles; they do not establish live MySQL or
multi-node production confirmation.

Back up worlds and the cluster database before deploying to a test environment, then check:

1. An existing team initially has an empty timeline and an observation date, not a fictitious founding date.
2. Complete a new highest epoch with two team members: one dated entry appears. Replaying the quest does
   not add another. A lower-tier member's older quest must not add a false unlock for an advanced team.
3. Restart the node: entries remain. Open the same history from Hub after home membership publication.
4. Move a disposable team Alpha → Endgame. The old entries remain, and one completed-migration entry
   appears. A rejected move should leave no completion entry.
5. Remove an offline member, wait for home membership publication and verify the removed account cannot
   continue reading the old team's history. Recreating a team with the same textual name starts a new history.
6. Temporarily interrupt the test database: the GUI shows an error, gameplay continues, and saved SU outbox
   entries publish once after recovery. Verify outbox replay after a test restart as well.
7. Check scrolling, Older/Newer, refresh, GUI scaling and older-client/hub compatibility.

An actual MySQL schema upgrade and in-game multi-node checks still require staging verification.
