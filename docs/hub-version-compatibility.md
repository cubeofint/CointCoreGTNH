# Hub version compatibility

Install the first build with this change on all nodes and in the launcher client.
Older CointCore builds still perform Forge's exact version check and cannot take
part in this compatibility scheme.

CointCore advertises a `+cointnet.1` suffix in its Forge mod version. The suffix
identifies the network protocol; the preceding version remains the Git build.

- A hub accepts different CointCore builds with the same protocol suffix.
- Alpha, Endgame and other game servers require the complete version to match.
- The hub is identified from `cluster.enabled`, `cluster.nodeId` and
  `cluster.hubNodeId`. No additional configuration is required.
- Other mods retain their own Forge compatibility checks. This does not permit
  arbitrary versions of GregTech, AE2, or the modpack.
- A failed Forge mod check tells the player to restart the pack through the
  launcher and retains Forge's rejected-mod list underneath.

For ordinary server fixes, rebuild CointCore, update the game nodes and launcher,
and leave the hub on its installed build. Players running the previous build can
wait on the hub, but must relaunch before entering an updated game node.

Before making an incompatible change to a packet ID, payload layout, or
GUI/container message, bump `NetworkCompatibility.PROTOCOL_SUFFIX`. Both sending
and receiving directions, including packets initiated by the client, must remain
compatible within one protocol. Such changes require updating the hub too.

Network compatibility does not make newly implemented features available on an
older hub and does not replace database-schema or configuration compatibility.
