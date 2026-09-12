package com.android.helper.util;

import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.util.Range;
import com.android.helper.audio.AudioCodec;
import com.android.helper.device.Device;
import com.android.helper.device.DeviceApp;
import com.android.helper.device.DisplayInfo;
import com.android.helper.device.Size;
import com.android.helper.video.VideoCodec;
import com.android.helper.wrappers.DisplayManager;
import com.android.helper.wrappers.ServiceManager;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/* JADX INFO: loaded from: classes.dex */
public final class LogUtils {
    private LogUtils() {
    }

    private static String buildEncoderListMessage(String str, Codec[] codecArr) {
        StringBuilder sb = new StringBuilder("List of ");
        sb.append(str);
        sb.append(" encoders:");
        MediaCodecList mediaCodecList = new MediaCodecList(0);
        for (Codec codec : codecArr) {
            for (MediaCodecInfo mediaCodecInfo : CodecUtils.getEncoders(mediaCodecList, codec.getMimeType())) {
                int length = sb.length();
                sb.append("\n    --");
                sb.append(str);
                sb.append("-codec=");
                sb.append(codec.getName());
                sb.append(" --");
                sb.append(str);
                sb.append("-encoder=");
                sb.append(mediaCodecInfo.getName());
                if (Build.VERSION.SDK_INT >= 29) {
                    int length2 = sb.length() - length;
                    if (length2 < 70) {
                        sb.append(String.format("%" + (70 - length2) + "s", " "));
                    }
                    sb.append(" (");
                    sb.append(getHwCodecType(mediaCodecInfo));
                    sb.append(')');
                    if (mediaCodecInfo.isVendor()) {
                        sb.append(" [vendor]");
                    }
                    if (mediaCodecInfo.isAlias()) {
                        sb.append(" (alias for ");
                        sb.append(mediaCodecInfo.getCanonicalName());
                        sb.append(')');
                    }
                }
            }
        }
        return sb.toString();
    }

    public static String buildVideoEncoderListMessage() {
        return buildEncoderListMessage("video", VideoCodec.values());
    }

    public static String buildAudioEncoderListMessage() {
        return buildEncoderListMessage("audio", AudioCodec.values());
    }

    private static String getHwCodecType(MediaCodecInfo mediaCodecInfo) {
        if (mediaCodecInfo.isSoftwareOnly()) {
            return "sw";
        }
        if (mediaCodecInfo.isHardwareAccelerated()) {
            return "hw";
        }
        return "hybrid";
    }

    public static String buildDisplayListMessage() {
        StringBuilder sb = new StringBuilder("List of displays:");
        DisplayManager displayManager = ServiceManager.getDisplayManager();
        int[] displayIds = displayManager.getDisplayIds();
        if (displayIds == null || displayIds.length == 0) {
            sb.append("\n    (none)");
        } else {
            for (int i : displayIds) {
                sb.append("\n    --display-id=");
                sb.append(i);
                sb.append("    (");
                DisplayInfo displayInfo = displayManager.getDisplayInfo(i);
                if (displayInfo != null) {
                    Size size = displayInfo.getSize();
                    sb.append(size.getWidth());
                    sb.append("x");
                    sb.append(size.getHeight());
                } else {
                    sb.append("size unknown");
                }
                sb.append(")");
            }
        }
        return sb.toString();
    }

    private static String getCameraFacingName(int i) {
        if (i == 0) {
            return "front";
        }
        if (i == 1) {
            return "back";
        }
        if (i == 2) {
            return "external";
        }
        return "unknown";
    }

    private static boolean isCameraBackwardCompatible(CameraCharacteristics cameraCharacteristics) {
        int[] iArr = (int[]) cameraCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (iArr == null) {
            return false;
        }
        for (int i : iArr) {
            if (i == 0) {
                return true;
            }
        }
        return false;
    }

