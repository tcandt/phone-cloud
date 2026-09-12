package com.android.helper.control;

import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Pair;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import com.android.helper.AsyncProcessor;
import com.android.helper.CleanUp;
import com.android.helper.Options;
import com.android.helper.device.Device;
import com.android.helper.device.DeviceApp;
import com.android.helper.device.DisplayInfo;
import com.android.helper.device.Point;
import com.android.helper.device.Position;
import com.android.helper.util.Ln;
import com.android.helper.util.LogUtils;
import com.android.helper.video.SurfaceCapture;
import com.android.helper.video.SurfaceEncoder;
import com.android.helper.video.VirtualDisplayListener;
import com.android.helper.wrappers.ClipboardManager;
import com.android.helper.wrappers.InputManager;
import com.android.helper.wrappers.ServiceManager;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/* JADX INFO: loaded from: classes.dex */
public class Controller implements AsyncProcessor, VirtualDisplayListener {
    static final /* synthetic */ boolean $assertionsDisabled = false;
    private static final int DEFAULT_DEVICE_ID = 0;
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor();
    private static final int POINTER_ID_MOUSE = -1;
    private final CleanUp cleanUp;
    private final boolean clipboardAutosync;
    private final ControlChannel controlChannel;
    private final int displayId;
    private boolean keepDisplayPowerOff;
    private long lastTouchDown;
    private final boolean powerOn;
    private final DeviceMessageSender sender;
    private ExecutorService startAppExecutor;
    private final boolean supportsInputEvents;
    private SurfaceCapture surfaceCapture;
    private Thread thread;
    private UhidManager uhidManager;
    private final KeyCharacterMap charMap = KeyCharacterMap.load(-1);
    private final AtomicBoolean isSettingClipboard = new AtomicBoolean();
    private final AtomicReference<DisplayData> displayData = new AtomicReference<>();
    private final Object displayDataAvailable = new Object();
    private final PointersState pointersState = new PointersState();
    private final MotionEvent.PointerProperties[] pointerProperties = new MotionEvent.PointerProperties[10];
    private final MotionEvent.PointerCoords[] pointerCoords = new MotionEvent.PointerCoords[10];

    private static final class DisplayData {
        private final PositionMapper positionMapper;
        private final int virtualDisplayId;

        private DisplayData(int i, PositionMapper positionMapper) {
            this.virtualDisplayId = i;
            this.positionMapper = positionMapper;
        }
    }

    public Controller(ControlChannel controlChannel, CleanUp cleanUp, Options options) {
        int displayId = options.getDisplayId();
        this.displayId = displayId;
        this.controlChannel = controlChannel;
        this.cleanUp = cleanUp;
        boolean clipboardAutosync = options.getClipboardAutosync();
        this.clipboardAutosync = clipboardAutosync;
        this.powerOn = options.getPowerOn();
        initPointers();
        this.sender = new DeviceMessageSender(controlChannel);
        boolean zSupportsInputEvents = Device.supportsInputEvents(displayId);
        this.supportsInputEvents = zSupportsInputEvents;
        if (!zSupportsInputEvents) {
            Ln.w("Input events are not supported for secondary displays before Android 10");
        }
        ClipboardManager clipboardManager = ServiceManager.getClipboardManager();
        if (clipboardAutosync) {
            if (clipboardManager != null) {
                clipboardManager.addPrimaryClipChangedListener(this::m12lambda$new$0$comandroidhelpercontrolController);
            } else {
                Ln.w("No clipboard manager, copy-paste between device and computer will not work");
            }
        }
    }

    /* JADX INFO: renamed from: lambda$new$0$com-android-helper-control-Controller, reason: not valid java name */
    /* synthetic */ void m12lambda$new$0$comandroidhelpercontrolController() {
        String clipboardText;
        if (this.isSettingClipboard.get() || (clipboardText = Device.getClipboardText()) == null) {
            return;
        }
        this.sender.send(DeviceMessage.createClipboard(clipboardText));
    }

    @Override // com.android.helper.video.VirtualDisplayListener
    public void onNewVirtualDisplay(int i, PositionMapper positionMapper) {
        if (this.displayData.getAndSet(new DisplayData(i, positionMapper)) == null) {
            synchronized (this.displayDataAvailable) {
                this.displayDataAvailable.notify();
            }
        }
    }

