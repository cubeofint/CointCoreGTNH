# Optional Spotify controls

The client command `/spotify` opens local music controls. The integration is **off
by default**. Enable it in that screen, open the Spotify desktop app and start a
song. No Spotify API account, application key or Premium API access is required:
the controls use Spotify's existing Windows media session. This is a controller
for the desktop player, not an embedded streaming client or playlist browser.

Requirements: Windows 10 version 1809 or newer / Windows 11, Windows PowerShell
5.1, and Spotify desktop with a registered Windows media session. Other operating
systems show an unsupported message. Spotify's own account restrictions still
apply. The web player is not selected, and there is no fallback to controlling
another app when Spotify is absent.

The menu provides play/pause, previous/next, seeking by clicking the progress bar,
Spotify application volume in steps of 5%, and a separate HUD toggle. Volume is
the Spotify Windows mixer level on the default multimedia output; a custom output
device may make that control unavailable. The optional HUD shows title, artist
and progress in the top right. The menu hotkey is unassigned until configured in
Minecraft Controls. `/spotify` works without any server permission.

Settings persist only in `config/cointcore-spotify.cfg` on that client. With
`enabled=false` no worker, external helper, polling or HUD is started. Enabling
starts a single hidden, bundled PowerShell helper with no profile. The helper
uses Windows media APIs and Core Audio for Spotify only, has no network access
code, and receives fixed commands through a private process pipe. It exits when
disabled or when Minecraft exits. No title, listening history or setting is sent
to a Minecraft server or Discord. Server-side code does not load the integration.

If Windows blocks the helper or media APIs, the menu reports an error; the game
continues. Disable and re-enable the integration to retry. Do not change system
PowerShell policy or antivirus settings as a workaround.

Manual checks after a build: off by default; enable with no Spotify running;
start Spotify and observe track/artist; pause/skip/seek; change app volume without
changing Minecraft volume; hide HUD; disable and confirm the helper exits; restart
the client to check preference persistence; connect to another node; launch a
dedicated server to confirm no client media code is loaded.

API references:
- https://learn.microsoft.com/en-us/uwp/api/windows.media.control.globalsystemmediatransportcontrolssessionmanager
- https://learn.microsoft.com/en-us/uwp/api/windows.media.control.globalsystemmediatransportcontrolssession
- https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nn-audioclient-isimpleaudiovolume