    public static String buildCameraListMessage(boolean z) {
        StringBuilder sb = new StringBuilder("List of cameras:");
        CameraManager cameraManager = ServiceManager.getCameraManager();
        try {
            String[] cameraIdList = cameraManager.getCameraIdList();
            if (cameraIdList.length == 0) {
                sb.append("\n    (none)");
            } else {
                for (String str : cameraIdList) {
                    CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(str);
                    if (isCameraBackwardCompatible(cameraCharacteristics)) {
                        sb.append("\n    --camera-id=");
                        sb.append(str);
                        int iIntValue = ((Integer) cameraCharacteristics.get(CameraCharacteristics.LENS_FACING)).intValue();
                        sb.append("    (");
                        sb.append(getCameraFacingName(iIntValue));
                        sb.append(", ");
                        Rect rect = (Rect) cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                        sb.append(rect.width());
                        sb.append("x");
                        sb.append(rect.height());
                        try {
                            Range[] rangeArr = (Range[]) cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                            if (rangeArr != null) {
                                SortedSet<Integer> uniqueSet = getUniqueSet(rangeArr);
                                sb.append(", fps=");
                                sb.append(uniqueSet);
                            }
                        } catch (Exception e) {
                            Ln.w("Could not get available frame rates for camera " + str, e);
                        }
                        sb.append(')');
                        if (z) {
                            StreamConfigurationMap streamConfigurationMap = (StreamConfigurationMap) cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                            android.util.Size[] outputSizes = streamConfigurationMap.getOutputSizes(MediaCodec.class);
                            if (outputSizes == null || outputSizes.length == 0) {
                                sb.append("\n        (none)");
                            } else {
                                for (android.util.Size size : outputSizes) {
                                    sb.append("\n        - ");
                                    sb.append(size.getWidth());
                                    sb.append('x');
                                    sb.append(size.getHeight());
                                }
                            }
                            android.util.Size[] highSpeedVideoSizes = streamConfigurationMap.getHighSpeedVideoSizes();
                            if (highSpeedVideoSizes != null && highSpeedVideoSizes.length > 0) {
                                sb.append("\n      High speed capture (--camera-high-speed):");
                                for (android.util.Size size2 : highSpeedVideoSizes) {
                                    SortedSet<Integer> uniqueSet2 = getUniqueSet(streamConfigurationMap.getHighSpeedVideoFpsRanges());
                                    sb.append("\n        - ");
                                    sb.append(size2.getWidth());
                                    sb.append("x");
                                    sb.append(size2.getHeight());
                                    sb.append(" (fps=");
                                    sb.append(uniqueSet2);
                                    sb.append(')');
                                }
                            }
                        }
                    }
                }
            }
        } catch (CameraAccessException unused) {
            sb.append("\n    (access denied)");
        }
        return sb.toString();
    }

    private static SortedSet<Integer> getUniqueSet(Range<Integer>[] rangeArr) {
        TreeSet treeSet = new TreeSet();
        for (Range<Integer> range : rangeArr) {
            treeSet.add((Integer) range.getUpper());
        }
        return treeSet;
    }

    public static String buildAppListMessage() {
        return buildAppListMessage("List of apps:", Device.listApps());
    }

    public static String buildAppListMessage(String str, List<DeviceApp> list) {
        StringBuilder sb = new StringBuilder(str);
        Collections.sort(list, new Comparator() { // from class: com.android.helper.util.LogUtils$$ExternalSyntheticLambda5
            @Override // java.util.Comparator
            public final int compare(Object obj, Object obj2) {
                return LogUtils.lambda$buildAppListMessage$0((DeviceApp) obj, (DeviceApp) obj2);
            }
        });
        for (DeviceApp deviceApp : list) {
            String name = deviceApp.getName();
            int length = 30 - name.length();
            sb.append("\n ");
            if (deviceApp.isSystem()) {
                sb.append("* ");
            } else {
                sb.append("- ");
            }
            sb.append(name);
            if (length > 0) {
                sb.append(String.format("%" + length + "s", " "));
            } else {
                sb.append("\n   ");
                sb.append(String.format("%30s", " "));
            }
            sb.append(" ");
            sb.append(deviceApp.getPackageName());
        }
        return sb.toString();
    }

    static /* synthetic */ int lambda$buildAppListMessage$0(DeviceApp deviceApp, DeviceApp deviceApp2) {
        int i = -Boolean.compare(deviceApp.isSystem(), deviceApp2.isSystem());
        if (i != 0) {
            return i;
        }
        int iCompare = Objects.compare(deviceApp.getName(), deviceApp2.getName(), new Comparator() { // from class: com.android.helper.util.LogUtils$$ExternalSyntheticLambda6
            @Override // java.util.Comparator
            public final int compare(Object obj, Object obj2) {
                return ((String) obj).compareTo((String) obj2);
            }
        });
        return iCompare != 0 ? iCompare : Objects.compare(deviceApp.getPackageName(), deviceApp2.getPackageName(), new Comparator() { // from class: com.android.helper.util.LogUtils$$ExternalSyntheticLambda6
            @Override // java.util.Comparator
            public final int compare(Object obj, Object obj2) {
                return ((String) obj).compareTo((String) obj2);
            }
        });
    }
}
