package com.undatech.opaque;

import android.graphics.Bitmap;

public interface Viewable {
    void waitUntilInflated();

    int getDesiredWidth();

    int getDesiredHeight();

    void reallocateDrawable(int width, int height);

    Bitmap getBitmap();

    void reDraw(int x, int y, int width, int height);

    void reDraw(DrawTask task);

    void setMousePointerPosition(int x, int y);

    void setSoftCursorPixels(int[] pixels, int width, int height, int xPos, int yPos);

    void setSoftCursorBitmap(Bitmap bitmap, int width, int height, int xPos, int yPos);

    void mouseMode(boolean relative);

    boolean isAbleToPan();

    void onConnectionSuccess();

    // 更新连接进度对话框上的中间状态文案(如 moonlight 的各个 stage)
    void setConnectionStatus(String status);

    // 关闭连接进度对话框(连接成功或失败/终止时)
    void dismissConnectionProgress();
}
