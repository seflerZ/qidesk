package com.qihua.util;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

public class BackTapKeyboardHelper implements SensorEventListener {
    private static final String TAG = "BackTapKeyboardHelper";

    // Threshold for tap detection (in m/s², gravity is ~9.8)
    // A tap typically produces a spike of 15-30 m/s²
    // Set higher to avoid accidental triggers when placing phone on table
    private static final float TAP_THRESHOLD = 20.0f;

    // Number of taps required (2 to reduce accidental triggers)
    private static final int REQUIRED_TAPS = 2;

    // Time window for completing the taps (in ms)
    private static final long TAP_TIME_WINDOW_MS = 700;

    // Minimum time between taps to avoid double counting (in ms)
    private static final long MIN_TIME_BETWEEN_TAPS_MS = 240;

    // Cooldown after triggering to prevent repeated triggers (in ms)
    private static final long TRIGGER_COOLDOWN_MS = 1000;

    private final SensorManager sensorManager;
    private final Sensor accelerometer;

    private int tapCount = 0;
    private long lastTapTime = 0;
    private long lastTriggerTime = 0;
    private boolean wasAboveThreshold = false;

    private OnBackTapListener listener;

    public interface OnBackTapListener {
        void onBackTap();
    }

    public BackTapKeyboardHelper(Context context) {
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    public void setOnBackTapListener(OnBackTapListener listener) {
        this.listener = listener;
    }

    public void start() {
        if (accelerometer != null) {
            try {
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST);
                Log.d(TAG, "Started listening for back taps (FASTEST)");
            } catch (Exception e) {
                Log.w(TAG, "FASTEST sensor rate failed, falling back to UI: " + e.getMessage());
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
            }
        } else {
            Log.w(TAG, "Accelerometer not available");
        }
    }

    public void stop() {
        sensorManager.unregisterListener(this);
        Log.d(TAG, "Stopped listening for back taps");
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
            return;
        }

        // Calculate acceleration magnitude (excluding gravity would require a low-pass filter,
        // but for tap detection, raw magnitude works well)
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        float magnitude = (float) Math.sqrt(x * x + y * y + z * z);

        long currentTime = System.currentTimeMillis();

        // Detect rising edge of tap (crossing threshold from below)
        boolean isAboveThreshold = magnitude > TAP_THRESHOLD;

        if (isAboveThreshold && !wasAboveThreshold) {
            // Rising edge detected - potential tap start
            handlePotentialTap(currentTime);
        }

        wasAboveThreshold = isAboveThreshold;
    }

    private void handlePotentialTap(long currentTime) {
        // Check if we're in cooldown after a trigger
        if (currentTime - lastTriggerTime < TRIGGER_COOLDOWN_MS) {
            return;
        }

        // Check if this tap is too close to the previous one
        if (currentTime - lastTapTime < MIN_TIME_BETWEEN_TAPS_MS) {
            return;
        }

        lastTapTime = currentTime;
        tapCount++;

        Log.d(TAG, "Tap detected: " + tapCount + "/" + REQUIRED_TAPS);

        if (tapCount >= REQUIRED_TAPS) {
            // Check if taps are within time window
            if (currentTime - (lastTapTime - (REQUIRED_TAPS - 1) * MIN_TIME_BETWEEN_TAPS_MS) < TAP_TIME_WINDOW_MS) {
                triggerKeyboard();
                tapCount = 0;
            } else {
                // Too slow, reset
                tapCount = 1;
            }
        }
    }

    private void triggerKeyboard() {
        Log.d(TAG, "Triggering soft keyboard");
        lastTriggerTime = System.currentTimeMillis();

        if (listener != null) {
            listener.onBackTap();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not needed for this implementation
    }
}