    public void setSurfaceCapture(SurfaceCapture surfaceCapture) {
        this.surfaceCapture = surfaceCapture;
    }

    private UhidManager getUhidManager() {
        DisplayInfo displayInfo;
        if (this.uhidManager == null) {
            int i = this.displayId;
            if (Build.VERSION.SDK_INT >= 35 && this.displayId == -1) {
                try {
                    DisplayData displayDataWaitDisplayData = waitDisplayData(1000L);
                    if (displayDataWaitDisplayData != null) {
                        i = displayDataWaitDisplayData.virtualDisplayId;
                    }
                } catch (InterruptedException unused) {
                }
            }
            this.uhidManager = new UhidManager(this.sender, (i <= 0 || (displayInfo = ServiceManager.getDisplayManager().getDisplayInfo(i)) == null) ? null : displayInfo.getUniqueId());
        }
        return this.uhidManager;
    }

    private void initPointers() {
        for (int i = 0; i < 10; i++) {
            MotionEvent.PointerProperties pointerProperties = new MotionEvent.PointerProperties();
            pointerProperties.toolType = 1;
            MotionEvent.PointerCoords pointerCoords = new MotionEvent.PointerCoords();
            pointerCoords.orientation = 0.0f;
            pointerCoords.size = 0.0f;
            this.pointerProperties[i] = pointerProperties;
            this.pointerCoords[i] = pointerCoords;
        }
    }

    private void control() throws Exception {
        int i;
        if (this.powerOn && (i = this.displayId) == 0 && !Device.isScreenOn(i)) {
            Device.pressReleaseKeycode(224, this.displayId, 0);
            SystemClock.sleep(500L);
        }
        boolean zHandleEvent = true;
        while (!Thread.currentThread().isInterrupted() && zHandleEvent) {
            zHandleEvent = handleEvent();
        }
    }

    @Override // com.android.helper.AsyncProcessor
    public void start(final AsyncProcessor.TerminationListener terminationListener) {
        Thread thread = new Thread(() -> m13lambda$start$1$comandroidhelpercontrolController(terminationListener), "control-recv");
        this.thread = thread;
        thread.start();
        this.sender.start();
    }

    /* JADX INFO: renamed from: lambda$start$1$com-android-helper-control-Controller, reason: not valid java name */
    /* synthetic */ void m13lambda$start$1$comandroidhelpercontrolController(AsyncProcessor.TerminationListener terminationListener) {
        try {
            control();
        } catch (Exception e) {
            Ln.e("Controller error", e);
        } finally {
            Ln.d("Controller stopped");
            if (this.uhidManager != null) {
                this.uhidManager.closeAll();
            }
            terminationListener.onTerminated(true);
        }
    }

    @Override // com.android.helper.AsyncProcessor
    public void stop() {
        Thread thread = this.thread;
        if (thread != null) {
            thread.interrupt();
        }
        this.sender.stop();
    }

    @Override // com.android.helper.AsyncProcessor
    public void join() throws InterruptedException {
        Thread thread = this.thread;
        if (thread != null) {
            thread.join();
        }
        this.sender.join();
    }

