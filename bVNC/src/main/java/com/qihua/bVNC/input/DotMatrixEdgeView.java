/**
 * Tesla-style dot matrix view with gradient highlight around touch position.
 */
package com.qihua.bVNC.input;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.core.content.ContextCompat;

import com.qihua.bVNC.R;

public class DotMatrixEdgeView extends View {
    public enum EdgeOrientation {
        LEFT,   // Vertical strip
        RIGHT,  // Vertical strip
        TOP,    // Horizontal strip
        BOTTOM  // Horizontal strip
    }

    private Paint inactivePaint;
    private Paint activePaint;

    private EdgeOrientation orientation = EdgeOrientation.LEFT;
    private float touchPosition = 0.5f; // 0 to 1, position along the edge

    private float dotRadius = 3f;
    private float dotSpacing = 14f;
    private float activeRadius = 5f;
    private float highlightRadius = 40f; // Radius of gradient effect in dp

    private int inactiveColor;
    private int activeColor;

    private ValueAnimator snapAnimator;

    public DotMatrixEdgeView(Context context) {
        super(context);
        init(context);
    }

    public DotMatrixEdgeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public DotMatrixEdgeView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        inactiveColor = ContextCompat.getColor(context, R.color.dot_matrix_inactive);
        activeColor = ContextCompat.getColor(context, R.color.dot_matrix_active);

        inactivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        inactivePaint.setColor(inactiveColor);
        inactivePaint.setStyle(Paint.Style.FILL);

        activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        activePaint.setColor(activeColor);
        activePaint.setStyle(Paint.Style.FILL);

        float density = context.getResources().getDisplayMetrics().density;
        dotRadius = 2.5f * density;
        activeRadius = 3.5f * density;
        dotSpacing = 12f * density;
        highlightRadius = 120f * density;
    }

    public void setOrientation(EdgeOrientation orientation) {
        this.orientation = orientation;
        invalidate();
    }

    public EdgeOrientation getOrientation() {
        return orientation;
    }

    public void setTouchPosition(float position) {
        this.touchPosition = Math.max(0f, Math.min(1f, position));
        invalidate();
    }

    public float getTouchPosition() {
        return touchPosition;
    }

    /**
     * Update touch position with optional animation
     */
    public void updateTouchPosition(float position, boolean animate) {
        if (animate) {
            animateToPosition(position);
        } else {
            touchPosition = Math.max(0f, Math.min(1f, position));
            invalidate();
        }
    }

    private void animateToPosition(float targetPosition) {
        targetPosition = Math.max(0f, Math.min(1f, targetPosition));
        ValueAnimator animator = ValueAnimator.ofFloat(touchPosition, targetPosition);
        animator.setDuration(120);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            touchPosition = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    /**
     * Snap to nearest end (0 or 1)
     */
    public void snapToEnd() {
        float target = touchPosition < 0.5f ? 0f : 1f;
        animateToPosition(target);
    }

    public void reset() {
        touchPosition = 0.5f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();

        if (width == 0 || height == 0) return;

        if (orientation == EdgeOrientation.LEFT || orientation == EdgeOrientation.RIGHT) {
            drawVerticalMatrix(canvas, width, height);
        } else {
            drawHorizontalMatrix(canvas, width, height);
        }
    }

    private void drawVerticalMatrix(Canvas canvas, int width, int height) {
        int columns = Math.max(1, (int) (width / dotSpacing));
        int rows = Math.max(3, (int) (height / dotSpacing));

        float startX = (width - (columns - 1) * dotSpacing) / 2f;
        float startY = dotSpacing / 2f;

        // Calculate touch position in pixel coordinates
        float touchY = startY + touchPosition * (height - dotSpacing);

        // Draw all dots with gradient highlight
        for (int col = 0; col < columns; col++) {
            float x = startX + col * dotSpacing;
            for (int row = 0; row < rows; row++) {
                float y = startY + row * dotSpacing;

                // Calculate distance from touch point (normalized)
                float distance = Math.abs(y - touchY);
                float normalizedDist = distance / highlightRadius;

                // Calculate alpha based on distance (closer = brighter)
                float alpha = Math.max(0f, 1f - normalizedDist);
                alpha = (float) Math.pow(alpha, 1.5); // Ease-out curve for smoother gradient

                // Calculate radius (closer = larger)
                float radius = dotRadius + (activeRadius - dotRadius) * alpha;

                // Use same color for all dots, only alpha varies
                int alphaInt = (int) (alpha * 255);
                int finalColor = (inactiveColor & 0x00FFFFFF) | (alphaInt << 24);
                inactivePaint.setColor(finalColor);

                canvas.drawCircle(x, y, radius, inactivePaint);
            }
        }
    }

    private void drawHorizontalMatrix(Canvas canvas, int width, int height) {
        int columns = Math.max(3, (int) (width / dotSpacing));
        int rows = Math.max(1, (int) (height / dotSpacing));

        float startX = dotSpacing / 2f;
        float startY = (height - (rows - 1) * dotSpacing) / 2f;

        // Calculate touch position in pixel coordinates
        float touchX = startX + touchPosition * (width - dotSpacing);

        // Draw all dots with gradient highlight
        for (int col = 0; col < columns; col++) {
            float x = startX + col * dotSpacing;
            for (int row = 0; row < rows; row++) {
                float y = startY + row * dotSpacing;

                // Calculate distance from touch point (normalized)
                float distance = Math.abs(x - touchX);
                float normalizedDist = distance / highlightRadius;

                // Calculate alpha based on distance (closer = brighter)
                float alpha = Math.max(0f, 1f - normalizedDist);
                alpha = (float) Math.pow(alpha, 1.5); // Ease-out curve

                // Calculate radius (closer = larger)
                float radius = dotRadius + (activeRadius - dotRadius) * alpha;

                // Use same color for all dots, only alpha varies
                int alphaInt = (int) (alpha * 255);
                int finalColor = (inactiveColor & 0x00FFFFFF) | (alphaInt << 24);
                inactivePaint.setColor(finalColor);

                canvas.drawCircle(x, y, radius, inactivePaint);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Don't consume - let parent handle
        return false;
    }

    public void setInactiveColor(int color) {
        this.inactiveColor = color;
        inactivePaint.setColor(color);
        invalidate();
    }

    public void setActiveColor(int color) {
        this.activeColor = color;
        activePaint.setColor(color);
        invalidate();
    }
}
