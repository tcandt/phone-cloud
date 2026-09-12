package com.android.helper.device;

/* JADX INFO: loaded from: classes.dex */
public enum Orientation {
    Orient0("0"),
    Orient90("90"),
    Orient180("180"),
    Orient270("270"),
    Flip0("flip0"),
    Flip90("flip90"),
    Flip180("flip180"),
    Flip270("flip270");

    static final /* synthetic */ boolean $assertionsDisabled = false;
    private final String name;

    public enum Lock {
        Unlocked,
        LockedInitial,
        LockedValue
    }

    Orientation(String str) {
        this.name = str;
    }

    public static Orientation getByName(String str) {
        for (Orientation orientation : values()) {
            if (orientation.name.equals(str)) {
                return orientation;
            }
        }
        throw new IllegalArgumentException("Unknown orientation: " + str);
    }

    public static Orientation fromRotation(int i) {
        return values()[(4 - i) % 4];
    }

    public boolean isFlipped() {
        return (ordinal() & 4) != 0;
    }

    public int getRotation() {
        return ordinal() & 3;
    }
}
