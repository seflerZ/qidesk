package com.qihua.bVNC;

import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.multidex.MultiDex;
import androidx.multidex.MultiDexApplication;

import com.qihua.bVNC.theme.NightMode;

//import com.umeng.commonsdk.UMConfigure;

import java.lang.ref.WeakReference;

public class App extends MultiDexApplication {

    public static boolean debugLog = false;
    private static WeakReference<Context> context;
    private Database database;

    public static Context getContext() {
        return context.get();
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(getBaseContext());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
        // Apply the user's selected day/night theme before any
        // activity is created. The themeModeType preference is the
        // single source of truth — ConnectionGridActivity.onCreate
        // used to do this, but only ran when the user actually
        // entered the home screen, so picking "Dark" in Global
        // Preferences and re-launching the app didn't reach the
        // AppCompatDelegate at all on a cold start. Doing it here
        // means the values-night/ resource set + AppCompat flag
        // are in place before any activity inflates a view.
        applyThemeModeFromPrefs();
        Constants.DEFAULT_PROTOCOL_PORT = Utils.getDefaultPort(this);
        database = new Database(this);
        context = new WeakReference<Context>(this);
        debugLog = Utils.querySharedPreferenceBoolean(getApplicationContext(), "moreDebugLoggingTag");
    }

    /**
     * Read the themeModeType preference and push the matching
     * AppCompat night mode. Values: "light" → MODE_NIGHT_NO,
     * "night" → MODE_NIGHT_YES, anything else (incl. "auto" /
     * unset) → MODE_NIGHT_FOLLOW_SYSTEM. Also keeps NightMode.APP_NIGHT_MODE
     * in sync so callers using getAppNightMode() see the same value.
     */
    private void applyThemeModeFromPrefs() {
        String mode = Utils.querySharedPreferenceString(
                getApplicationContext(), Constants.themeModeType, "auto");
        int appCompatMode;
        switch (mode) {
            case "light":
                appCompatMode = AppCompatDelegate.MODE_NIGHT_NO;
                break;
            case "night":
                appCompatMode = AppCompatDelegate.MODE_NIGHT_YES;
                break;
            default:
                appCompatMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                break;
        }
        AppCompatDelegate.setDefaultNightMode(appCompatMode);
        NightMode.setAppNightMode(mode);
    }

    public Database getDatabase() {
        return database;
    }
}
