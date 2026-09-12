package com.android.helper;

import android.graphics.Rect;
import android.util.Pair;
import com.android.helper.audio.AudioCodec;
import com.android.helper.audio.AudioSource;
import com.android.helper.device.NewDisplay;
import com.android.helper.device.Orientation;
import com.android.helper.device.Size;
import com.android.helper.util.CodecOption;
import com.android.helper.util.Ln;
import com.android.helper.video.CameraAspectRatio;
import com.android.helper.video.CameraFacing;
import com.android.helper.video.VideoCodec;
import com.android.helper.video.VideoSource;
import java.util.List;
import java.util.Locale;

/* JADX INFO: loaded from: classes.dex */
public class Options {
    static final /* synthetic */ boolean $assertionsDisabled = false;
    private float angle;
    private List<CodecOption> audioCodecOptions;
    private boolean audioDup;
    private String audioEncoder;
    private CameraAspectRatio cameraAspectRatio;
    private CameraFacing cameraFacing;
    private int cameraFps;
    private boolean cameraHighSpeed;
    private String cameraId;
    private Size cameraSize;
    private Rect crop;
    private int displayId;
    private boolean listApps;
    private boolean listCameraSizes;
    private boolean listCameras;
    private boolean listDisplays;
    private boolean listEncoders;
    private float maxFps;
    private int maxSize;
    private NewDisplay newDisplay;
    private boolean powerOffScreenOnClose;
    private boolean showTouches;
    private boolean stayAwake;
    private boolean tunnelForward;
    private List<CodecOption> videoCodecOptions;
    private String videoEncoder;
    private Ln.Level logLevel = Ln.Level.DEBUG;
    private int scid = -1;
    private boolean video = true;
    private boolean audio = true;
    private VideoCodec videoCodec = VideoCodec.H264;
    private AudioCodec audioCodec = AudioCodec.OPUS;
    private VideoSource videoSource = VideoSource.DISPLAY;
    private AudioSource audioSource = AudioSource.OUTPUT;
    private int videoBitRate = 8000000;
    private int audioBitRate = 128000;
    private boolean control = true;
    private float cameraZoom = 0.0f;
    private int screenOffTimeout = -1;
    private int displayImePolicy = -1;
    private boolean clipboardAutosync = true;
    private boolean downsizeOnError = true;
    private boolean cleanup = true;
    private boolean powerOn = true;
    private boolean vdDestroyContent = true;
    private boolean vdSystemDecorations = true;
    private Orientation.Lock captureOrientationLock = Orientation.Lock.Unlocked;
    private Orientation captureOrientation = Orientation.Orient0;
    private boolean sendDeviceMeta = true;
    private boolean sendFrameMeta = true;
    private boolean sendDummyByte = true;
    private boolean sendCodecMeta = true;
    private int port = 0;
    private String videoSocket = "cloudphone_video";
    private String audioSocket = "cloudphone_audio";
    private String controlSocket = "cloudphone_control";
    private String touchSocket = "cloudphone_touch";

    public Ln.Level getLogLevel() {
        return this.logLevel;
    }

    public int getScid() {
        return this.scid;
    }

    public boolean getVideo() {
        return this.video;
    }

    public boolean getAudio() {
        return this.audio;
    }

    public int getMaxSize() {
        return this.maxSize;
    }

    public VideoCodec getVideoCodec() {
        return this.videoCodec;
    }

    public AudioCodec getAudioCodec() {
        return this.audioCodec;
    }

    public VideoSource getVideoSource() {
        return this.videoSource;
    }

    public AudioSource getAudioSource() {
        return this.audioSource;
    }

    public boolean getAudioDup() {
        return this.audioDup;
    }

    public int getVideoBitRate() {
        return this.videoBitRate;
    }

    public int getAudioBitRate() {
        return this.audioBitRate;
    }

    public float getMaxFps() {
        return this.maxFps;
    }

    public float getAngle() {
        return this.angle;
    }

    public boolean isTunnelForward() {
        return this.tunnelForward;
    }

    public Rect getCrop() {
        return this.crop;
    }

    public boolean getControl() {
        return this.control;
    }

    public int getDisplayId() {
        return this.displayId;
    }

    public String getCameraId() {
        return this.cameraId;
    }

