/**
 * Copyright (C) 2012 Iordan Iordanov
 * Copyright (C) 2009 Michael A. MacDonald
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

import android.graphics.Matrix;
import android.widget.ImageView.ScaleType;

import com.undatech.remoteClientUi.R;

/**
 * @author Michael A. MacDonald
 */
class ZoomScaling extends AbstractScaling {

    static final String TAG = "ZoomScaling";
    int canvasXOffset;
    int canvasYOffset;
    float scaling;
    float minimumScale;
    private Matrix matrix;

    public ZoomScaling() {
        super(R.id.itemZoomable, ScaleType.MATRIX);
        matrix = new Matrix();
        scaling = 1;
    }

    /* (non-Javadoc)
     * @see com.qihua.bVNC.AbstractScaling#getDefaultHandlerId()
     */
    @Override
    int getDefaultHandlerId() {
        return R.id.itemInputTouchPanZoomMouse;
    }

    /* (non-Javadoc)
     * @see com.qihua.bVNC.AbstractScaling#isAbleToPan()
     */
    @Override
    public boolean isAbleToPan() {
        return true;
    }

    /* (non-Javadoc)
     * @see com.qihua.bVNC.AbstractScaling#isValidInputMode(int)
     */
    @Override
    boolean isValidInputMode(int mode) {
//        return mode == R.id.itemInputTouchPanZoomMouse;
        return true;
    }

    /**
     * Call after scaling and matrix have been changed to resolve scrolling
     * @param canvas
     */
    private void resolveZoom(RemoteCanvas canvas) {
        resetMatrix();
        matrix.postScale(scaling, scaling);
        canvas.setImageMatrix(matrix);
        canvas.resetScroll();
        canvas.relativePan(0, 0);
    }

    /* (non-Javadoc)
     * @see com.qihua.bVNC.AbstractScaling#zoomIn(com.qihua.bVNC.RemoteCanvasActivity)
     */
    @Override
    void zoomIn(RemoteCanvasActivity activity) {
        resetMatrix();
        standardizeScaling();
        scaling += 0.25;
        if (scaling > 4.0f) {
            scaling = 4.0f;
        }
        matrix.postScale(scaling, scaling);
        //Log.v(TAG,String.format("before set matrix scrollx = %d scrolly = %d", activity.vncCanvas.getScrollX(), activity.vncCanvas.getScrollY()));
        activity.getCanvas().setImageMatrix(matrix);
        resolveZoom(activity.getCanvas());
    }

    /* (non-Javadoc)
     * @see com.qihua.bVNC.AbstractScaling#getScale()
     */
    @Override
    public float getZoomFactor() {
        return scaling;
    }

    /* (non-Javadoc)
     * @see com.qihua.bVNC.AbstractScaling#zoomOut(com.qihua.bVNC.RemoteCanvasActivity)
     */
    @Override
    void zoomOut(RemoteCanvasActivity activity) {
        resetMatrix();
        standardizeScaling();
        scaling -= 0.25;
        if (scaling < minimumScale) {
            scaling = minimumScale;
        }
        matrix.postScale(scaling, scaling);
        //Log.v(TAG,String.format("before set matrix scrollx = %d scrolly = %d", activity.vncCanvas.getScrollX(), activity.vncCanvas.getScrollY()));
        activity.getCanvas().setImageMatrix(matrix);
        //Log.v(TAG,String.format("after set matrix scrollx = %d scrolly = %d", activity.vncCanvas.getScrollX(), activity.vncCanvas.getScrollY()));
        resolveZoom(activity.getCanvas());
    }

    /* (non-Javadoc)
     * @see com.qihua.bVNC.AbstractScaling#adjust(com.qihua.bVNC.RemoteCanvasActivity, float, float, float)
     */
    @Override
    public void changeZoom(RemoteCanvasActivity activity, float scaleFactor, float fx, float fy) {

        float oldScale = scaling;
        float newScale = scaleFactor * oldScale;
        if (scaleFactor < 1) {
            if (newScale < minimumScale) {
                newScale = minimumScale;
            }
        } else {
            if (newScale > 4) {
                newScale = 4;
            }
        }

        RemoteCanvas canvas = activity.getCanvas();
        // "dx" is the distance to left screen edge in screen's coordinate.
        // "fx" is the point's x position in original image's coordinate.
        //  1. newXPan*newScale + dx = fx * newScale
        //  => newXPan = fx - dx / newScale
        //  2. dx = fx / imageWidth * canvasWidth = fx * minimumScale
        int xPan = canvas.absoluteXPosition;
        float newXPan = (fx + canvasXOffset) * (1 - canvas.getMinimumScale() / newScale);
        int yPan = canvas.absoluteYPosition;
        float newYPan = (fy + canvasYOffset) * (1 - canvas.getMinimumScale() / newScale);

        resetMatrix();
        scaling = newScale;
        matrix.postScale(scaling, scaling);
        canvas.setImageMatrix(matrix);
        resolveZoom(canvas);

        // Only if we have actually scaled do we pan and potentially set mouse position.
//        if (oldScale != newScale) {
            canvas.relativePan((int) (newXPan - xPan), (int) (newYPan - yPan));
//        }
    }

    private void resetMatrix() {
        matrix.reset();
        matrix.preTranslate(canvasXOffset, canvasYOffset);
    }

    /**
     *  Set scaling to one of the clicks on the zoom scale
     */
    private void standardizeScaling() {
        scaling = ((float) ((int) (scaling * 4))) / 4;
    }

    /* (non-Javadoc)
     * @see com.qihua.bVNC.AbstractScaling#setScaleTypeForActivity(com.qihua.bVNC.RemoteCanvasActivity)
     */
    @Override
    void setScaleTypeForActivity(RemoteCanvasActivity activity) {
        super.setScaleTypeForActivity(activity);
        RemoteCanvas canvas = activity.getCanvas();
        if (canvas == null || canvas.myDrawable == null)
            return;

        // top center align
        canvasXOffset = -canvas.getCenteredXOffset();
        canvasYOffset = 0;

        minimumScale = canvas.getMinimumScale();

        float lastZoomFactor = canvas.connection.getLastZoomFactor();

        // Do not apply zooming in second display mode.
        if (lastZoomFactor > 0 && canvas.connection.getUseLastPositionToolbar() && !canvas.isOutDisplay()) {
            scaling = lastZoomFactor;
        } else {
            scaling = minimumScale;
        }
        resolveZoom(canvas);
    }

}
