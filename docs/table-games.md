# Card table games

An unused card table opens a game picker: UNO, Klondike solitaire or no-limit Texas hold'em.
An occupied table opens its existing game; it cannot be replaced through the picker.
UNO keeps its previous rules, animations, spectators and packet format. `/cards` lists
all local table games and provides clickable spectator links.

## Klondike

Single player, draw one, unlimited stock redeals in their original order. Drag a card
or a descending run onto a valid pile; the hovered destination is highlighted. Invalid
drops return smoothly, and accepted drops wait for server confirmation without editing
the client snapshot. Click-to-select/move remains available. Right-click a card pile to move its top card
to an eligible foundation. Foundations ascend by suit from ace to king; tableau runs
descend in alternating colours and empty columns require a king. Exposed covered cards
flip automatically. Undo retains up to 32 moves, including draws and stock recycling.

Ranks are larger, and suits use filled geometric shapes on both solitaire and poker cards,
so their visibility does not depend on the resource pack's small Unicode suit glyphs.

Other players may watch the public board. Stock order and covered tableau identities
stay on the server. A player leaving the table ends their solitaire session.

## Hold'em

Two to four seats, optional bots, 1000 play chips per entrant, blinds 10/20. Chips exist
only within the table's in-memory game, with no item, wallet or external payout support.
The dealer moves between hands; the heads-up dealer posts the small blind and acts first
preflop, while the big blind acts first after the flop.

Fold, check/call, and raise-to-total are supported. A full raise must match at least the
previous full raise; short all-ins do not reopen raising unless accumulated increases
reach that amount. Players can call with all remaining chips. Main/side pots only pay
eligible players, tied pots split and odd chips go clockwise after the dealer. Uncalled
excess is returned. Burn cards precede flop, turn and river. Best five-card hands out of
seven are compared without suit tie-breakers. Poker rule reference:
[PokerStars hold'em rules](https://www.pokerstars.com/poker/learn/lesson/texas-holdem-rules/).

Humans have 30 seconds per turn: timeout checks when possible, otherwise folds. Bots
think for 1.5 seconds and use their own cards only. Closing the GUI keeps the seat;
walking more than eight blocks away, leaving the table, or logging out releases it.
Departing players fold, except an all-in hand remains eligible until its pot is settled.
The host can start the next hand and add/remove bots between hands. If fewer than two
players still have chips, leave and reopen the free table for a new game.

Only a player's own hole cards are sent during play. Spectators receive none; showdown
reveals nonfolded hands only. Pot ownership never depends on a client's claim of its hand.

## Lifecycle and deployment

All moves run on the server thread, validate table membership and distance, and reject
stale revisions. A player can occupy only one game at a time. Table removal, restart or
the last human's departure ends the in-memory session. No game world/player save migration
is required.

New ordinary-deck state/action messages use WorldTravel channel IDs 21/22. The compatibility
suffix is now `+cointnet.2`: update all nodes and the launcher distribution together. A
`+cointnet.1` client is not compatible with this build, including Hub. Do not deploy only
the server half of this feature.

## Checks

`tools/tests/card-tests.gradle` supplies rule and headless GUI tests. New coverage includes
deal uniqueness, hidden-card masking, draw/redeal order, undo, legal runs/foundations,
hand evaluation, heads-up order, short all-ins, side pots, split/odd chips, turn preservation
on disconnect, private packet round trips, random hands with chip conservation, and bots.
GUI checks cover controls at 320×240, 640×360 and 960×540. They do not draw OpenGL frames.

The separate `tools/tests/table-forge/forge.gradle` probe passed 45 checks in a local
dedicated Forge server with test players and an embedded network connection. It covers
real picker/state delivery, solitaire spectators and stale moves, session generations,
UNO selection/rejoin, private poker hands, wrong-turn/spectator actions, host handoff and
table removal. This is server integration verification, not an in-game visual playtest.

Before production, use two clients to verify the picker, solitaire moves/undo/redeal,
poker betting and showdown, bots, remote `/cards` spectators, host departure, walking
away, breaking the table, and both GUIs at the actual GUI scale. Recheck UNO's rule
toggles, jump-in and 0/7 animations. Games do not survive restarts by design.