    private boolean handleEvent() throws Exception {
        try {
            ControlMessage controlMessageRecv = this.controlChannel.recv();
            switch (controlMessageRecv.getType()) {
                case 0:
                    if (!this.supportsInputEvents) {
                        return true;
                    }
                    injectKeycode(controlMessageRecv.getAction(), controlMessageRecv.getKeycode(), controlMessageRecv.getRepeat(), controlMessageRecv.getMetaState());
                    return true;
                case 1:
                    if (!this.supportsInputEvents) {
                        return true;
                    }
                    injectText(controlMessageRecv.getText());
                    return true;
                case 2:
                    if (this.supportsInputEvents) {
                        injectTouch(controlMessageRecv.getAction(), controlMessageRecv.getPointerId(), controlMessageRecv.getPosition(), controlMessageRecv.getPressure(), controlMessageRecv.getActionButton(), controlMessageRecv.getButtons());
                        return true;
                    }
                    break;
                case ControlMessage.TYPE_INJECT_SCROLL_EVENT /* 3 */:
                    if (this.supportsInputEvents) {
                        injectScroll(controlMessageRecv.getPosition(), controlMessageRecv.getHScroll(), controlMessageRecv.getVScroll(), controlMessageRecv.getButtons());
                    }
                    break;
                case ControlMessage.TYPE_BACK_OR_SCREEN_ON /* 4 */:
                    if (this.supportsInputEvents) {
                        pressBackOrTurnScreenOn(controlMessageRecv.getAction());
                    }
                    break;
                case ControlMessage.TYPE_EXPAND_NOTIFICATION_PANEL /* 5 */:
                    Device.expandNotificationPanel();
                    break;
                case ControlMessage.TYPE_EXPAND_SETTINGS_PANEL /* 6 */:
                    Device.expandSettingsPanel();
                    break;
                case ControlMessage.TYPE_COLLAPSE_PANELS /* 7 */:
                    Device.collapsePanels();
                    break;
                case ControlMessage.TYPE_GET_CLIPBOARD /* 8 */:
                    getClipboard(controlMessageRecv.getCopyKey());
                    break;
                case ControlMessage.TYPE_SET_CLIPBOARD /* 9 */:
                    setClipboard(controlMessageRecv.getText(), controlMessageRecv.getPaste(), controlMessageRecv.getSequence());
                    break;
                case 10:
                    if (this.supportsInputEvents) {
                        setDisplayPower(controlMessageRecv.getOn());
                    }
                    break;
                case ControlMessage.TYPE_ROTATE_DEVICE /* 11 */:
                    Device.rotateDevice(getActionDisplayId());
                    break;
                case 12:
                    getUhidManager().open(controlMessageRecv.getId(), controlMessageRecv.getVendorId(), controlMessageRecv.getProductId(), controlMessageRecv.getText(), controlMessageRecv.getData());
                    break;
                case ControlMessage.TYPE_UHID_INPUT /* 13 */:
                    getUhidManager().writeInput(controlMessageRecv.getId(), controlMessageRecv.getData());
                    break;
                case ControlMessage.TYPE_UHID_DESTROY /* 14 */:
                    getUhidManager().close(controlMessageRecv.getId());
                    break;
                case ControlMessage.TYPE_OPEN_HARD_KEYBOARD_SETTINGS /* 15 */:
                    openHardKeyboardSettings();
                    break;
                case ControlMessage.TYPE_START_APP /* 16 */:
                    startAppAsync(controlMessageRecv.getText());
                    break;
                case ControlMessage.TYPE_RESET_VIDEO /* 17 */:
                    resetVideo();
                    break;
                case ControlMessage.TYPE_REQUEST_KEYFRAME /* 18 */:
                    Ln.d("KeyframeTrace controller-request-keyframe");
                    SurfaceEncoder.requestKeyFrame();
                    break;
                case ControlMessage.TYPE_SET_BITRATE /* 19 */:
                    SurfaceEncoder.setVideoBitrate(controlMessageRecv.getBitrate());
                    break;
            }
            return true;
        } catch (IOException unused) {
            return false;
        }
    }

    private boolean injectKeycode(int i, int i2, int i3, int i4) {
        if (this.keepDisplayPowerOff && i == 1 && (i2 == 26 || i2 == 224)) {
            scheduleDisplayPowerOff(this.displayId);
        }
        return injectKeyEvent(i, i2, i3, i4, 0);
    }

    private boolean injectChar(char c) {
        String strDecompose = KeyComposition.decompose(c);
        KeyEvent[] events = this.charMap.getEvents(strDecompose != null ? strDecompose.toCharArray() : new char[]{c});
        if (events == null) {
            return false;
        }
        int actionDisplayId = getActionDisplayId();
        for (KeyEvent keyEvent : events) {
            if (!Device.injectEvent(keyEvent, actionDisplayId, 0)) {
                return false;
            }
        }
        return true;
    }

    private int injectText(String str) {
        int i = 0;
        for (char c : str.toCharArray()) {
            if (injectChar(c)) {
                i++;
            } else {
                Ln.w("Could not inject char u+" + String.format("%04x", Integer.valueOf(c)));
            }
        }
        return i;
    }

