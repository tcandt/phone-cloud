package com.android.helper.device;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.ParcelFileDescriptor;
import com.android.helper.Options;
import com.android.helper.control.ControlChannel;
import com.android.helper.util.IO;
import com.android.helper.util.StringUtils;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/* JADX INFO: loaded from: classes.dex */
public final class DesktopConnection implements Closeable {
    private static final int DEVICE_NAME_FIELD_LENGTH = 64;
    private static final String SOCKET_NAME_PREFIX = "scrcpy";
    private final FileDescriptor audioFd;
    private final SocketWrapper audioSocket;
    private final ControlChannel controlChannel;
    private final SocketWrapper controlSocket;
    private final ControlChannel touchChannel;
    private final SocketWrapper touchSocket;
    private final FileDescriptor videoFd;
    private final SocketWrapper videoSocket;

    private interface SocketWrapper extends Closeable {
        FileDescriptor getFileDescriptor();

        InputStream getInputStream() throws IOException;

        OutputStream getOutputStream() throws IOException;

        void shutdownInput() throws IOException;

        void shutdownOutput() throws IOException;
    }

    private static class LocalSocketWrapper implements SocketWrapper {
        private final LocalSocket socket;

        public LocalSocketWrapper(LocalSocket localSocket) {
            this.socket = localSocket;
        }

        @Override // com.android.helper.device.DesktopConnection.SocketWrapper
        public FileDescriptor getFileDescriptor() {
            return this.socket.getFileDescriptor();
        }

        @Override // com.android.helper.device.DesktopConnection.SocketWrapper
        public InputStream getInputStream() throws IOException {
            return this.socket.getInputStream();
        }

        @Override // com.android.helper.device.DesktopConnection.SocketWrapper
        public OutputStream getOutputStream() throws IOException {
            return this.socket.getOutputStream();
        }

        @Override // com.android.helper.device.DesktopConnection.SocketWrapper
        public void shutdownInput() throws IOException {
            this.socket.shutdownInput();
        }

        @Override // com.android.helper.device.DesktopConnection.SocketWrapper
        public void shutdownOutput() throws IOException {
            this.socket.shutdownOutput();
        }

        @Override // java.io.Closeable, java.lang.AutoCloseable
        public void close() throws IOException {
            this.socket.close();
        }
    }

    private static class NetSocketWrapper implements SocketWrapper {
        private final ParcelFileDescriptor pfd;
        private final Socket socket;

        public NetSocketWrapper(Socket socket) {
            this.socket = socket;
            this.pfd = ParcelFileDescriptor.fromSocket(socket);
        }

        @Override // com.android.helper.device.DesktopConnection.SocketWrapper
        public FileDescriptor getFileDescriptor() {
            return this.pfd.getFileDescriptor();
        }

        @Override // com.android.helper.device.DesktopConnection.SocketWrapper
        public InputStream getInputStream() throws IOException {
            return this.socket.getInputStream();
        }

        @Override // com.android.helper.device.DesktopConnection.SocketWrapper
        public OutputStream getOutputStream() throws IOException {
            return this.socket.getOutputStream();
        }

        @Override // com.android.helper.device.DesktopConnection.SocketWrapper
        public void shutdownInput() throws IOException {
            this.socket.shutdownInput();
        }

        @Override // com.android.helper.device.DesktopConnection.SocketWrapper
        public void shutdownOutput() throws IOException {
            this.socket.shutdownOutput();
        }

        @Override // java.io.Closeable, java.lang.AutoCloseable
        public void close() throws IOException {
            try {
                this.pfd.close();
            } finally {
                this.socket.close();
            }
        }
    }

