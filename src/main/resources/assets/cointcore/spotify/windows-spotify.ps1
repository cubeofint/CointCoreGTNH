# Bundled local bridge. No network access, downloads, credentials or system-wide media keys.
# Reads only Spotify's Windows media session and changes only Spotify's audio sessions.
param([switch]$Probe)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding = New-Object System.Text.UTF8Encoding($false)
$OutputEncoding = [Console]::OutputEncoding

function Publish($State) { [Console]::WriteLine(($State | ConvertTo-Json -Compress)) }

try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading;

public static class CointSpotifyInput {
    public static readonly ConcurrentQueue<string> Commands = new ConcurrentQueue<string>();
    public static volatile bool Closed;
    public static void Start() {
        var thread = new Thread(() => {
            try {
                string line;
                while ((line = Console.ReadLine()) != null) {
                    if (Commands.Count < 16) Commands.Enqueue(line);
                }
            } finally { Closed = true; }
        });
        thread.IsBackground = true;
        thread.Start();
    }
}

public static class CointSpotifyAudio {
    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    private class DeviceEnumerator {}
    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IDeviceEnumerator {
        [PreserveSig] int EnumAudioEndpoints(int flow, uint state, out IntPtr devices);
        [PreserveSig] int GetDefaultAudioEndpoint(int flow, int role, out IDevice device);
    }
    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IDevice {
        [PreserveSig] int Activate(ref Guid iid, uint context, IntPtr parameters,
            [MarshalAs(UnmanagedType.IUnknown)] out object instance);
    }
    [ComImport, Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface ISessionManager {
        [PreserveSig] int GetAudioSessionControl(IntPtr guid, uint flags, out IntPtr control);
        [PreserveSig] int GetSimpleAudioVolume(IntPtr guid, uint flags, out IntPtr volume);
        [PreserveSig] int GetSessionEnumerator(out ISessionEnumerator sessions);
    }
    [ComImport, Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface ISessionEnumerator {
        [PreserveSig] int GetCount(out int count);
        [PreserveSig] int GetSession(int index, [MarshalAs(UnmanagedType.IUnknown)] out object control);
    }
    [ComImport, Guid("BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface ISessionControl {
        [PreserveSig] int GetState(out int state);
        [PreserveSig] int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string name);
        [PreserveSig] int SetDisplayName([MarshalAs(UnmanagedType.LPWStr)] string name, ref Guid context);
        [PreserveSig] int GetIconPath([MarshalAs(UnmanagedType.LPWStr)] out string path);
        [PreserveSig] int SetIconPath([MarshalAs(UnmanagedType.LPWStr)] string path, ref Guid context);
        [PreserveSig] int GetGroupingParam(out Guid grouping);
        [PreserveSig] int SetGroupingParam(ref Guid grouping, ref Guid context);
        [PreserveSig] int RegisterAudioSessionNotification(IntPtr notification);
        [PreserveSig] int UnregisterAudioSessionNotification(IntPtr notification);
        [PreserveSig] int GetSessionIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);
        [PreserveSig] int GetSessionInstanceIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);
        [PreserveSig] int GetProcessId(out uint pid);
        [PreserveSig] int IsSystemSoundsSession();
        [PreserveSig] int SetDuckingPreference([MarshalAs(UnmanagedType.Bool)] bool optOut);
    }
    [ComImport, Guid("87CE5498-68D6-44E5-9215-6DA47EF883D8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IVolume {
        [PreserveSig] int SetMasterVolume(float volume, ref Guid context);
        [PreserveSig] int GetMasterVolume(out float volume);
        [PreserveSig] int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, ref Guid context);
        [PreserveSig] int GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
    }
    private static void Release(object value) {
        if (value != null && Marshal.IsComObject(value)) Marshal.ReleaseComObject(value);
    }
    public static int Volume(int desired) {
        IDeviceEnumerator enumerator = null;
        IDevice device = null;
        object manager = null;
        ISessionEnumerator sessions = null;
        try {
            enumerator = (IDeviceEnumerator)new DeviceEnumerator();
            Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(0, 1, out device));
            Guid iid = typeof(ISessionManager).GUID;
            Marshal.ThrowExceptionForHR(device.Activate(ref iid, 23, IntPtr.Zero, out manager));
            Marshal.ThrowExceptionForHR(((ISessionManager)manager).GetSessionEnumerator(out sessions));
            int count;
            Marshal.ThrowExceptionForHR(sessions.GetCount(out count));
            int result = -1;
            for (int i = 0; i < count; i++) {
                object session = null;
                try {
                    if (sessions.GetSession(i, out session) < 0) continue;
                    var control = (ISessionControl)session;
                    uint pid;
                    if (control.GetProcessId(out pid) < 0 || pid == 0) continue;
                    using (var process = Process.GetProcessById((int)pid)) {
                        if (!String.Equals(process.ProcessName, "Spotify", StringComparison.OrdinalIgnoreCase)) continue;
                    }
                    var volume = (IVolume)control;
                    if (desired >= 0 && desired <= 100) {
                        Guid context = Guid.Empty;
                        Marshal.ThrowExceptionForHR(volume.SetMasterVolume(desired / 100f, ref context));
                    }
                    float level;
                    if (volume.GetMasterVolume(out level) >= 0) result = (int)Math.Round(level * 100);
                } catch (ArgumentException) { } finally { Release(session); }
            }
            return result;
        } catch { return -1; }
        finally { Release(sessions); Release(manager); Release(device); Release(enumerator); }
    }
}
'@
    $script:asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and
        $_.GetGenericArguments().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
    } | Select-Object -First 1
    function Await($Operation, [Type]$ResultType) {
        $task = $script:asTask.MakeGenericMethod($ResultType).Invoke($null, @($Operation))
        if (-not $task.Wait(3000)) { throw 'Media session request timed out' }
        return $task.Result
    }
    $managerType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType=WindowsRuntime]
    $propertiesType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType=WindowsRuntime]
    $manager = Await ($managerType::RequestAsync()) $managerType
    $script:asStream = [System.IO.WindowsRuntimeStreamExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsStream' -and $_.GetParameters().Count -eq 1
    } | Select-Object -First 1
    function Cover($Media) {
        $stream = $null; $reader = $null
        try {
            if ($null -eq $Media.Thumbnail) { return @{ id = ''; data = '' } }
            $streamType = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType=WindowsRuntime]
            $stream = Await ($Media.Thumbnail.OpenReadAsync()) $streamType
            # The raw WinRT result is an untyped COM object in Windows PowerShell.
            # The .NET adapter disposes the native stream; do not assume it is seekable.
            $reader = $script:asStream.Invoke($null, @($stream))
            $memory = New-Object System.IO.MemoryStream
            try {
                $buffer = New-Object byte[] 8192
                while (($read = $reader.Read($buffer, 0, $buffer.Length)) -gt 0) {
                    if ($memory.Length + $read -gt 524288) { return @{ id = ''; data = '' } }
                    $memory.Write($buffer, 0, $read)
                }
                $bytes = $memory.ToArray()
            } finally { $memory.Dispose() }
            if ($bytes.Length -eq 0) { return @{ id = ''; data = '' } }
            $hash = [System.Security.Cryptography.SHA256]::Create()
            try { $id = ([BitConverter]::ToString($hash.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() }
            finally { $hash.Dispose() }
            return @{ id = $id; data = [Convert]::ToBase64String($bytes) }
        } catch { return @{ id = ''; data = '' } }
        finally {
            if ($null -ne $reader) { try { ([System.IDisposable]$reader).Dispose() } catch { } }
            if ($null -ne $stream) { try { ([System.IDisposable]$stream).Dispose() } catch { } }
        }
    }
    if (-not $Probe) { [CointSpotifyInput]::Start() }
    $nextPoll = [DateTime]::MinValue
    $session = $null
    $coverKey = ''; $coverId = ''
    $coverRetry = [DateTime]::MinValue
    while ($Probe -or -not [CointSpotifyInput]::Closed) {
        if ([DateTime]::UtcNow -ge $nextPoll -or -not [CointSpotifyInput]::Commands.IsEmpty) {
            $session = $manager.GetSessions() | Where-Object { $_.SourceAppUserModelId -match '(?i)spotify' } | Select-Object -First 1
        }
        $command = $null
        while ([CointSpotifyInput]::Commands.TryDequeue([ref]$command)) {
            if ($command -eq 'quit') { return }
            if ($null -eq $session) { continue }
            switch -Regex ($command) {
                '^toggle$' { $null = Await ($session.TryTogglePlayPauseAsync()) ([bool]); break }
                '^next$' { $null = Await ($session.TrySkipNextAsync()) ([bool]); break }
                '^previous$' { $null = Await ($session.TrySkipPreviousAsync()) ([bool]); break }
                '^shuffle$' {
                    $null = Await ($session.TryChangeShuffleActiveAsync(-not [bool]$session.GetPlaybackInfo().IsShuffleActive)) ([bool])
                    break
                }
                '^repeat$' {
                    $mode = [Windows.Media.MediaPlaybackAutoRepeatMode, Windows.Media, ContentType=WindowsRuntime]
                    $current = [string]$session.GetPlaybackInfo().AutoRepeatMode
                    $next = if ($current -eq 'None') { $mode::Track } elseif ($current -eq 'Track') { $mode::List } else { $mode::None }
                    $null = Await ($session.TryChangeAutoRepeatModeAsync($next)) ([bool])
                    break
                }
                '^volume (100|[0-9]{1,2})$' { $null = [CointSpotifyAudio]::Volume([int]$Matches[1]); break }
                '^seek ([0-9]{1,10})$' {
                    $timeline = $session.GetTimelineProperties()
                    $length = ($timeline.EndTime - $timeline.StartTime).TotalMilliseconds
                    $ms = [Math]::Min($length, [long]$Matches[1])
                    $null = Await ($session.TryChangePlaybackPositionAsync([long]($timeline.StartTime.Ticks + $ms * 10000))) ([bool])
                    break
                }
            }
            $nextPoll = [DateTime]::MinValue
        }
        if ([DateTime]::UtcNow -ge $nextPoll) {
            if ($null -eq $session) { Publish @{ status = 'waiting' } }
            else {
                $media = Await ($session.TryGetMediaPropertiesAsync()) $propertiesType
                $info = $session.GetPlaybackInfo()
                $timeline = $session.GetTimelineProperties()
                $playing = $info.PlaybackStatus.ToString() -eq 'Playing'
                $position = ($timeline.Position - $timeline.StartTime).TotalMilliseconds
                if ($playing) { $position += [Math]::Max(0, ([DateTime]::UtcNow - $timeline.LastUpdatedTime.UtcDateTime).TotalMilliseconds) }
                $title = [string]$media.Title
                $artist = [string]$media.Artist
                $key = $title + '|' + $artist + '|' + [string]$media.AlbumTitle
                if ($key -ne $coverKey -or ($coverId -eq '' -and [DateTime]::UtcNow -ge $coverRetry)) {
                    $art = Cover $media
                    $coverId = $art.id; $coverKey = $key
                    $coverRetry = [DateTime]::UtcNow.AddSeconds(5)
                    Publish @{ status = 'artwork'; id = $art.id; data = $art.data }
                }
                Publish @{
                    status = 'ready'; title = $title.Substring(0, [Math]::Min(256, $title.Length))
                    artist = $artist.Substring(0, [Math]::Min(256, $artist.Length))
                    playing = $playing; position = [long]$position
                    duration = [long]($timeline.EndTime - $timeline.StartTime).TotalMilliseconds
                    volume = [CointSpotifyAudio]::Volume(-1)
                    coverId = $coverId; shuffle = [bool]$info.IsShuffleActive
                    repeat = ([string]$info.AutoRepeatMode).ToLowerInvariant()
                    canShuffle = [bool]$info.Controls.IsShuffleEnabled
                    canRepeat = [bool]$info.Controls.IsRepeatEnabled
                    canSeek = [bool]$info.Controls.IsPlaybackPositionEnabled
                }
            }
            if ($Probe) { break }
            $nextPoll = [DateTime]::UtcNow.AddSeconds(1)
        }
        Start-Sleep -Milliseconds 100
    }
} catch {
    Publish @{ status = 'error' }
    # One diagnostic on stderr, never tokens or playback commands.
    [Console]::Error.WriteLine('Spotify media bridge: ' + $_.Exception.GetType().Name)
    exit 1
}
