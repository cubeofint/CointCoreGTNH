package coint.cluster;

/**
 * An open window onto a remote ME network: ours ({@link ContainerRemoteMe}) or AE2's ({@link ContainerRemoteMeNative}).
 */
interface RemoteMeWindow {

    RemoteMe.View remoteView();
}