    private DesktopConnection(SocketWrapper socketWrapper, SocketWrapper socketWrapper2, SocketWrapper socketWrapper3, SocketWrapper socketWrapper4) throws IOException {
        this.videoSocket = socketWrapper;
        this.audioSocket = socketWrapper2;
        this.controlSocket = socketWrapper3;
        this.touchSocket = socketWrapper4;
        this.videoFd = socketWrapper != null ? socketWrapper.getFileDescriptor() : null;
        this.audioFd = socketWrapper2 != null ? socketWrapper2.getFileDescriptor() : null;
        this.controlChannel = socketWrapper3 != null ? new ControlChannel(socketWrapper3.getInputStream(), socketWrapper3.getOutputStream()) : null;
        this.touchChannel = socketWrapper4 != null ? new ControlChannel(socketWrapper4.getInputStream(), socketWrapper4.getOutputStream()) : null;
    }

    private static LocalSocketWrapper connectLocal(String str) throws IOException {
        LocalSocket localSocket = new LocalSocket();
        localSocket.connect(new LocalSocketAddress(str));
        return new LocalSocketWrapper(localSocket);
    }

    private static NetSocketWrapper connectNet(int i) throws IOException {
        return new NetSocketWrapper(new Socket("127.0.0.1", i));
    }

    private static String getSocketName(int i) {
        if (i == -1) {
            return SOCKET_NAME_PREFIX;
        }
        return SOCKET_NAME_PREFIX + String.format("_%08x", Integer.valueOf(i));
    }

    public static DesktopConnection open(Options options) throws IOException {
        int scid = options.getScid();
        boolean isTunnelForward = options.isTunnelForward();
        boolean video = options.getVideo();
        boolean audio = options.getAudio();
        boolean control = options.getControl();
        boolean sendDummyByte = options.getSendDummyByte();
        int port = options.getPort();

        SocketWrapper videoSocket = null;
        SocketWrapper audioSocket = null;
        SocketWrapper controlSocket = null;
        SocketWrapper touchSocket = null;

        try {
            if (isTunnelForward) {
                if (port > 0) {
                    ServerSocket serverSocket = new ServerSocket();
                    try {
                        serverSocket.setReuseAddress(true);
                        serverSocket.bind(new InetSocketAddress(port));
                        if (video) {
                            videoSocket = new NetSocketWrapper(serverSocket.accept());
                            if (sendDummyByte) {
                                videoSocket.getOutputStream().write(0);
                            }
                        }
                        if (audio) {
                            audioSocket = new NetSocketWrapper(serverSocket.accept());
                            if (sendDummyByte) {
                                audioSocket.getOutputStream().write(0);
                            }
                        }
                        if (control) {
                            controlSocket = new NetSocketWrapper(serverSocket.accept());
                            if (sendDummyByte) {
                                controlSocket.getOutputStream().write(0);
                            }
                        }
                    } finally {
                        try {
                            serverSocket.close();
                        } catch (IOException ignored) {}
                    }
                } else {
                    if (video) {
                        LocalServerSocket localServerSocket = new LocalServerSocket(options.getVideoSocket());
                        try {
                            videoSocket = new LocalSocketWrapper(localServerSocket.accept());
                            if (sendDummyByte) {
                                videoSocket.getOutputStream().write(0);
                            }
                        } finally {
                            try { localServerSocket.close(); } catch (IOException ignored) {}
                        }
                    }
                    if (audio) {
                        LocalServerSocket localServerSocket = new LocalServerSocket(options.getAudioSocket());
                        try {
                            audioSocket = new LocalSocketWrapper(localServerSocket.accept());
                            if (sendDummyByte) {
                                audioSocket.getOutputStream().write(0);
                            }
                        } finally {
                            try { localServerSocket.close(); } catch (IOException ignored) {}
                        }
                    }
                    if (control) {
                        LocalServerSocket localServerSocket = new LocalServerSocket(options.getControlSocket());
                        try {
                            controlSocket = new LocalSocketWrapper(localServerSocket.accept());
                        } finally {
                            try { localServerSocket.close(); } catch (IOException ignored) {}
                        }
                    }
                    if (options.getTouchSocket() != null) {
                        LocalServerSocket localServerSocket = new LocalServerSocket(options.getTouchSocket());
                        try {
                            touchSocket = new LocalSocketWrapper(localServerSocket.accept());
                        } finally {
                            try { localServerSocket.close(); } catch (IOException ignored) {}
                        }
                    }
                }
            } else {
                if (port > 0) {
                    videoSocket = video ? connectNet(port) : null;
                    audioSocket = audio ? connectNet(port) : null;
                    controlSocket = control ? connectNet(port) : null;
                } else {
                    String socketName = getSocketName(scid);
                    videoSocket = video ? connectLocal(socketName) : null;
                    audioSocket = audio ? connectLocal(socketName) : null;
                    controlSocket = control ? connectLocal(socketName) : null;
                }
            }
            return new DesktopConnection(videoSocket, audioSocket, controlSocket, touchSocket);
        } catch (IOException | RuntimeException e) {
            if (videoSocket != null) {
                try { videoSocket.close(); } catch (IOException ignored) {}
            }
            if (audioSocket != null) {
                try { audioSocket.close(); } catch (IOException ignored) {}
            }
            if (controlSocket != null) {
                try { controlSocket.close(); } catch (IOException ignored) {}
            }
            if (touchSocket != null) {
                try { touchSocket.close(); } catch (IOException ignored) {}
            }
            throw e;
        }
    }