    private Pair<Point, Integer> getEventPointAndDisplayId(Position position) {
        Point point;
        int i;
        DisplayData displayData = this.displayData.get();
        if (displayData != null) {
            point = displayData.positionMapper.map(position);
            if (point != null) {
                i = displayData.virtualDisplayId;
            } else {
                if (!Ln.isEnabled(Ln.Level.VERBOSE)) {
                    return null;
                }
                Ln.v("Ignore positional event generated for size " + position.getScreenSize() + " (current size is " + displayData.positionMapper.getVideoSize() + ")");
                return null;
            }
        } else {
            point = position.getPoint();
            i = this.displayId;
        }
        return Pair.create(point, Integer.valueOf(i));
    }

    /* JADX WARN: Type inference failed for: r1v11 */
    /* JADX WARN: Type inference failed for: r1v12, types: [boolean, int] */
    /* JADX WARN: Type inference failed for: r1v14 */
    private boolean injectTouch(int i, long j, Position position, float f, int i2, int i3) {
        int i4;
        int i5;
        int i6;
        int i7;
        int i8;
        int i9;
        int i10;
        int i11;
        int r1;
        boolean z;
        int i12 = i;
        long jUptimeMillis = SystemClock.uptimeMillis();
        Pair<Point, Integer> eventPointAndDisplayId = getEventPointAndDisplayId(position);
        if (eventPointAndDisplayId == null) {
            return false;
        }
        Point point = (Point) eventPointAndDisplayId.first;
        int iIntValue = ((Integer) eventPointAndDisplayId.second).intValue();
        int pointerIndex = this.pointersState.getPointerIndex(j);
        if (pointerIndex == -1) {
            Ln.w("Too many pointers for touch event");
            return false;
        }
        Pointer pointer = this.pointersState.get(pointerIndex);
        pointer.setPoint(point);
        pointer.setPressure(f);
        boolean z2 = ((i2 | i3) & (-2)) != 0;
        if (j == -1 && (i12 == 7 || z2)) {
            this.pointerProperties[pointerIndex].toolType = 3;
            pointer.setUp(i3 == 0);
            i5 = i3;
            i4 = 8194;
        } else {
            this.pointerProperties[pointerIndex].toolType = 1;
            pointer.setUp(i12 == 1);
            i4 = 4098;
            i5 = 0;
        }
        int iUpdate = this.pointersState.update(this.pointerProperties, this.pointerCoords);
        if (iUpdate == 1) {
            if (i12 == 0) {
                this.lastTouchDown = jUptimeMillis;
            }
        } else if (i12 == 1) {
            i12 = (pointerIndex << 8) | 6;
        } else if (i12 == 0) {
            i12 = (pointerIndex << 8) | 5;
        }
        if (Build.VERSION.SDK_INT < 23 || i4 != 8194) {
            i6 = i4;
            i7 = iUpdate;
            i8 = iIntValue;
        } else {
            if (i12 == 0) {
                if (i2 == i5) {
                    i9 = i4;
                    i10 = iUpdate;
                    z = true;
                    i11 = iIntValue;
                    r1 = 0;
                    if (!Device.injectEvent(MotionEvent.obtain(this.lastTouchDown, jUptimeMillis, 0, i10, this.pointerProperties, this.pointerCoords, 0, i5, 1.0f, 1.0f, 0, 0, i9, 0), i11, 0)) {
                        return false;
                    }
                } else {
                    i9 = i4;
                    i10 = iUpdate;
                    i11 = iIntValue;
                    r1 = 0;
                    z = true;
                }
                MotionEvent motionEventObtain = MotionEvent.obtain(this.lastTouchDown, jUptimeMillis, 11, i10, this.pointerProperties, this.pointerCoords, 0, i5, 1.0f, 1.0f, 0, 0, i9, 0);
                return (InputManager.setActionButton(motionEventObtain, i2) && Device.injectEvent(motionEventObtain, i11, r1)) ? z : false;
            }
            i6 = i4;
            i7 = iUpdate;
            if (i12 == 1) {
                MotionEvent motionEventObtain2 = MotionEvent.obtain(this.lastTouchDown, jUptimeMillis, 12, i7, this.pointerProperties, this.pointerCoords, 0, i5, 1.0f, 1.0f, 0, 0, i6, 0);
                if (InputManager.setActionButton(motionEventObtain2, i2) && Device.injectEvent(motionEventObtain2, iIntValue, 0)) {
                    return i5 != 0 || Device.injectEvent(MotionEvent.obtain(this.lastTouchDown, jUptimeMillis, 1, i7, this.pointerProperties, this.pointerCoords, 0, i5, 1.0f, 1.0f, 0, 0, i6, 0), iIntValue, 0);
                }
                return false;
            }
            i8 = iIntValue;
        }
        return Device.injectEvent(MotionEvent.obtain(this.lastTouchDown, jUptimeMillis, i12, i7, this.pointerProperties, this.pointerCoords, 0, i5, 1.0f, 1.0f, 0, 0, i6, 0), i8, 0);
    }

