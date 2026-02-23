/**
 * Copyright (C) 2012 Iordan Iordanov
 * Copyright (C) 2010 Michael A. MacDonald
 * <p>
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * <p>
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this software; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307,
 * USA.
 */

package com.qihua.bVNC;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.MemoryInfo;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.ClipboardManager;
import android.text.Html;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.antlersoft.android.contentxml.SqliteElement;
import com.antlersoft.android.contentxml.SqliteElement.ReplaceStrategy;

import net.sqlcipher.database.SQLiteDatabase;

import org.xml.sax.SAXException;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;

public class Utils {
    private final static String TAG = "Utils";
    private static final Intent docIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://code.google.com/p/android-vnc-viewer/wiki/Documentation"));
    public static String[] standardPackageNames = {
            "com.qihua.bVNC", "com.qihua.freebVNC",
            "com.qihua.rmt", "com.qihua.freeaRDP",
            "com.qihua.aSPICE", "com.qihua.freeaSPICE"
    };
    private static AlertDialog alertDialog;
    private static int nextNoticeID = 0;

    public static boolean isChineseLocal(Context context) {
        Locale currentLocale;
        Configuration config = context.getResources().getConfiguration();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            currentLocale = config.getLocales().get(0);
        } else {
            currentLocale = config.locale;
        }
        return "zh".equals(currentLocale.getLanguage());
    }

    public static void showYesNoPrompt(Context _context, String title, String message, OnClickListener onYesListener, OnClickListener onNoListener) {
        try {
            if (alertDialog != null && alertDialog.isShowing() && !isContextActivityThatIsFinishing(_context)) {
                alertDialog.dismiss();
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(_context);
            builder.setTitle(title);
            builder.setMessage(message);
            builder.setCancelable(false);
            builder.setPositiveButton(_context.getString(android.R.string.yes), onYesListener);
            builder.setNegativeButton(_context.getString(android.R.string.no), onNoListener);
            if (!(alertDialog != null && alertDialog.isShowing()) && !isContextActivityThatIsFinishing(_context)) {
                alertDialog = builder.create();
                alertDialog.show();

                alertDialog.setOnShowListener(d -> {
                    Button yes = alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
                    yes.setTextColor(ContextCompat.getColor(_context, R.color.theme));

                    Button no = alertDialog.getButton(DialogInterface.BUTTON_NEGATIVE);
                    no.setTextColor(ContextCompat.getColor(_context, R.color.theme));
                });
            }
        } catch (IllegalArgumentException ignore) {

        }
    }

    public static ActivityManager getActivityManager(Context context) {
        ActivityManager result = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (result == null)
            throw new UnsupportedOperationException("Could not retrieve ActivityManager");
        return result;
    }

    public static MemoryInfo getMemoryInfo(Context _context) {
        MemoryInfo info = new MemoryInfo();
        getActivityManager(_context).getMemoryInfo(info);
        return info;
    }

    public static boolean isCellularNetwork(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (cm == null) return false;

        Network network = cm.getActiveNetwork();
        if (network == null) return false;

        NetworkCapabilities nc = cm.getNetworkCapabilities(network);
        return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
    }

    public static boolean isWrongSubnetSiteLocalAddress(String address) {
        try {
            InetAddress targetAddress = InetAddress.getByName(address);
            if (!(targetAddress instanceof Inet4Address) || !targetAddress.isSiteLocalAddress()) {
                return false;
            }

            // We have a site-local address. Look for a matching local interface.
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
                    if (!(addr.getAddress() instanceof Inet4Address) || !addr.getAddress().isSiteLocalAddress()) {
                        // Skip non-site-local or non-IPv4 addresses
                        continue;
                    }

                    byte[] targetAddrBytes = targetAddress.getAddress();
                    byte[] ifaceAddrBytes = addr.getAddress().getAddress();

                    // Compare prefix to ensure it's the same
                    boolean addressMatches = true;
                    for (int i = 0; i < addr.getNetworkPrefixLength(); i++) {
                        if ((ifaceAddrBytes[i / 8] & (1 << (i % 8))) != (targetAddrBytes[i / 8] & (1 << (i % 8)))) {
                            addressMatches = false;
                            break;
                        }
                    }

                    if (addressMatches) {
                        return false;
                    }
                }
            }

            // Couldn't find a matching interface
            return true;
        } catch (Exception e) {
            // Catch all exceptions because some broken Android devices
            // will throw an NPE from inside getNetworkInterfaces().
            e.printStackTrace();
            return false;
        }
    }

    public static void showDocumentation(Context c) {
        c.startActivity(docIntent);
    }

    public static int nextNoticeID() {
        nextNoticeID++;
        return nextNoticeID;
    }

    public static void showErrorMessage(Context _context, String message) {
        showMessage(_context, _context.getString(R.string.error), message, 0, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });
    }

    public static void showFatalErrorMessage(final Context _context, String message) {
        showMessage(_context, _context.getString(R.string.error), message, 0, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
                Activity activity = Utils.getActivity(_context);
                if (activity != null) {
                    Utils.justFinish(activity);
                }
            }
        });
    }

    public static void showMessage(Context _context, String title, String message, int icon, DialogInterface.OnClickListener ackHandler) {
        try {
            if (alertDialog != null && alertDialog.isShowing() && !isContextActivityThatIsFinishing(_context)) {
                alertDialog.dismiss();
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(_context);
            builder.setTitle(title);
            builder.setMessage(Html.fromHtml(message));
            builder.setCancelable(false);
            builder.setPositiveButton(_context.getString(android.R.string.ok), ackHandler);
            builder.setIcon(icon);
            if (!(alertDialog != null && alertDialog.isShowing()) && !isContextActivityThatIsFinishing(_context)) {
                alertDialog = builder.create();
                alertDialog.show();

                Button yes = alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
                yes.setTextColor(ContextCompat.getColor(_context, R.color.theme));
            }
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        }
    }

    /**
     * Determine if a string is null or empty
     * @param s The string to comapare
     * @return true iff s is null or empty
     */
    public static boolean isNullOrEmptry(String s) {
        if (s == null || s.equals(""))
            return true;
        return false;
    }

    /**
     * Converts a given sequence of bytes to a human-readable colon-separated Hex format.
     * @param bytes
     * @return
     */
    public static String toHexString(byte[] bytes) {
        char[] hexArray = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};
        char[] hexChars = new char[bytes.length * 3];
        int v, j;
        for (j = 0; j < bytes.length - 1; j++) {
            v = bytes[j] & 0xFF;
            hexChars[j * 3] = hexArray[v / 16];
            hexChars[j * 3 + 1] = hexArray[v % 16];
            hexChars[j * 3 + 2] = ":".charAt(0);
        }
        v = bytes[j] & 0xFF;
        hexChars[j * 3] = hexArray[v / 16];
        hexChars[j * 3 + 1] = hexArray[v % 16];
        return new String(hexChars);
    }

    /**
     * Forces the appearance of a menu in the given context.
     * @param ctx
     */
    public static void showMenu(Context ctx) {
//        try {
//            ViewConfiguration config = ViewConfiguration.get(ctx);
//            Field menuKeyField = ViewConfiguration.class.getDeclaredField("sHasPermanentMenuKey");
//
//            if (menuKeyField != null) {
//                menuKeyField.setAccessible(true);
//                menuKeyField.setBoolean(config, false);
//            }
//        } catch (Exception e) {
//        }
    }

    public static String pName(Context context) {
        String pName = Constants.defaultPackageName;
        try {
            pName = context.getPackageName();
        } catch (Exception e) {
            Log.e(TAG, "Error obtaining package name from context, using default");
        }
        return pName;
    }

    public static boolean isFree(Context context) {
        return Utils.pName(context).contains("free");
    }

    public static String getConnectionString(Context context) {
        return Utils.pName(context) + ".CONNECTION";
    }

    public static String getConnectionTypeString(int type) {
        switch (type) {
            case 0:
                return "rdp";
            case 1:
                return "vnc";
            case 2:
                return "nvstream";
            default:
                return "unsupported";
        }
    }

    public static boolean isCustom(Context context) {
        String packageName = Utils.pName(context);
        for (String s : standardPackageNames) {
            if (packageName.equals(s)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isVnc(Context context) {
        String packageName = Utils.pName(context);
        return packageName.toLowerCase().contains("vnc");
    }

    public static boolean isRdp(Context context) {
        String packageName = Utils.pName(context);
        return packageName.toLowerCase().contains("rmt");
    }

    public static boolean isSpice(Context context) {
        String packageName = Utils.pName(context);
        return packageName.toLowerCase().contains("spice");
    }

    public static boolean isOpaque(Context context) {
        String packageName = Utils.pName(context);
        return packageName.toLowerCase().contains("opaque");
    }

    public static Class getConnectionSetupClass(String type) {
        switch (type) {
            case "vnc":
                return ConfigVNC.class;
            case "rdp":
                return ConfigRDP.class;
            case "nvstream":
                return ConfigNVStream.class;
            default:
                throw new UnsupportedOperationException("Unsupported connection type: " + type);
        }
    }

    public static String getConnectionScheme(Context context) {
        String packageName = Utils.pName(context);
        String scheme = "unsupported";
        if (isVnc(context))
            scheme = "vnc";
        else if (isRdp(context))
            scheme = "rdp";
        else if (isSpice(context))
            scheme = "spice";
        return scheme;
    }

    public static int getDefaultPort(Context context) {
        int port = Constants.DEFAULT_PROTOCOL_PORT;
        if (context != null) {
            if (isRdp(context))
                port = Constants.DEFAULT_RDP_PORT;
            else
                port = Constants.DEFAULT_VNC_PORT;
        }
        return port;
    }

    public static String getDonationPackageName(Context ctx) {
        return Utils.pName(ctx).replace("free", "");
    }

    public static String getDonationPackageLink(Context context) {
        String donationPackageName = getDonationPackageName(context);
        return "market://details?id=" + donationPackageName;
    }

    public static String getDonationPackageUrl(Context context) {
        String donationPackageName = getDonationPackageName(context);
        return "https://play.google.com/store/apps/details?id=" + donationPackageName;
    }

    public static boolean isBlackBerry() {
        boolean bb = false;
        if (android.os.Build.MODEL.contains("BlackBerry") ||
                android.os.Build.BRAND.contains("BlackBerry") ||
                android.os.Build.MANUFACTURER.contains("BlackBerry")) {
            bb = true;
        }
        return bb;
    }

    public static void exportSettingsToXml(OutputStream f, SQLiteDatabase db) {
        Writer writer = new OutputStreamWriter(f);
        try {
            SqliteElement.exportDbAsXmlToStream(db, writer);
            writer.close();
        } catch (SAXException | IOException e) {
            e.printStackTrace();
        }
    }

    public static void importSettingsFromXml(InputStream fin, SQLiteDatabase db) {
        Reader reader = new InputStreamReader(fin);
        try {
            SqliteElement.importXmlStreamToDb(db, reader, ReplaceStrategy.REPLACE_EXISTING);
        } catch (SAXException | IOException e) {
            e.printStackTrace();
        }
    }

    public static boolean isValidIpv6Address(final String address) {
        try {
            return InetAddress.getByName(address) instanceof Inet6Address;
        } catch (final UnknownHostException ex) {
            return false;
        }
    }

    public static String messageAndStackTraceAsString(Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        String localizedMessage = e.getLocalizedMessage();
        if (localizedMessage == null)
            localizedMessage = "";

        return "\n" + localizedMessage + "\n" + sw.toString();
    }

    public static boolean querySharedPreferenceBoolean(Context context, String key) {
        boolean result = false;
        if (context != null) {
            SharedPreferences sp = context.getSharedPreferences(Constants.generalSettingsTag, Context.MODE_PRIVATE);
            result = sp.getBoolean(key, false);
        }
        return result;
    }

    public static boolean querySharedPreferenceBoolean(Context context, String key, boolean defaultValue) {
        boolean result = defaultValue;
        if (context != null) {
            SharedPreferences sp = context.getSharedPreferences(Constants.generalSettingsTag, Context.MODE_PRIVATE);
            result = sp.getBoolean(key, false);
        }
        return result;
    }

    public static String querySharedPreferenceString(Context context, String key, String dftValue) {
        String result = dftValue;
        if (context != null) {
            SharedPreferences sp = context.getSharedPreferences(Constants.generalSettingsTag, Context.MODE_PRIVATE);
            result = sp.getString(key, dftValue);
        }
        return result;
    }

    public static int querySharedPreferenceInt(Context context, String key, int dftValue) {
        int result = dftValue;
        if (context != null) {
            SharedPreferences sp = context.getSharedPreferences(Constants.generalSettingsTag, Context.MODE_PRIVATE);
            result = sp.getInt(key, dftValue);
        }
        return result;
    }

    public static void setSharedPreferenceString(Context context, String key, String value) {
        if (context != null) {
            SharedPreferences sp = context.getSharedPreferences(Constants.generalSettingsTag, Context.MODE_PRIVATE);
            Editor editor = sp.edit();
            editor.putString(key, value);
            editor.apply();
            Log.i(TAG, "Set: " + key + " to value: " + value);
        }
    }

    public static void setSharedPreferenceBoolean(Context context, String key, boolean value) {
        if (context != null) {
            SharedPreferences sp = context.getSharedPreferences(Constants.generalSettingsTag, Context.MODE_PRIVATE);
            Editor editor = sp.edit();
            editor.putBoolean(key, value);
            editor.apply();
            Log.i(TAG, "Set: " + key + " to value: " + value);
        }
    }


    public static void toggleSharedPreferenceBoolean(Context context, String key) {
        if (context != null) {
            SharedPreferences sp = context.getSharedPreferences(Constants.generalSettingsTag,
                    Context.MODE_PRIVATE);
            boolean state = sp.getBoolean(key, false);
            Editor editor = sp.edit();
            editor.putBoolean(key, !state);
            editor.apply();
            Log.i(TAG, "Toggled " + key + " " + String.valueOf(state));
        }
    }

    static boolean isContextActivityThatIsFinishing(Context _context) {
        boolean result = false;
        if (_context instanceof Activity) {
            Activity activity = (Activity) _context;
            if (activity.isFinishing()) {
                result = true;
            }
        }
        return result;
    }

    static void writeScreenshotToFile(AbstractBitmapData drawable,
                                      String filePath, int dstWidth) {
        try {
            if (drawable != null && drawable.mbitmap != null) {
                // TODO: Add Filename to settings.
                FileOutputStream out = new FileOutputStream(filePath);
                double scaleReduction = (double) drawable.mbitmap.getWidth() / dstWidth;
                int dstHeight = (int) ((double) drawable.mbitmap.getHeight() / scaleReduction);
                Log.d(TAG, "Desktop screenshot width: " + dstWidth + ", height " + dstHeight);
                Bitmap tmp = Bitmap.createScaledBitmap(drawable.mbitmap, dstWidth, dstHeight, true);
                tmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                out.close();
//                tmp.recycle();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    /**
     * Either returns the input of it's already a UUID or returns a random UUID.
     * @param string which if it's a string representation of a UUID will be returned unaltered
     * @return the input if it's a UUID or a random UUID as a string
     */
    static String getUuid(String string) {
        try {
            UUID.fromString(string);
            return string;
        } catch (IllegalArgumentException e) {
            return UUID.randomUUID().toString();
        }
    }

    /**
     * Creates a connection screen help dialog for each app.
     * @param context
     * @return
     */
    public static Dialog createMainScreenDialog(Context context) {
        int textId = R.string.main_screen_help_text;
        return createDialog(context, textId);
    }

    /**
     * Creates a connection screen help dialog for each app.
     * @param context
     * @return
     */
    public static Dialog createConnectionScreenDialog(Context context) {
        int textId = R.string.vnc_connection_screen_help_text;
        if (Utils.isRdp(context))
            textId = R.string.rdp_connection_screen_help_text;
        else if (Utils.isSpice(context))
            textId = R.string.spice_connection_screen_help_text;
        else if (Utils.isOpaque(context))
            textId = R.string.opaque_connection_screen_help_text;
        return createDialog(context, textId);
    }

    public static Dialog createDialog(Context context, int textId) {
        AlertDialog.Builder adb = new AlertDialog.Builder(context)
                .setMessage(textId)
                .setPositiveButton(R.string.close,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,
                                                int whichButton) {
                                // We don't have to do anything.
                            }
                        });
        Dialog d = adb.setView(new ScrollView(context)).create();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(d.getWindow().getAttributes());
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        d.show();
        d.getWindow().setAttributes(lp);
        return d;
    }

    public static String newScreenshotFileName() {
        return UUID.randomUUID().toString() + ".png";
    }

    public static String getHostFromUriString(String uriString) {
        if (!uriString.startsWith("http")) {
            uriString = "https://" + uriString;
        }
        Uri uri = Uri.parse(uriString);
        String host = uri.getHost();
        return host;
    }

    public static Activity getActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    public static String getVersionAndCode(Context context) {
        String result = "";
        try {
            String packageName = Utils.pName(context);
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(packageName, 0);
            result = pInfo.versionName + "_" + pInfo.versionCode;
            android.util.Log.d(TAG, "Version of " + packageName + " is " + result);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return result;
    }

    public static String getStringFromMessage(Message msg, String key) {
        Bundle s = msg.getData();
        String value = "";
        if (s != null) {
            value = s.getString(key);
        }
        return value;
    }

    public static int getIntFromMessage(Message msg, String key) {
        Bundle s = msg.getData();
        int value = 0;
        if (s != null) {
            value = s.getInt(key);
        }
        return value;
    }

    public static boolean getBooleanFromMessage(Message msg, String key) {
        Bundle s = msg.getData();
        boolean value = false;
        if (s != null) {
            value = s.getBoolean(key);
        }
        return value;
    }

    public static String getStringResourceByName(Context context, String stringName) {
        String packageName = Utils.pName(context);
        int resId = context.getResources().getIdentifier(stringName, "string", packageName);
        String message = "";
        if (resId > 0) {
            message = context.getString(resId);
        }
        return message;
    }

    public static void hideKeyboard(Context context, View view) {
        if (context != null && view != null) {
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(view.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
            }
        }
    }

    public static void showKeyboard(Context context, View view) {
        if (view != null && context != null) {
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                view.requestFocus();
                imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
            }
        }
    }

    public static void toggleKeyboard(Context context, View view) {
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && imm.isActive()) {
                view.requestFocus(0);
                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, InputMethodManager.HIDE_IMPLICIT_ONLY);
            }
        }
    }

    public static void justFinish(Context context) {
        if (isOpaque(context) || isSpice(context)) {
            triggerRestart(context);
        } else {
            ((Activity) context).finish();
        }
    }

    public static void triggerRestart(Context context) {
        PackageManager packageManager = context.getPackageManager();
        Intent intent = packageManager.getLaunchIntentForPackage(context.getPackageName());
        ComponentName componentName = intent.getComponent();
        Intent mainIntent = Intent.makeRestartActivityTask(componentName);
        context.startActivity(mainIntent);
        Runtime.getRuntime().exit(0);
    }

    public static void startUriIntent(Context context, String url) {
        try {
            Log.d(TAG, "startUriIntent: Starting intent with url: " + url);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setData(Uri.parse(url));
            context.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "startUriIntent: ActivityNotFoundException caught.");
        }
    }

    public static void showRateAppDialog(Activity activity) {
//        ReviewManager manager = ReviewManagerFactory.create(activity);
//        Task<ReviewInfo> request = manager.requestReviewFlow();
//        request.addOnCompleteListener(task -> {
//            GoogleApiAvailability apiAvailability = GoogleApiAvailability.getInstance();
//            if (apiAvailability.isGooglePlayServicesAvailable(activity) == ConnectionResult.SUCCESS) {
//                if (task.isSuccessful()) {
//                    ReviewInfo reviewInfo = task.getResult();
//                    Task<Void> flow = manager.launchReviewFlow(activity, reviewInfo);
//                    flow.addOnCompleteListener(completedTask -> {
//                        Log.d(TAG, "rateApp: Completed: " + completedTask.getResult());
//                    });
//                } else {
//                    Log.d(TAG, "rateApp: task is not successful");
//                }
//            }
//        });
    }

    public static void setClipboard(Context context, String url) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setText(url);
    }

    /**
     * 显示自定义时长的 Toast
     * @param context 上下文（建议用 Application Context 避免内存泄漏）
     * @param message 提示文本
     * @param duration 自定义时长（毫秒，比如 1000 表示 1 秒）
     */
    public static void showCustomDurationToast(Context context, String message, int duration, Handler handler) {
        // 1. 创建 Toast 实例（用 LENGTH_SHORT 作为基础，后续手动取消）
        Toast toast = Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_SHORT);
        toast.show();

        // 2. 通过 Handler 延迟取消 Toast，实现自定义时长
        handler.postDelayed(toast::cancel, duration);
    }

    // 快捷方法：显示 1 秒的短 Toast（比系统 SHORT 更短）
    public static void showVeryShortToast(Context context, String message, Handler handler) {
        showCustomDurationToast(context, message, 1000, handler); // 1000 毫秒 = 1 秒
    }
}
