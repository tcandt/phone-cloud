package com.android.helper.wrappers;

import android.view.InputEvent;
import android.view.MotionEvent;
import com.android.helper.FakeContext;
import com.android.helper.util.Ln;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/* JADX INFO: loaded from: classes.dex */
public final class InputManager {
    public static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;
    public static final int INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 2;
    public static final int INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1;
    private static Method addUniqueIdAssociationByPortMethod;
    private static Method injectInputEventMethod;
    private static Method removeUniqueIdAssociationByPortMethod;
    private static Method setActionButtonMethod;
    private static Method setDisplayIdMethod;
    private long lastPermissionLogDate;
    private final android.hardware.input.InputManager manager;

    static InputManager create() {
        return new InputManager((android.hardware.input.InputManager) FakeContext.get().getSystemService("input"));
    }

    private InputManager(android.hardware.input.InputManager inputManager) {
        this.manager = inputManager;
    }

    private static Method getInjectInputEventMethod() throws NoSuchMethodException {
        if (injectInputEventMethod == null) {
            injectInputEventMethod = android.hardware.input.InputManager.class.getMethod("injectInputEvent", InputEvent.class, Integer.TYPE);
        }
        return injectInputEventMethod;
    }

    public boolean injectInputEvent(InputEvent inputEvent, int i) {
        String message;
        try {
            return ((Boolean) getInjectInputEventMethod().invoke(this.manager, inputEvent, Integer.valueOf(i))).booleanValue();
        } catch (ReflectiveOperationException e) {
            if ((e instanceof InvocationTargetException) && (e.getCause() instanceof SecurityException) && (message = e.getCause().getMessage()) != null && message.contains("INJECT_EVENTS permission")) {
                long jCurrentTimeMillis = System.currentTimeMillis();
                if (this.lastPermissionLogDate <= jCurrentTimeMillis - 3000) {
                    Ln.e(message);
                    Ln.e("Make sure you have enabled \"USB debugging (Security Settings)\" and then rebooted your device.");
                    this.lastPermissionLogDate = jCurrentTimeMillis;
                }
                return false;
            }
            Ln.e("Could not invoke method", e);
            return false;
        }
    }

    private static Method getSetDisplayIdMethod() throws NoSuchMethodException {
        if (setDisplayIdMethod == null) {
            setDisplayIdMethod = InputEvent.class.getMethod("setDisplayId", Integer.TYPE);
        }
        return setDisplayIdMethod;
    }

    public static boolean setDisplayId(InputEvent inputEvent, int i) {
        try {
            getSetDisplayIdMethod().invoke(inputEvent, Integer.valueOf(i));
            return true;
        } catch (ReflectiveOperationException e) {
            Ln.e("Cannot associate a display id to the input event", e);
            return false;
        }
    }

    private static Method getSetActionButtonMethod() throws NoSuchMethodException {
        if (setActionButtonMethod == null) {
            setActionButtonMethod = MotionEvent.class.getMethod("setActionButton", Integer.TYPE);
        }
        return setActionButtonMethod;
    }

    public static boolean setActionButton(MotionEvent motionEvent, int i) {
        try {
            getSetActionButtonMethod().invoke(motionEvent, Integer.valueOf(i));
            return true;
        } catch (ReflectiveOperationException e) {
            Ln.e("Cannot set action button on MotionEvent", e);
            return false;
        }
    }

    private static Method getAddUniqueIdAssociationByPortMethod() throws NoSuchMethodException {
        if (addUniqueIdAssociationByPortMethod == null) {
            addUniqueIdAssociationByPortMethod = android.hardware.input.InputManager.class.getMethod("addUniqueIdAssociationByPort", String.class, String.class);
        }
        return addUniqueIdAssociationByPortMethod;
    }

    public void addUniqueIdAssociationByPort(String str, String str2) {
        try {
            getAddUniqueIdAssociationByPortMethod().invoke(this.manager, str, str2);
        } catch (ReflectiveOperationException e) {
            Ln.e("Cannot add unique id association by port", e);
        }
    }

    private static Method getRemoveUniqueIdAssociationByPortMethod() throws NoSuchMethodException {
        if (removeUniqueIdAssociationByPortMethod == null) {
            removeUniqueIdAssociationByPortMethod = android.hardware.input.InputManager.class.getMethod("removeUniqueIdAssociationByPort", String.class);
        }
        return removeUniqueIdAssociationByPortMethod;
    }

    public void removeUniqueIdAssociationByPort(String str) {
        try {
            getRemoveUniqueIdAssociationByPortMethod().invoke(this.manager, str);
        } catch (ReflectiveOperationException e) {
            Ln.e("Cannot remove unique id association by port", e);
        }
    }
}