    private boolean injectScroll(Position position, float f, float f2, int i) {
        long jUptimeMillis = SystemClock.uptimeMillis();
        Pair<Point, Integer> eventPointAndDisplayId = getEventPointAndDisplayId(position);
        if (eventPointAndDisplayId == null) {
            return false;
        }
        Point point = (Point) eventPointAndDisplayId.first;
        int iIntValue = ((Integer) eventPointAndDisplayId.second).intValue();
        this.pointerProperties[0].id = 0;
        MotionEvent.PointerCoords pointerCoords = this.pointerCoords[0];
        pointerCoords.x = point.getX();
        pointerCoords.y = point.getY();
        pointerCoords.setAxisValue(10, f);
        pointerCoords.setAxisValue(9, f2);
        return Device.injectEvent(MotionEvent.obtain(this.lastTouchDown, jUptimeMillis, 8, 1, this.pointerProperties, this.pointerCoords, 0, i, 1.0f, 1.0f, 0, 0, 8194, 0), iIntValue, 0);
    }

    private static void scheduleDisplayPowerOff(final int i) {
        EXECUTOR.schedule(new Runnable() { // from class: com.android.helper.control.Controller$$ExternalSyntheticLambda3
            @Override // java.lang.Runnable
            public final void run() {
                Controller.lambda$scheduleDisplayPowerOff$2(i);
            }
        }, 200L, TimeUnit.MILLISECONDS);
    }

    static /* synthetic */ void lambda$scheduleDisplayPowerOff$2(int i) {
        Ln.i("Forcing display off");
        Device.setDisplayPower(i, false);
    }

    private boolean pressBackOrTurnScreenOn(int i) {
        int i2 = this.displayId;
        if (i2 == -1 || Device.isScreenOn(i2)) {
            return injectKeyEvent(i, 4, 0, 0, 0);
        }
        if (i != 0) {
            return true;
        }
        if (this.keepDisplayPowerOff) {
            scheduleDisplayPowerOff(this.displayId);
        }
        return pressReleaseKeycode(26, 0);
    }

    private void getClipboard(int i) {
        String clipboardText;
        if (i != 0 && Build.VERSION.SDK_INT >= 24 && this.supportsInputEvents) {
            pressReleaseKeycode(i == 1 ? 278 : 277, 2);
        }
        if ((!this.clipboardAutosync || i == 0) && (clipboardText = Device.getClipboardText()) != null) {
            this.sender.send(DeviceMessage.createClipboard(clipboardText));
        }
    }

    private boolean setClipboard(String str, boolean z, long j) {
        this.isSettingClipboard.set(true);
        boolean clipboardText = Device.setClipboardText(str);
        this.isSettingClipboard.set(false);
        if (clipboardText) {
            Ln.i("Device clipboard set");
        }
        if (z && Build.VERSION.SDK_INT >= 24 && this.supportsInputEvents) {
            pressReleaseKeycode(279, 0);
        }
        if (j != 0) {
            this.sender.send(DeviceMessage.createAckClipboard(j));
        }
        return clipboardText;
    }

    private void openHardKeyboardSettings() {
        ServiceManager.getActivityManager().startActivity(new Intent("android.settings.HARD_KEYBOARD_SETTINGS"));
    }

    private boolean injectKeyEvent(int i, int i2, int i3, int i4, int i5) {
        return Device.injectKeyEvent(i, i2, i3, i4, getActionDisplayId(), i5);
    }

