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
device may make that control unavailable. The optional HUD explicitly labels the
track and artist, shows playback state, elapsed/total time and progress. Long
track/artist names scroll. The menu hotkey is unassigned until configured in
Minecraft Controls. `/spotify` works without any server permission.

Choose **Edit widget** in `/spotify` to position and resize it. Drag the panel to
move it, drag its bottom-right corner or scroll over it to change size, and click
Done to save. Reset restores the default top-right position and scale. Cancel or
Escape discards the editor's changes. Position uses relative screen coordinates,
so the widget stays on screen when resolution or Minecraft GUI scale changes.
The editor shows a sample track when no media is connected; opening the editor
never enables Spotify or starts a helper. Layout and scale are local preferences.

With chat open, the widget stays visible and becomes a mini-player. Drag its
header/body to move it, drag its corner to resize, or scroll over the body to
change scale. Geometry is saved on release; chat input and scrolling outside the
widget retain their normal behavior. The previous/play/next buttons, progress
slider, application-volume slider and speaker mute toggle control Spotify. Scroll
over the volume area for 5% steps. Shuffle and repeat are enabled only if Spotify's
Windows session advertises them. Repeat cycles off, track, playlist.

Album art comes from the local Windows media thumbnail, never a separate web
request. The bridge sends a bounded, hashed image on track changes. Image decoding
and downsampling run on the worker; the render thread maintains only one reusable
128px texture and releases it on opt-out or loss of the media session. Missing
art uses a placeholder and does not stop playback controls. Closing chat commits
an active slider/drag, and changing tracks while dragging seek cancels that seek.

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
the client to check preference persistence; move/resize the widget and change
resolution/GUI scale; cancel an edit; connect to another node; launch a
dedicated server to confirm no client media code is loaded.

API references:
- https://learn.microsoft.com/en-us/uwp/api/windows.media.control.globalsystemmediatransportcontrolssessionmanager
- https://learn.microsoft.com/en-us/uwp/api/windows.media.control.globalsystemmediatransportcontrolssession
- https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nn-audioclient-isimpleaudiovolume