    public Size getCameraSize() {
        return this.cameraSize;
    }

    public CameraFacing getCameraFacing() {
        return this.cameraFacing;
    }

    public CameraAspectRatio getCameraAspectRatio() {
        return this.cameraAspectRatio;
    }

    public int getCameraFps() {
        return this.cameraFps;
    }

    public boolean getCameraHighSpeed() {
        return this.cameraHighSpeed;
    }

    public float getCameraZoom() {
        return this.cameraZoom;
    }

    public boolean getShowTouches() {
        return this.showTouches;
    }

    public boolean getStayAwake() {
        return this.stayAwake;
    }

    public int getScreenOffTimeout() {
        return this.screenOffTimeout;
    }

    public int getDisplayImePolicy() {
        return this.displayImePolicy;
    }

    public List<CodecOption> getVideoCodecOptions() {
        return this.videoCodecOptions;
    }

    public List<CodecOption> getAudioCodecOptions() {
        return this.audioCodecOptions;
    }

    public String getVideoEncoder() {
        return this.videoEncoder;
    }

    public String getAudioEncoder() {
        return this.audioEncoder;
    }

    public boolean getPowerOffScreenOnClose() {
        return this.powerOffScreenOnClose;
    }

    public boolean getClipboardAutosync() {
        return this.clipboardAutosync;
    }

    public boolean getDownsizeOnError() {
        return this.downsizeOnError;
    }

    public boolean getCleanup() {
        return this.cleanup;
    }

    public boolean getPowerOn() {
        return this.powerOn;
    }

    public NewDisplay getNewDisplay() {
        return this.newDisplay;
    }

    public Orientation getCaptureOrientation() {
        return this.captureOrientation;
    }

    public Orientation.Lock getCaptureOrientationLock() {
        return this.captureOrientationLock;
    }

    public boolean getVDDestroyContent() {
        return this.vdDestroyContent;
    }

    public boolean getVDSystemDecorations() {
        return this.vdSystemDecorations;
    }

    public boolean getList() {
        return this.listEncoders || this.listDisplays || this.listCameras || this.listCameraSizes || this.listApps;
    }

    public boolean getListEncoders() {
        return this.listEncoders;
    }

    public boolean getListDisplays() {
        return this.listDisplays;
    }

    public boolean getListCameras() {
        return this.listCameras;
    }

    public boolean getListCameraSizes() {
        return this.listCameraSizes;
    }

    public boolean getListApps() {
        return this.listApps;
    }

    public boolean getSendDeviceMeta() {
        return this.sendDeviceMeta;
    }

    public boolean getSendFrameMeta() {
        return this.sendFrameMeta;
    }

    public boolean getSendDummyByte() {
        return this.sendDummyByte;
    }

    public boolean getSendCodecMeta() {
        return this.sendCodecMeta;
    }

    public int getPort() {
        return this.port;
    }

    public String getVideoSocket() {
        return this.videoSocket;
    }

    public String getAudioSocket() {
        return this.audioSocket;
    }

    public String getControlSocket() {
        return this.controlSocket;
    }

    public String getTouchSocket() {
        return this.touchSocket;
    }

