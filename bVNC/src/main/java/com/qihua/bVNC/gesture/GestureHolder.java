package com.qihua.bVNC.gesture;

import android.gesture.Gesture;

import java.util.List;

public class GestureHolder {
    private Gesture gesture;
    private String name;
    private List<String> keys;

    public GestureHolder(Gesture gesture, String name) {
        this.gesture = gesture;
        this.name = name;
    }

    public Gesture getGesture() {
        return gesture;
    }

    public String getName() {
        return name;
    }

    public List<String> getKeys() {
        return keys;
    }

    public String getJoinedKeys() {
        return String.join("+", keys);
    }

    public void setKeys(List<String> keys) {
        this.keys = keys;
    }
}
