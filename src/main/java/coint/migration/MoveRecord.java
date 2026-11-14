package coint.migration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Durable identity of a move; no Minecraft objects or numeric team UIDs cross servers. */
public final class MoveRecord {

    public String id, source, target, team, actor;
    /** Non-null only for a single player joining an existing destination team. No base travels with him. */
    public String joinTeam, joinOwner;

    public boolean joining() {
        return joinTeam != null;
    }

    public long createdAt = System.currentTimeMillis();
    public List<String> players = new ArrayList<>();
    public List<String> names = new ArrayList<>();
    public List<Integer> dimensions = new ArrayList<>();
    public List<MigrationStations.Station> stations = new ArrayList<>();
    /** {dimension, chunkX, chunkZ, originallyForced}. Includes personal and external claims. */
    public List<int[]> claims = new ArrayList<>();

    public void validate() {
        if (!UUID.fromString(id)
            .toString()
            .equals(id) || !node(source)
            || !node(target)
            || source.equalsIgnoreCase(target)
            || team == null
            || team.isEmpty()
            || players.isEmpty()
            || players.size() != names.size()) {
            throw new IllegalArgumentException("Invalid move identity");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < players.size(); i++) {
            String player = players.get(i);
            if (!UUID.fromString(player)
                .toString()
                .equals(player) || !seen.add(player)
                || !names.get(i)
                    .matches("[A-Za-z0-9_]{1,16}"))
                throw new IllegalArgumentException("Invalid move player");
        }
        Set<Integer> dims = new HashSet<>();
        for (int dim : dimensions) {
            if (dim <= 1 || !dims.add(dim)) throw new IllegalArgumentException("Invalid personal dimension");
        }
        Set<Integer> stationIds = new HashSet<>();
        Set<Integer> shipIds = new HashSet<>();
        for (MigrationStations.Station station : stations) {
            if (station == null || !dims.contains(station.id)
                || !stationIds.add(station.id)
                || station.owner == null
                || names.stream()
                    .noneMatch(n -> n.equalsIgnoreCase(station.owner)))
                throw new IllegalArgumentException("Invalid station ownership");
            if (station.shipId < 0
                || (station.shipId > 0 && (!shipIds.add(station.shipId) || !players.contains(station.ownerUuid)
                    || station.parent == null
                    || station.parent.isEmpty())))
                throw new IllegalArgumentException("Invalid mothership ownership");
        }
        if (joining()) {
            if (joinTeam.isEmpty() || players.size() != 1
                || !dimensions.isEmpty()
                || !claims.isEmpty()
                || joinOwner == null
                || !UUID.fromString(joinOwner)
                    .toString()
                    .equals(joinOwner))
                throw new IllegalArgumentException("Invalid player join move");
        } else if (dims.isEmpty()) throw new IllegalArgumentException("Move has no personal dimensions");
        seen.clear();
        for (int[] c : claims) {
            if (c == null || c.length != 4 || (c[3] != 0 && c[3] != 1) || !seen.add(key(c))) {
                throw new IllegalArgumentException("Invalid move claim");
            }
        }
    }

    private static boolean node(String node) {
        return node != null && node.matches("[A-Za-z0-9_-]{1,64}");
    }

    public static String key(int[] c) {
        return c[0] + ":" + c[1] + ":" + c[2];
    }
}
