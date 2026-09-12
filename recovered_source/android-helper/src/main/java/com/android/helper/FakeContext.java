package com.android.helper;

import android.content.AttributionSource;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.IContentProvider;
import android.content.SharedPreferences;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Binder;
import com.android.helper.util.Ln;
import com.android.helper.wrappers.ServiceManager;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/* JADX INFO: loaded from: classes.dex */
public final class FakeContext extends ContextWrapper {
    private static final FakeContext INSTANCE = new FakeContext();
    public static final String PACKAGE_NAME = "com.android.shell";
    public static final int ROOT_UID = 0;
    private final ContentResolver contentResolver;

    @Override // android.content.ContextWrapper, android.content.Context
    public Context createPackageContext(String str, int i) {
        return this;
    }

    @Override // android.content.ContextWrapper, android.content.Context
    public Context getApplicationContext() {
        return this;
    }

    @Override // android.content.ContextWrapper, android.content.Context
    public int getDeviceId() {
        return 0;
    }

    public static FakeContext get() {
        return INSTANCE;
    }

    private FakeContext() {
        super(Workarounds.getSystemContext());
        this.contentResolver = new ContentResolver(this) { // from class: com.android.helper.FakeContext.1
            protected IContentProvider acquireUnstableProvider(Context context, String str) {
                return null;
            }

            public boolean releaseProvider(IContentProvider iContentProvider) {
                return false;
            }

            public boolean releaseUnstableProvider(IContentProvider iContentProvider) {
                return false;
            }

            public void unstableProviderDied(IContentProvider iContentProvider) {
            }

            protected IContentProvider acquireProvider(Context context, String str) {
                return ServiceManager.getActivityManager().getContentProviderExternal(str, new Binder());
            }
        };
    }

    @Override // android.content.ContextWrapper, android.content.Context
    public File getDataDir() {
        return new File("/data/local/tmp");
    }

    @Override // android.content.ContextWrapper, android.content.Context
    public File getFilesDir() {
        File file = new File("/data/local/tmp/scrcpy_files");
        if (!file.exists()) {
            file.mkdirs();
        }
        return file;
    }

    @Override // android.content.ContextWrapper, android.content.Context
    public File getCacheDir() {
        File file = new File("/data/local/tmp/scrcpy_cache");
        if (!file.exists()) {
            file.mkdirs();
        }
        return file;
    }

    @Override // android.content.ContextWrapper, android.content.Context
    public File getDatabasePath(String str) {
        File file = new File("/data/local/tmp/scrcpy_db");
        if (!file.exists()) {
            file.mkdirs();
        }
        return new File(file, str);
    }