    public static Options parse(String... strArr) {
        Options options = new Options();
        if (System.getenv("CP_STEAL_MODE") == null && strArr.length != 0) {
            if (strArr.length < 1) {
                throw new IllegalArgumentException("Missing client version");
            }
            String str = strArr[0];
            if (!str.equals(BuildConfig.VERSION_NAME)) {
                throw new IllegalArgumentException("The server version (3.3.4-2af7ccc1) does not match the client (" + str + ")");
            }
            for (int i = 1; i < strArr.length; i++) {
                String str2 = strArr[i];
                int iIndexOf = str2.indexOf(61);
                if (iIndexOf == -1) {
                    throw new IllegalArgumentException("Invalid key=value pair: \"" + str2 + "\"");
                }
                options.setOption(str2.substring(0, iIndexOf), str2.substring(iIndexOf + 1));
            }
        } else {
            options.readFromEnvironment();
        }
        if (options.newDisplay != null) {
            options.displayId = -1;
        }
        return options;
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARN: Code duplicated, block: B:4:0x0012  */
    public void setOption(String str, String str2) {
        str.hashCode();
        switch (str) {
            case "camera_fps":
                this.cameraFps = Integer.parseInt(str2);
                return;
            case "log_level":
                this.logLevel = Ln.Level.valueOf(str2.toUpperCase(Locale.ENGLISH));
                return;
            case "list_camera_sizes":
                this.listCameraSizes = Boolean.parseBoolean(str2);
                return;
            case "camera_facing":
                if (str2.isEmpty()) {
                    return;
                }
                CameraFacing cameraFacingFindByName = CameraFacing.findByName(str2);
                if (cameraFacingFindByName != null) {
                    this.cameraFacing = cameraFacingFindByName;
                    return;
                }
                throw new IllegalArgumentException("Camera facing " + str2 + " not supported");
            case "touch_socket":
                this.touchSocket = str2;
                return;
            case "audio_socket":
                this.audioSocket = str2;
                return;
            case "audio_source":
                AudioSource audioSourceFindByName = AudioSource.findByName(str2);
                if (audioSourceFindByName != null) {
                    this.audioSource = audioSourceFindByName;
                    return;
                }
                throw new IllegalArgumentException("Audio source " + str2 + " not supported");
            case "raw_stream":
                if (Boolean.parseBoolean(str2)) {
                    this.sendDeviceMeta = false;
                    this.sendFrameMeta = false;
                    this.sendDummyByte = false;
                    this.sendCodecMeta = false;
                    return;
                }
                return;
            case "audio_encoder":
                if (str2.isEmpty()) {
                    return;
                }
                this.audioEncoder = str2;
                return;
            case "list_apps":
                this.listApps = Boolean.parseBoolean(str2);
                return;
            case "vd_destroy_content":
                this.vdDestroyContent = Boolean.parseBoolean(str2);
                return;
            case "vd_system_decorations":
                this.vdSystemDecorations = Boolean.parseBoolean(str2);
                return;
            case "clipboard_autosync":
                this.clipboardAutosync = Boolean.parseBoolean(str2);
                return;
            case "display_ime_policy":
                this.displayImePolicy = parseDisplayImePolicy(str2);
                return;
            case "list_encoders":
                this.listEncoders = Boolean.parseBoolean(str2);
                return;
            case "capture_orientation":
                Pair<Orientation.Lock, Orientation> captureOrientation = parseCaptureOrientation(str2);
                this.captureOrientationLock = (Orientation.Lock) captureOrientation.first;
                this.captureOrientation = (Orientation) captureOrientation.second;
                return;
            case "display_id":
                this.displayId = Integer.parseInt(str2);
                return;
            case "power_off_on_close":
                this.powerOffScreenOnClose = Boolean.parseBoolean(str2);
                return;
            case "control_socket":
                this.controlSocket = str2;
                return;
            case "audio_bit_rate":
                this.audioBitRate = Integer.parseInt(str2);
                return;
            case "video_bit_rate":
                this.videoBitRate = Integer.parseInt(str2);
                return;
            case "list_cameras":
                this.listCameras = Boolean.parseBoolean(str2);
                return;
            case "camera_ar":
                if (str2.isEmpty()) {
                    return;
                }
                this.cameraAspectRatio = parseCameraAspectRatio(str2);
                return;
            case "camera_id":
                if (str2.isEmpty()) {
                    return;
                }
                this.cameraId = str2;
                return;
            case "stay_awake":
                this.stayAwake = Boolean.parseBoolean(str2);
                return;
            case "tunnel_forward":
                this.tunnelForward = Boolean.parseBoolean(str2);
                return;
            case "send_dummy_byte":
                this.sendDummyByte = Boolean.parseBoolean(str2);
                return;
            case "video_socket":
                this.videoSocket = str2;
                return;
            case "video_source":
                VideoSource videoSourceFindByName = VideoSource.findByName(str2);
                if (videoSourceFindByName != null) {
                    this.videoSource = videoSourceFindByName;
                    return;
                }
                throw new IllegalArgumentException("Video source " + str2 + " not supported");
            case "crop":
                if (str2.isEmpty()) {
                    return;
                }
                this.crop = parseCrop(str2);
                return;
            case "port":
                this.port = Integer.parseInt(str2);
                return;
            case "scid":
                int i = Integer.parseInt(str2, 16);
                if (i < -1) {
                    throw new IllegalArgumentException("scid may not be negative (except -1 for 'none'): " + i);
                }
                this.scid = i;
                return;
            case "angle":
                this.angle = parseFloat("angle", str2);
                return;
            case "audio":
                this.audio = Boolean.parseBoolean(str2);
                return;
            case "video":
                this.video = Boolean.parseBoolean(str2);
                return;
            case "audio_dup":
                this.audioDup = Boolean.parseBoolean(str2);
                return;
            case "screen_off_timeout":
                int i2 = Integer.parseInt(str2);
                this.screenOffTimeout = i2;
                if (i2 >= -1) {
                    return;
                }
                throw new IllegalArgumentException("Invalid screen off timeout: " + this.screenOffTimeout);
            case "show_touches":
                this.showTouches = Boolean.parseBoolean(str2);
                return;
            case "max_size":
                this.maxSize = Integer.parseInt(str2) & (-8);
                return;
            case "video_encoder":
                if (str2.isEmpty()) {
                    return;
                }
                this.videoEncoder = str2;
                return;
            case "send_codec_meta":
                this.sendCodecMeta = Boolean.parseBoolean(str2);
                return;
            case "send_frame_meta":
                this.sendFrameMeta = Boolean.parseBoolean(str2);
                return;
            case "audio_codec":
                AudioCodec audioCodecFindByName = AudioCodec.findByName(str2);
                if (audioCodecFindByName != null) {
                    this.audioCodec = audioCodecFindByName;
                    return;
                }
                throw new IllegalArgumentException("Audio codec " + str2 + " not supported");
            case "max_fps":
                this.maxFps = parseFloat("max_fps", str2);
                return;
            case "cleanup":
                this.cleanup = Boolean.parseBoolean(str2);
                return;
            case "power_on":
                this.powerOn = Boolean.parseBoolean(str2);
                return;
            case "new_display":
                this.newDisplay = parseNewDisplay(str2);
                return;
            case "downsize_on_error":
                this.downsizeOnError = Boolean.parseBoolean(str2);
                return;
            case "control":
                this.control = Boolean.parseBoolean(str2);
                return;
            case "audio_codec_options":
                this.audioCodecOptions = CodecOption.parse(str2);
                return;
            case "video_codec":
                VideoCodec videoCodecFindByName = VideoCodec.findByName(str2);
                if (videoCodecFindByName != null) {
                    this.videoCodec = videoCodecFindByName;
                    return;
                }
                throw new IllegalArgumentException("Video codec " + str2 + " not supported");
            case "camera_high_speed":
                this.cameraHighSpeed = Boolean.parseBoolean(str2);
                return;
            case "video_codec_options":
                this.videoCodecOptions = CodecOption.parse(str2);
                return;
            case "list_displays":
                this.listDisplays = Boolean.parseBoolean(str2);
                return;
            case "camera_size":
                if (str2.isEmpty()) {
                    return;
                }
                this.cameraSize = parseSize(str2);
                return;
            case "camera_zoom":
                if (str2.isEmpty()) {
                    return;
                }
                this.cameraZoom = Float.parseFloat(str2);
                return;
            case "send_device_meta":
                this.sendDeviceMeta = Boolean.parseBoolean(str2);
                return;
            default:
                Ln.w("Unknown server option: " + str);
                return;
        }
    }

    private void readFromEnvironment() {
        String[] strArr = {"scid", "log_level", "video", "audio", "video_codec", "audio_codec", "video_source", "audio_source", "audio_dup", "max_size", "video_bit_rate", "audio_bit_rate", "max_fps", "angle", "tunnel_forward", "crop", "control", "display_id", "show_touches", "stay_awake", "screen_off_timeout", "video_codec_options", "audio_codec_options", "video_encoder", "audio_encoder", "power_off_on_close", "clipboard_autosync", "downsize_on_error", "cleanup", "power_on", "list_encoders", "list_displays", "list_cameras", "list_camera_sizes", "list_apps", "camera_id", "camera_size", "camera_facing", "camera_ar", "camera_fps", "camera_high_speed", "camera_zoom", "new_display", "vd_destroy_content", "vd_system_decorations", "capture_orientation", "display_ime_policy", "send_device_meta", "send_frame_meta", "send_dummy_byte", "send_codec_meta", "raw_stream", "port", "video_socket", "audio_socket", "control_socket", "touch_socket"};
        for (int i = 0; i < 57; i++) {
            String str = strArr[i];
            String str2 = "CP_" + str.toUpperCase(Locale.ENGLISH);
            String str3 = System.getenv(str2);
            if (str3 != null) {
                try {
                    setOption(str, str3);
                } catch (Exception e) {
                    Ln.e("Failed to parse env variable " + str2 + "=" + str3, e);
                }
            }
        }
    }

    private static Rect parseCrop(String str) {
        String[] strArrSplit = str.split(":");
        if (strArrSplit.length != 4) {
            throw new IllegalArgumentException("Crop must contains 4 values separated by colons: \"" + str + "\"");
        }
        int i = Integer.parseInt(strArrSplit[0]);
        int i2 = Integer.parseInt(strArrSplit[1]);
        if (i <= 0 || i2 <= 0) {
            throw new IllegalArgumentException("Invalid crop size: " + i + "x" + i2);
        }
        int i3 = Integer.parseInt(strArrSplit[2]);
        int i4 = Integer.parseInt(strArrSplit[3]);
        if (i3 < 0 || i4 < 0) {
            throw new IllegalArgumentException("Invalid crop offset: " + i3 + ":" + i4);
        }
        return new Rect(i3, i4, i + i3, i2 + i4);
    }

    private static Size parseSize(String str) {
        String[] strArrSplit = str.split("x");
        if (strArrSplit.length != 2) {
            throw new IllegalArgumentException("Invalid size format (expected <width>x<height>): \"" + str + "\"");
        }
        int i = Integer.parseInt(strArrSplit[0]);
        int i2 = Integer.parseInt(strArrSplit[1]);
        if (i <= 0 || i2 <= 0) {
            throw new IllegalArgumentException("Invalid non-positive size dimension: \"" + str + "\"");
        }
        return new Size(i, i2);
    }

    private static CameraAspectRatio parseCameraAspectRatio(String str) {
        if ("sensor".equals(str)) {
            return CameraAspectRatio.sensorAspectRatio();
        }
        String[] strArrSplit = str.split(":");
        if (strArrSplit.length == 2) {
            return CameraAspectRatio.fromFraction(Integer.parseInt(strArrSplit[0]), Integer.parseInt(strArrSplit[1]));
        }
        return CameraAspectRatio.fromFloat(Float.parseFloat(strArrSplit[0]));
    }

    private static float parseFloat(String str, String str2) {
        try {
            return Float.parseFloat(str2);
        } catch (NumberFormatException unused) {
            throw new IllegalArgumentException("Invalid float value for " + str + ": \"" + str2 + "\"");
        }
    }

    private static NewDisplay parseNewDisplay(String str) {
        if (str.isEmpty()) {
            return new NewDisplay();
        }
        String[] strArrSplit = str.split("/");
        int i = 0;
        Size size = !strArrSplit[0].isEmpty() ? parseSize(strArrSplit[0]) : null;
        if (strArrSplit.length >= 2) {
            int i2 = Integer.parseInt(strArrSplit[1]);
            if (i2 <= 0) {
                throw new IllegalArgumentException("Invalid non-positive dpi: " + strArrSplit[1]);
            }
            i = i2;
        }
        return new NewDisplay(size, i);
    }

    private static Pair<Orientation.Lock, Orientation> parseCaptureOrientation(String str) {
        Orientation.Lock lock;
        if (str.isEmpty()) {
            throw new IllegalArgumentException("Empty capture orientation string");
        }
        if (str.charAt(0) == '@') {
            str = str.substring(1);
            if (str.isEmpty()) {
                return Pair.create(Orientation.Lock.LockedInitial, Orientation.Orient0);
            }
            lock = Orientation.Lock.LockedValue;
        } else {
            lock = Orientation.Lock.Unlocked;
        }
        return Pair.create(lock, Orientation.getByName(str));
    }

    private static int parseDisplayImePolicy(String str) {
        str.hashCode();
        switch (str) {
            case "hide":
                return 2;
            case "local":
                return 0;
            case "fallback":
                return 1;
            default:
                throw new IllegalArgumentException("Invalid display IME policy: " + str);
        }
    }
}