    private boolean pressReleaseKeycode(int i, int i2) {
        return Device.pressReleaseKeycode(i, getActionDisplayId(), i2);
    }

    private int getActionDisplayId() {
        int i = this.displayId;
        if (i != -1) {
            return i;
        }
        DisplayData displayData = this.displayData.get();
        if (displayData == null) {
            return 0;
        }
        return displayData.virtualDisplayId;
    }

    private void startAppAsync(final String str) {
        if (this.startAppExecutor == null) {
            this.startAppExecutor = Executors.newSingleThreadExecutor();
        }
        this.startAppExecutor.submit(() -> m14lambda$startAppAsync$3$comandroidhelpercontrolController(str));
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX INFO: renamed from: startApp, reason: merged with bridge method [inline-methods] */
    public void m14lambda$startAppAsync$3$comandroidhelpercontrolController(String str) {
        DeviceApp deviceAppFindByPackageName;
        boolean zStartsWith = str.startsWith("+");
        if (zStartsWith) {
            str = str.substring(1);
        }
        if (str.startsWith("?")) {
            str = str.substring(1);
            Ln.i("Processing Android apps... (this may take some time)");
            List<DeviceApp> listFindByName = Device.findByName(str);
            if (listFindByName.isEmpty()) {
                Ln.w("No app found for name \"" + str + "\"");
                return;
            }
            if (listFindByName.size() > 1) {
                Ln.w(LogUtils.buildAppListMessage("No unique app found for name \"" + str + "\":", listFindByName));
                return;
            }
            deviceAppFindByPackageName = listFindByName.get(0);
        } else {
            deviceAppFindByPackageName = Device.findByPackageName(str);
            if (deviceAppFindByPackageName == null) {
                Ln.w("No app found for package \"" + str + "\"");
                return;
            }
        }
        int startAppDisplayId = getStartAppDisplayId();
        if (startAppDisplayId == -1) {
            Ln.e("No known display id to start app \"" + str + "\"");
            return;
        }
        Ln.i("Starting app \"" + deviceAppFindByPackageName.getName() + "\" [" + deviceAppFindByPackageName.getPackageName() + "] on display " + startAppDisplayId + "...");
        Device.startApp(deviceAppFindByPackageName.getPackageName(), startAppDisplayId, zStartsWith);
    }

    private int getStartAppDisplayId() {
        int i = this.displayId;
        if (i != -1) {
            return i;
        }
        try {
            DisplayData displayDataWaitDisplayData = waitDisplayData(1000L);
            if (displayDataWaitDisplayData != null) {
                return displayDataWaitDisplayData.virtualDisplayId;
            }
        } catch (InterruptedException unused) {
        }
        return -1;
    }

    private DisplayData waitDisplayData(long j) throws InterruptedException {
        long jCurrentTimeMillis = System.currentTimeMillis() + j;
        synchronized (this.displayDataAvailable) {
            DisplayData displayData = this.displayData.get();
            while (displayData == null) {
                long jCurrentTimeMillis2 = jCurrentTimeMillis - System.currentTimeMillis();
                if (jCurrentTimeMillis2 < 0) {
                    return null;
                }
                if (jCurrentTimeMillis2 > 0) {
                    this.displayDataAvailable.wait(jCurrentTimeMillis2);
                }
                displayData = this.displayData.get();
            }
            return displayData;
        }
    }

    private void setDisplayPower(boolean z) {
        int i = this.displayId;
        boolean z2 = false;
        if (i == -1) {
            i = 0;
        }
        if (Device.setDisplayPower(i, z)) {
            if (this.displayId != -1 && !z) {
                z2 = true;
            }
            this.keepDisplayPowerOff = z2;
            Ln.i("Device display turned ".concat(z ? "on" : "off"));
            CleanUp cleanUp = this.cleanUp;
            if (cleanUp != null) {
                cleanUp.setRestoreDisplayPower(!z);
            }
        }
    }

    private void resetVideo() {
        if (this.surfaceCapture != null) {
            Ln.i("KeyframeTrace video-reset-request");
            this.surfaceCapture.requestInvalidate();
        } else {
            Ln.w("KeyframeTrace video-reset-skipped: no surface capture");
        }
    }
}