    @Override // android.content.ContextWrapper, android.content.Context
    public SQLiteDatabase openOrCreateDatabase(String str, int i, SQLiteDatabase.CursorFactory cursorFactory) {
        return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(str), cursorFactory);
    }

    @Override // android.content.ContextWrapper, android.content.Context
    public SQLiteDatabase openOrCreateDatabase(String str, int i, SQLiteDatabase.CursorFactory cursorFactory, DatabaseErrorHandler databaseErrorHandler) {
        return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(str).getPath(), cursorFactory, databaseErrorHandler);
    }

    @Override // android.content.ContextWrapper, android.content.Context
    public File getDir(String str, int i) {
        File file = new File("/data/local/tmp/scrcpy_" + str);
        if (!file.exists()) {
            file.mkdirs();
        }
        return file;
    }

    @Override // android.content.ContextWrapper, android.content.Context
    public SharedPreferences getSharedPreferences(String str, int i) {
        final Map<String, Object> map = new ConcurrentHashMap<>();
        return new SharedPreferences() {
            @Override // android.content.SharedPreferences
            public Set<String> getStringSet(String str2, Set<String> set) {
                return set;
            }

            @Override // android.content.SharedPreferences
            public void registerOnSharedPreferenceChangeListener(SharedPreferences.OnSharedPreferenceChangeListener onSharedPreferenceChangeListener) {
            }

            @Override // android.content.SharedPreferences
            public void unregisterOnSharedPreferenceChangeListener(SharedPreferences.OnSharedPreferenceChangeListener onSharedPreferenceChangeListener) {
            }

            @Override // android.content.SharedPreferences
            public Map<String, ?> getAll() {
                return map;
            }

            @Override // android.content.SharedPreferences
            public String getString(String str2, String str3) {
                Object obj = map.get(str2);
                return obj instanceof String ? (String) obj : str3;
            }

            @Override // android.content.SharedPreferences
            public int getInt(String str2, int i2) {
                Object obj = map.get(str2);
                return obj instanceof Integer ? ((Integer) obj).intValue() : i2;
            }

            @Override // android.content.SharedPreferences
            public long getLong(String str2, long j) {
                Object obj = map.get(str2);
                return obj instanceof Long ? ((Long) obj).longValue() : j;
            }

            @Override // android.content.SharedPreferences
            public float getFloat(String str2, float f) {
                Object obj = map.get(str2);
                return obj instanceof Float ? ((Float) obj).floatValue() : f;
            }

            @Override // android.content.SharedPreferences
            public boolean getBoolean(String str2, boolean z) {
                Object obj = map.get(str2);
                return obj instanceof Boolean ? ((Boolean) obj).booleanValue() : z;
            }

            @Override // android.content.SharedPreferences
            public boolean contains(String str2) {
                return map.containsKey(str2);
            }

            @Override // android.content.SharedPreferences
            public SharedPreferences.Editor edit() {
                return new SharedPreferences.Editor() {
                    @Override // android.content.SharedPreferences.Editor
                    public void apply() {
                    }

                    @Override // android.content.SharedPreferences.Editor
                    public boolean commit() {
                        return true;
                    }

                    @Override // android.content.SharedPreferences.Editor
                    public SharedPreferences.Editor putStringSet(String str2, Set<String> set) {
                        return this;
                    }

                    @Override // android.content.SharedPreferences.Editor
                    public SharedPreferences.Editor putString(String str2, String str3) {
                        if (str3 != null) {
                            map.put(str2, str3);
                        }
                        return this;
                    }

                    @Override // android.content.SharedPreferences.Editor
                    public SharedPreferences.Editor putInt(String str2, int i2) {
                        map.put(str2, Integer.valueOf(i2));
                        return this;
                    }

                    @Override // android.content.SharedPreferences.Editor
                    public SharedPreferences.Editor putLong(String str2, long j) {
                        map.put(str2, Long.valueOf(j));
                        return this;
                    }

                    @Override // android.content.SharedPreferences.Editor
                    public SharedPreferences.Editor putFloat(String str2, float f) {
                        map.put(str2, Float.valueOf(f));
                        return this;
                    }

                    @Override // android.content.SharedPreferences.Editor
                    public SharedPreferences.Editor putBoolean(String str2, boolean z) {
                        map.put(str2, Boolean.valueOf(z));
                        return this;
                    }

                    @Override // android.content.SharedPreferences.Editor
                    public SharedPreferences.Editor remove(String str2) {
                        map.remove(str2);
                        return this;
                    }

                    @Override // android.content.SharedPreferences.Editor
                    public SharedPreferences.Editor clear() {
                        map.clear();
                        return this;
                    }
                };
            }
        };
    }

    @Override // android.content.ContextWrapper, android.content.Context
    public String getPackageName() {
        return PACKAGE_NAME;
    }

    @Override // android.content.ContextWrapper, android.content.Context
    public String getOpPackageName() {
        return PACKAGE_NAME;
    }

    @Override // android.content.ContextWrapper, android.content.Context
    public AttributionSource getAttributionSource() {
        AttributionSource.Builder builderM = FakeContext$$ExternalSyntheticApiModelOutline0.m(2000);
        builderM.setPackageName(PACKAGE_NAME);
        return builderM.build();
    }

    @Override // android.content.ContextWrapper, android.content.Context
    public ContentResolver getContentResolver() {
        return this.contentResolver;
    }

    @Override // android.content.ContextWrapper, android.content.Context
    public Object getSystemService(String str) {
        Object systemService = super.getSystemService(str);
        if (systemService == null) {
            return null;
        }
        if (!"clipboard".equals(str) && !"semclipboard".equals(str) && !"activity".equals(str) && !"power".equals(str)) {
            return systemService;
        }
        try {
            Field declaredField = systemService.getClass().getDeclaredField("mContext");
            declaredField.setAccessible(true);
            declaredField.set(systemService, this);
            return systemService;
        } catch (ReflectiveOperationException e) {
            if ("power".equals(str)) {
                Ln.w("Could not inject FakeContext to PowerManager: " + e.getMessage());
                return systemService;
            }
            throw new RuntimeException(e);
        }
    }
}
