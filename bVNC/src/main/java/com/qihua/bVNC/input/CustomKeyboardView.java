package com.qihua.bVNC.input;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.SoundPool;
import android.os.Build;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.qihua.bVNC.Constants;
import com.qihua.bVNC.R;
import com.qihua.bVNC.Utils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Custom on-screen keyboard replacing the system IME when the "custom keyboard"
 * pref is on. Sends plain Unicode chars / key events only — the remote side runs
 * its own IME. Visual style mirrors ExtraKeysView (semi-transparent buttons).
 * Rows 1-2 stagger ergonomically; past {@link #MAX_KEY_WIDTH_DP} per key the rows
 * split into two edge-docked groups with a wide SPACE on each side.
 */
@SuppressLint("ClickableViewAccessibility")
public class CustomKeyboardView extends ViewGroup {

    public interface OnKeyAction {
        void onKeyboardText(char c, int extraMeta);
        void onKeyboardSpecialKey(int androidKeyCode, int extraMeta);
    }

    private static final float MAX_KEY_WIDTH_DP = 64f;
    private static final float ROW_HEIGHT_DP = 48f;
    private static final int ROW_COUNT = 4;
    /** Reference row width in key units (row 0: 10 letters + 1.5u BKSP). */
    private static final float ROW_UNITS = 11.5f;
    /** Screen width (dp) past which the keyboard splits into two groups. */
    public static final float SPLIT_WIDTH_THRESHOLD_DP = ROW_UNITS * MAX_KEY_WIDTH_DP;
    /** Per-half content width in key units when split; SPACE flexes to fill it so both bars touch the center gap. */
    private static final float SPLIT_HALF_UNITS = 6f;
    private static final float STAGGER_ROW1 = 0.35f;
    private static final float STAGGER_ROW2 = 0.5f;
    private static final long REPEAT_INITIAL_MS = 400;
    private static final long REPEAT_DELAY_MS = 20;

    /** A key. Weights are widths in key units; < 0 means flex (fill the rest of the row). */
    private static final class KeyDef {
        final String label;
        final String labelShort; // shown when the key is too narrow for label
        final char text;     // 0 for special keys
        final int keyCode;   // 0 for text keys
        final char swipe;    // swipe-up char (row-0 digits, punctuation pairs), 0 for none
        final float weightFlat;
        final float weightSplit;

        KeyDef(String label, char text, int keyCode, char swipe, float weightFlat, float weightSplit) {
            this(label, label, text, keyCode, swipe, weightFlat, weightSplit);
        }

        KeyDef(String label, String labelShort, char text, int keyCode, char swipe, float weightFlat, float weightSplit) {
            this.label = label;
            this.labelShort = labelShort;
            this.text = text;
            this.keyCode = keyCode;
            this.swipe = swipe;
            this.weightFlat = weightFlat;
            this.weightSplit = weightSplit;
        }
    }

    private static KeyDef kd(char c) {
        return new KeyDef(String.valueOf(c), c, 0, (char) 0, 1f, 1f);
    }

    private static KeyDef kd(char c, char swipe) {
        return new KeyDef(String.valueOf(c), c, 0, swipe, 1f, 1f);
    }

    private static final class Row {
        final KeyDef[] keys;
        final float stagger;  // row indent in units (applies per group in split mode)
        final int splitAfter; // right-group start index; keys.length = no split

        Row(KeyDef[] keys, float stagger, int splitAfter) {
            this.keys = keys;
            this.stagger = stagger;
            this.splitAfter = splitAfter;
        }
    }

    private static final KeyDef BKSP = new KeyDef("⌫", (char) 0, KeyEvent.KEYCODE_DEL, (char) 0, 1.5f, 1f);
    private static final KeyDef SPACE_FLAT = new KeyDef("SPACE", (char) 0, KeyEvent.KEYCODE_SPACE, (char) 0, -1f, 0f);
    private static final KeyDef SPACE_L = new KeyDef("SPACE", (char) 0, KeyEvent.KEYCODE_SPACE, (char) 0, 0f, -1f);
    private static final KeyDef SPACE_R = new KeyDef("SPACE", (char) 0, KeyEvent.KEYCODE_SPACE, (char) 0, 0f, -1f);
    private static final KeyDef ENTER = new KeyDef("ENTER ⏎", "⏎", (char) 0, KeyEvent.KEYCODE_ENTER, (char) 0, -1f, 1.5f);
    /** Right shift latch: one-shot, meta rides on the next key (SSH needs it on the letter itself). */
    private static final KeyDef SHIFT = new KeyDef("SHIFT", (char) 0, KeyEvent.KEYCODE_SHIFT_RIGHT, (char) 0, -1f, 1.5f);
    // Punctuation swipe-ups mirror the physical keyboard's Shift pairs (?/ is flipped: ? is the face).
    private static final KeyDef COMMA = kd(',', '<');
    private static final KeyDef DOT = kd('.', '>');
    private static final KeyDef SEMI = kd(';', ':');
    private static final KeyDef QUESTION = kd('?', '/');
    private static final KeyDef APOSTROPHE = kd('\'', '"');
    /** Split row 2 puts b on both halves — either thumb may hit it. */
    private static final KeyDef B_RIGHT = kd('b');

    private static final Row[] ROWS_FLAT;
    private static final Row[] ROWS_SPLIT;

    static {
        KeyDef z = kd('z'), x = kd('x'), c = kd('c'), v = kd('v'), b = kd('b'), n = kd('n'), m = kd('m');
        KeyDef[] r0 = {kd('q', '1'), kd('w', '2'), kd('e', '3'), kd('r', '4'), kd('t', '5'),
                kd('y', '6'), kd('u', '7'), kd('i', '8'), kd('o', '9'), kd('p', '0'), BKSP};
        KeyDef[] r1 = {kd('a'), kd('s'), kd('d'), kd('f'), kd('g'), kd('h'), kd('j'), kd('k'), kd('l'), ENTER};
        KeyDef[] r2 = {z, x, c, v, b, n, m, QUESTION, SHIFT};
        KeyDef[] r2split = {z, x, c, v, b, B_RIGHT, n, m, QUESTION, SHIFT};
        KeyDef[] r3flat = {COMMA, DOT, SPACE_FLAT, SEMI, APOSTROPHE};
        KeyDef[] r3split = {COMMA, DOT, SPACE_L, SPACE_R, SEMI, APOSTROPHE};
        ROWS_FLAT = new Row[]{new Row(r0, 0, r0.length), new Row(r1, STAGGER_ROW1, r1.length),
                new Row(r2, STAGGER_ROW2, r2.length), new Row(r3flat, 0, r3flat.length)};
        ROWS_SPLIT = new Row[]{new Row(r0, 0, 5), new Row(r1, STAGGER_ROW1, 5),
                new Row(r2split, STAGGER_ROW2, 5), new Row(r3split, 0, 3)};
    }

    private final List<KeyButton> buttons = new ArrayList<>();
    private final int colorText;
    private final int colorTextActive;
    private final int colorBg;
    private final int colorBgActive;
    private boolean shiftLatched;      // own on-keyboard ⇧
    private boolean extraShiftActive;  // ExtraKeys bar's SHIFT

    /** Bundled click (system playSoundEffect is gated by the touch-sounds setting). */
    private static SoundPool soundPool;
    private static int clickSoundId = -1;

    private OnKeyAction keyAction;
    private PopupWindow popupWindow;
    private ScheduledExecutorService repeatExecutor;
    private int longPressCount;

    public CustomKeyboardView(Context context) {
        super(context);
        colorText = ContextCompat.getColor(context, R.color.extraKeysButtonTextColor);
        colorTextActive = ContextCompat.getColor(context, R.color.extraKeysButtonActiveTextColor);
        colorBg = ContextCompat.getColor(context, R.color.extraKeysButtonBackgroundColor);
        colorBgActive = ContextCompat.getColor(context, R.color.extraKeysButtonActiveBackgroundColor);

        if (soundPool == null) {
            soundPool = new SoundPool.Builder().setMaxStreams(4).build();
            clickSoundId = soundPool.load(context, R.raw.key_click, 1);
        }

        LinkedHashSet<KeyDef> union = new LinkedHashSet<>();
        for (Row row : ROWS_FLAT) for (KeyDef k : row.keys) union.add(k);
        for (Row row : ROWS_SPLIT) for (KeyDef k : row.keys) union.add(k);
        for (KeyDef def : union) {
            KeyButton b = new KeyButton(context, def);
            buttons.add(b);
            addView(b);
        }
    }

    public void setOnKeyAction(OnKeyAction action) {
        keyAction = action;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float density = getResources().getDisplayMetrics().density;
        int availW = MeasureSpec.getSize(widthMeasureSpec);
        int rowH = (int) (ROW_HEIGHT_DP * density + 0.5f);
        int maxKeyPx = (int) (MAX_KEY_WIDTH_DP * density + 0.5f);
        float natural = availW / ROW_UNITS;
        boolean split = natural > maxKeyPx;
        int unitW = (int) Math.min(natural, maxKeyPx);

        Row[] rows = split ? ROWS_SPLIT : ROWS_FLAT;
        for (KeyButton b : buttons) b.targetRect = null;

        for (int r = 0; r < rows.length; r++) {
            layoutRow(rows[r], split, unitW, availW, r * rowH, rowH);
        }

        setMeasuredDimension(availW, rowH * ROW_COUNT);

        for (KeyButton b : buttons) {
            if (b.targetRect != null) {
                if (b.getVisibility() != VISIBLE) b.setVisibility(VISIBLE);
                b.measure(MeasureSpec.makeMeasureSpec(b.targetRect.width(), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(b.targetRect.height(), MeasureSpec.EXACTLY));
            } else if (b.getVisibility() != GONE) {
                b.setVisibility(GONE);
            }
        }
    }

    private void layoutRow(Row row, boolean split, int unitW, int availW, int y, int rowH) {
        int staggerPx = (int) (row.stagger * unitW + 0.5f);
        // Flat rows fill ROW_UNITS; split groups each fill their half so the
        // flex SPACE bars on row 3 end up flush with the center gap.
        float groupUnits = (split ? SPLIT_HALF_UNITS : ROW_UNITS) - row.stagger;

        float flexLeft = flexUnits(row, 0, row.splitAfter, split, groupUnits);
        int x = staggerPx;
        for (int i = 0; i < row.splitAfter; i++) {
            x += place(row.keys[i], split, unitW, flexLeft, x, y, rowH);
        }

        if (row.splitAfter < row.keys.length) {
            float flexRight = flexUnits(row, row.splitAfter, row.keys.length, split, groupUnits);
            int totalW = 0;
            for (int i = row.splitAfter; i < row.keys.length; i++) {
                totalW += keyWidthPx(row.keys[i], split, unitW, flexRight);
            }
            int rx = availW - staggerPx - totalW;
            for (int i = row.splitAfter; i < row.keys.length; i++) {
                rx += place(row.keys[i], split, unitW, flexRight, rx, y, rowH);
            }
        }
    }

    /** Width in units left for a flex key after the group's fixed keys and stagger. */
    private float flexUnits(Row row, int from, int to, boolean split, float availUnits) {
        float fixed = 0;
        for (int i = from; i < to; i++) {
            float w = split ? row.keys[i].weightSplit : row.keys[i].weightFlat;
            if (w > 0) fixed += w;
        }
        return Math.max(1f, availUnits - fixed);
    }

    private int keyWidthPx(KeyDef k, boolean split, int unitW, float flexUnits) {
        float w = split ? k.weightSplit : k.weightFlat;
        return (int) ((w < 0 ? flexUnits : w) * unitW);
    }

    private int place(KeyDef k, boolean split, int unitW, float flexUnits, int x, int y, int rowH) {
        int w = keyWidthPx(k, split, unitW, flexUnits);
        KeyButton b = buttonFor(k);
        b.targetRect = new Rect(x, y, x + w, y + rowH);
        return w;
    }

    private KeyButton buttonFor(KeyDef k) {
        for (KeyButton b : buttons) if (b.def == k) return b;
        throw new IllegalStateException("no button for " + k.label);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (KeyButton btn : buttons) {
            if (btn.getVisibility() == GONE || btn.targetRect == null) continue;
            btn.layout(btn.targetRect.left, btn.targetRect.top, btn.targetRect.right, btn.targetRect.bottom);
        }
    }

    private void sendKey(KeyDef def) {
        if (keyAction == null) return;
        if (def == SHIFT) {
            setShiftLatched(!shiftLatched);
            return;
        }
        if (def.keyCode != 0) {
            keyAction.onKeyboardSpecialKey(def.keyCode, shiftMeta());
        } else {
            sendText(def.text);
        }
    }

    private void sendText(char c) {
        if (keyAction == null) return;
        keyAction.onKeyboardText(c, shiftMeta());
        // One-shot: a printable key consumes the latch.
        if (shiftLatched) setShiftLatched(false);
    }

    private int shiftMeta() {
        return shiftLatched ? KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_RIGHT_ON : 0;
    }

    private void setShiftLatched(boolean latched) {
        shiftLatched = latched;
        buttonFor(SHIFT).setTextColor(latched ? colorTextActive : colorText);
        updateLetterCase();
    }

    /** ExtraKeys bar's SHIFT state — drives letter case display only (meta comes from its own path). */
    public void setExternalShiftActive(boolean active) {
        if (extraShiftActive == active) return;
        extraShiftActive = active;
        updateLetterCase();
    }

    private void updateLetterCase() {
        boolean upper = shiftLatched || extraShiftActive;
        for (KeyButton b : buttons) b.setUpperCase(upper);
    }

    private void startRepeat() {
        stopRepeat();
        repeatExecutor = Executors.newSingleThreadScheduledExecutor();
        repeatExecutor.scheduleWithFixedDelay(() -> {
            longPressCount++;
            if (keyAction != null) keyAction.onKeyboardSpecialKey(KeyEvent.KEYCODE_DEL, shiftMeta());
        }, REPEAT_INITIAL_MS, REPEAT_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void stopRepeat() {
        if (repeatExecutor != null) {
            repeatExecutor.shutdownNow();
            repeatExecutor = null;
        }
    }

    private void playKeySound() {
        if (soundPool == null || clickSoundId <= 0) return;
        // Sound follows the system touch-sound switch, haptics the system haptic switch;
        // both are additionally gated by the app's own touch-feedback pref.
        if (Settings.System.getInt(getContext().getContentResolver(),
                Settings.System.SOUND_EFFECTS_ENABLED, 1) == 0) return;
        if (!Utils.querySharedPreferenceBoolean(getContext(), Constants.touchpadFeedback, false)) return;
        soundPool.play(clickSoundId, 0.7f, 0.7f, 1, 0, 1f);
    }

    private void performHaptic(View v) {
        if (!Utils.querySharedPreferenceBoolean(getContext(), Constants.touchpadFeedback, false)) return;
        if (Build.VERSION.SDK_INT >= 28) {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        } else if (Settings.Global.getInt(getContext().getContentResolver(), "zen_mode", 0) != 2) {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    private void showPopup(KeyButton btn) {
        MaterialButton popup = new MaterialButton(getContext(), null, android.R.attr.buttonBarButtonStyle);
        popup.setText(String.valueOf(btn.def.swipe));
        popup.setTextColor(colorText);
        popup.setBackgroundColor(colorBgActive);
        popup.setPadding(0, 0, 0, 0);
        popup.setMinHeight(0);
        popup.setMinWidth(0);
        popup.setMinimumWidth(0);
        popup.setMinimumHeight(0);
        popup.setWidth(btn.getMeasuredWidth());
        popup.setHeight(btn.getMeasuredHeight());
        popupWindow = new PopupWindow(getContext());
        popupWindow.setWidth(LayoutParams.WRAP_CONTENT);
        popupWindow.setHeight(LayoutParams.WRAP_CONTENT);
        popupWindow.setContentView(popup);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setFocusable(false);
        popupWindow.showAsDropDown(btn, 0, -2 * btn.getMeasuredHeight());
    }

    private void dismissPopup() {
        if (popupWindow != null) {
            popupWindow.setContentView(null);
            popupWindow.dismiss();
            popupWindow = null;
        }
    }

    private boolean onKeyTouch(View v, MotionEvent event) {
        KeyButton btn = (KeyButton) v;
        KeyDef def = btn.def;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                Utils.setKeyPressedFlash(v, true);
                playKeySound();
                performHaptic(v);
                longPressCount = 0;
                if (def.keyCode == KeyEvent.KEYCODE_DEL) startRepeat();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (def.swipe != 0) {
                    if (popupWindow == null && event.getY() < 0) {
                        stopRepeat();
                        Utils.setKeyPressedFlash(v, false);
                        showPopup(btn);
                    }
                    if (popupWindow != null && event.getY() > 0) {
                        Utils.setKeyPressedFlash(v, true);
                        dismissPopup();
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
                Utils.setKeyPressedFlash(v, false);
                stopRepeat();
                if (popupWindow != null) {
                    dismissPopup();
                    sendText(def.swipe);
                } else if (longPressCount == 0) {
                    sendKey(def);
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                Utils.setKeyPressedFlash(v, false);
                stopRepeat();
                dismissPopup();
                return true;

            default:
                return true;
        }
    }

    /** Button with an optional small corner hint (the swipe-up digit). */
    private final class KeyButton extends MaterialButton {
        final KeyDef def;
        Rect targetRect;
        boolean upperCase;
        private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        KeyButton(Context context, KeyDef def) {
            super(context, null, android.R.attr.buttonBarButtonStyle);
            this.def = def;
            setText(def.label);
            setTextColor(colorText);
            setBackgroundColor(colorBg);
            setAllCaps(false);
            setPadding(0, 0, 0, 0);
            setTextAlignment(TEXT_ALIGNMENT_CENTER);
            cornerPaint.setColor(colorText);
            cornerPaint.setAlpha(180);
            setOnTouchListener(CustomKeyboardView.this::onKeyTouch);
        }

        boolean isLetter() {
            return def.text != 0 && Character.isLowerCase(def.text);
        }

        void setUpperCase(boolean upper) {
            if (upperCase == upper) return;
            upperCase = upper;
            if (isLetter()) {
                setText(upper ? String.valueOf(Character.toUpperCase(def.text)) : def.label);
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            // Narrow keys (phones) fall back to the short icon-only label.
            String want = getPaint().measureText(def.label) > w * 0.88f ? def.labelShort : def.label;
            if (upperCase && isLetter()) want = want.toUpperCase();
            if (!want.contentEquals(getText())) setText(want);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (def.swipe != 0) {
                cornerPaint.setTextSize(getTextSize() * 0.5f);
                String s = String.valueOf(def.swipe);
                float padX = getWidth() * 0.12f;
                canvas.drawText(s, getWidth() - cornerPaint.measureText(s) - padX,
                        getHeight() * 0.08f + cornerPaint.getTextSize(), cornerPaint);
            }
        }
    }
}
