package com.qihua.bVNC;

import android.app.Activity;
import android.app.Application.ActivityLifecycleCallbacks;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
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

        // Android 16 (API 36) deprecated and disabled
        // R.attr.windowOptOutEdgeToEdgeEnforcement for any app
        // targeting API 36, so android:statusBarColor in styles.xml
        // is silently ignored on those devices and the status bar
        // becomes transparent with the system itself drawing it.
        // Inject a thin colored View at the top of every activity's
        // content frame so the status bar visually matches the
        // ActionBar (colorPrimary). The View's height is taken from
        // the system-bars inset at runtime, so it adapts to cutouts
        // and notches; if no inset is reported (very old platform
        // fallback), we leave it at 0 instead of guessing.
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                applyStatusBarOverlay(activity);
            }

            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

    /**
     * Inject a colored View at the top of the activity's DecorView sized to
     * the status-bars inset. The View sits inside the DecorView (above any
     * setContentView(...) content) so it appears in the region normally
     * covered by the system status bar; the system-drawn scrim on top is
     * transparent by default with edge-to-edge, so our color shows through.
     * Idempotent across config-change recreations.
     */
    private void applyStatusBarOverlay(Activity activity) {
        View decorView = activity.getWindow().getDecorView();
        if (!(decorView instanceof ViewGroup)) {
            return;
        }
        ViewGroup decor = (ViewGroup) decorView;

        // Idempotent across config-change recreations.
        if (decor.findViewById(R.id.statusBarOverlay) != null) {
            return;
        }

        int color;
        TypedValue tv = new TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.colorPrimary, tv, true)) {
            // Composite grey_overlay (5% opaque black) on top of colorPrimary
            // so the status bar band picks up the same subtle tint the
            // QiHua theme uses for elevation overlays. SRC_OVER:
            // result = src * srcA + dst * (1 - srcA).
            int primaryColor = tv.data;
            int greyOverlay = ContextCompat.getColor(activity, R.color.grey_overlay);
            color = ColorUtils.compositeColors(greyOverlay, primaryColor);
        } else {
            color = 0xFF313131;
        }

        View overlay = new View(activity);
        overlay.setId(R.id.statusBarOverlay);
        overlay.setBackgroundColor(color);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        lp.gravity = Gravity.TOP;
        overlay.setLayoutParams(lp);
        decor.addView(overlay);

        // Tint the ActionBar background to the same composite so the
        // status-bar overlay and the ActionBar read as one continuous strip
        // without a visible boundary at the seam.
        if (activity instanceof AppCompatActivity) {
            androidx.appcompat.app.ActionBar actionBar =
                    ((AppCompatActivity) activity).getSupportActionBar();
            if (actionBar != null) {
                actionBar.setBackgroundDrawable(new ColorDrawable(color));
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(overlay, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) v.getLayoutParams();
            params.height = Math.max(bars.top, 0);
            v.setLayoutParams(params);
            return insets;
        });

        // Edge-to-edge on Android 15+ (forced on API 36+) makes the
        // ActionBarOverlayLayout's ContentFrameLayout span the full
        // DecorView, then draw the ActionBar as an overlay on top of
        // it — so calls to setContentView(...) land at y=0 of the
        // screen and the first row of content slides under the
        // ActionBar. AppCompat 1.6.1 didn't fix this; the ActionBar is
        // always overlay in the post-edge-to-edge layout flow. We
        // resolve ?attr/actionBarSize from the activity's theme and
        // add it to statusBars.top as padding-top on android.R.id.content
        // so the user's setContentView root is naturally below the
        // ActionBar — no per-layout 33dp spacer required. Cutout and
        // nav-bar insets handled the same way so rotated / cutout
        // devices don't fight this either.
        //
        // BUT: RemoteCanvasActivity uses AppTheme (NoActionBar) and
        // needs full-screen real estate — adding actionBarSize there
        // would leave a phantom 48dp gap. Only activities that actually
        // host an ActionBar get the +actionBarSize offset; full-screen
        // activities (RemoteCanvas) just get the bare statusBars.top.
        // getSupportActionBar() returns null for NoActionBar themes
        // and for activities that called requestWindowFeature(NO_TITLE),
        // which is the gate we want.
        int actionBarHeight = 0;
        boolean hasActionBar = false;
        if (activity instanceof AppCompatActivity) {
            androidx.appcompat.app.ActionBar ab =
                    ((AppCompatActivity) activity).getSupportActionBar();
            hasActionBar = (ab != null);
        }

        if (hasActionBar) {
            TypedValue abTv = new TypedValue();
            if (activity.getTheme().resolveAttribute(androidx.appcompat.R.attr.actionBarSize, abTv, true)) {
                actionBarHeight = TypedValue.complexToDimensionPixelSize(
                        abTv.data, activity.getResources().getDisplayMetrics());

                // fine tune
                actionBarHeight -= (int) (16 * activity.getResources().getDisplayMetrics().density);
            }
        }

        final int[] topInsetPadding = {actionBarHeight};
        View content = activity.findViewById(android.R.id.content);
        if (content != null) {
            // padding.top = actionBarHeight + statusBars.top.
            // In edge-to-edge mode (Android 15+, forced on API 36+),
            // the system positions the ActionBar at y=statusBars.top,
            // so the ActionBar visually occupies y=statusBars.top ..
            // statusBars.top + actionBarHeight. Padding the content
            // frame by only actionBarHeight puts content at
            // y=actionBarHeight — well inside the ActionBar's vertical
            // band, where the ActionBar's bottom half still occludes
            // the first row. Adding statusBars.top moves content to
            // y=statusBars.top + actionBarHeight, i.e. flush below the
            // ActionBar's bottom edge. The other three sides still
            // take nav-bar / cutout insets so the edge-to-edge
            // contract is preserved elsewhere.
            ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(
                        bars.left,
                        topInsetPadding[0] + bars.top,
                        bars.right,
                        bars.bottom);
                return insets;
            });
        }
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
