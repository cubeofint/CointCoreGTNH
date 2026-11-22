# Admin chat

`/adminchat <message>` (aliases `/a` and `/ac`) sends to online staff on the same node and across the GTNH
cluster. Staff can also select the red **[A]** chat tab and type normally. Explicit `/commands`, `!global`
and `$trade` messages retain their explicitly chosen destination.

The permission is `cointcore.adminchat`, OP by default, matching the ATM permission name. Grant it to the
appropriate ServerUtilities staff ranks, for example:

```text
[admin]
cointcore.adminchat: true
```

The server checks permission when sending and again for each recipient, including remote delivery. The tab
appears only after the server grants access. Existing mutes still prevent sending. Staff can use the command
while visiting another node. Console/RCON can also send messages.

`chat.adminChatEnabled` defaults to true and can be reloaded. Permission changes are reflected in the tab
within about one second. If access is revoked while the admin tab is selected, that selection remains locked
to `/adminchat` and the server rejects sending: unfinished private text must never fall through to local chat.
Selecting another tab explicitly changes the destination.

Messages use the normal timestamp/rank/name order with a red A channel marker. Remote messages include
their originating node before the channel marker. They are not published to public chat, the Discord bridge,
or `/spy`. Server consoles retain the admin-chat line.

## Transport compatibility

The optional `cointadmin` channel communicates tab access. The ADMIN chat ordinal is appended; the four
existing message ordinals retain their values. Staff using an older compatible hub client receive ordinary
chat components instead of an unknown channel ordinal. No existing packet layout or protocol suffix changes.

Cluster messages use the existing DM envelope addressed to the reserved invalid player name
`$coint-adminchat`. Updated nodes intercept it before public chat processing and check recipient permissions.
Older nodes treat it as an undeliverable DM, never a global message. Upgrade all three nodes for full staff
chat delivery. Admin relay remains independent of the public `cluster.chatEnabled` switch.

## Checks before rollout

1. A normal player has no [A] tab, cannot use `/adminchat`, `/a` or `/ac`, and receives none of the messages.
2. Two staff on one node receive one message each via the command and via the red tab; ALL also shows it.
3. Repeat with staff on Alpha, Endgame and Hub. Check remote node labels, delivery while visiting, and no
   duplicate local copy, public chat, Discord or spy copy.
4. Revoke a staff member's permission while they are typing in [A]. Their attempted message must be rejected,
   not sent to local chat. Check permission grant, disconnect/reconnect and mute enforcement.
5. Check the full input length with ordinary and longer-chat settings; `/adminchat ` must fit inside the limit.
6. Disable tabs: permission-restricted command delivery still works. Disable admin chat: sending is rejected.
7. Test older compatible hub clients/nodes: ordinary players never receive the reserved DM payload.

Build/unit checks are local. Actual three-node delivery and client rendering require in-game verification.
