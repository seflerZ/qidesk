package com.qihua.bVNC.input;

import android.app.Activity;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import com.qihua.bVNC.R;

import java.lang.ref.WeakReference;

public class KeyBoardListenerHelper {
    //constants
    private static final String TAG = "KeyBoardListenerHelper";
    //data
    private WeakReference<Activity> weakActivity = null;//避免内存泄漏，使用弱引用
    private OnKeyBoardChangeListener onKeyBoardChangeListener;
    private final ViewTreeObserver.OnGlobalLayoutListener onGlobalLayoutListener =
            new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    if (!isActivityValid() || onKeyBoardChangeListener == null) {
                        return;
                    }
                    try {
                        Rect rect = new Rect();
                        weakActivity.get().getWindow().getDecorView().getWindowVisibleDisplayFrame(rect);
                        int screenHeight = weakActivity.get().getWindow().getDecorView().getHeight();
                        int keyBoardHeight = screenHeight - rect.bottom;
                        onKeyBoardChangeListener.OnKeyBoardChange(keyBoardHeight > 0, keyBoardHeight);
                    } catch (Exception e) {

                    }
                }
            };

    public KeyBoardListenerHelper(Activity activity) {
        if (activity == null) {
            return;
        }
        weakActivity = new WeakReference<>(activity);
        try {
            View content = weakActivity.get().findViewById(R.id.canvas);
            content.getViewTreeObserver().addOnGlobalLayoutListener(onGlobalLayoutListener);
        } catch (Exception e) {

        }
    }

    //在不使用的时候需要及时销毁，避免内存泄漏或造成额外开销
    public void destroy() {
        Log.i(TAG, "destroy");
        if (!isActivityValid()) {
            return;
        }
        try {
            View content = weakActivity.get().findViewById(android.R.id.content);
            content.getViewTreeObserver().removeOnGlobalLayoutListener(onGlobalLayoutListener);
        } catch (Exception e) {
            Log.e(TAG, "destroy error:" + e.getMessage());
        }
    }

    public void setOnKeyBoardChangeListener(OnKeyBoardChangeListener listener) {
        Log.i(TAG, "setOnKeyBoardChangeListener");
        this.onKeyBoardChangeListener = listener;
    }

    public interface OnKeyBoardChangeListener {

        void OnKeyBoardChange(boolean isShow, int keyBoardHeight);
    }

    public boolean isActivityValid() {
        return weakActivity != null && weakActivity.get() != null;
    }
}