    private SocketWrapper getFirstSocket() {
        SocketWrapper socketWrapper = this.videoSocket;
        if (socketWrapper != null) {
            return socketWrapper;
        }
        SocketWrapper socketWrapper2 = this.audioSocket;
        return socketWrapper2 != null ? socketWrapper2 : this.controlSocket;
    }

    public void shutdown() throws IOException {
        SocketWrapper socketWrapper = this.videoSocket;
        if (socketWrapper != null) {
            socketWrapper.shutdownInput();
            this.videoSocket.shutdownOutput();
        }
        SocketWrapper socketWrapper2 = this.audioSocket;
        if (socketWrapper2 != null) {
            socketWrapper2.shutdownInput();
            this.audioSocket.shutdownOutput();
        }
        SocketWrapper socketWrapper3 = this.controlSocket;
        if (socketWrapper3 != null) {
            socketWrapper3.shutdownInput();
            this.controlSocket.shutdownOutput();
        }
        SocketWrapper socketWrapper4 = this.touchSocket;
        if (socketWrapper4 != null) {
            socketWrapper4.shutdownInput();
            this.touchSocket.shutdownOutput();
        }
    }

    @Override // java.io.Closeable, java.lang.AutoCloseable
    public void close() throws IOException {
        SocketWrapper socketWrapper = this.videoSocket;
        if (socketWrapper != null) {
            socketWrapper.close();
        }
        SocketWrapper socketWrapper2 = this.audioSocket;
        if (socketWrapper2 != null) {
            socketWrapper2.close();
        }
        SocketWrapper socketWrapper3 = this.controlSocket;
        if (socketWrapper3 != null) {
            socketWrapper3.close();
        }
        SocketWrapper socketWrapper4 = this.touchSocket;
        if (socketWrapper4 != null) {
            socketWrapper4.close();
        }
    }

    public void sendDeviceMeta(String str) throws IOException {
        byte[] bArr = new byte[DEVICE_NAME_FIELD_LENGTH];
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, bArr, 0, StringUtils.getUtf8TruncationIndex(bytes, 63));
        IO.writeFully(getFirstSocket().getFileDescriptor(), bArr, 0, DEVICE_NAME_FIELD_LENGTH);
    }

    public FileDescriptor getVideoFd() {
        return this.videoFd;
    }

    public FileDescriptor getAudioFd() {
        return this.audioFd;
    }

    public ControlChannel getControlChannel() {
        return this.controlChannel;
    }

    public ControlChannel getTouchChannel() {
        return this.touchChannel;
    }
}
