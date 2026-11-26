package com.team4.bintime.timetable;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * [BT-048] 원본 이미지 확대 보기. 새 라이브러리 없이 Android 기본 제스처(ScaleGestureDetector·GestureDetector)만 쓴다.
 * <ul>
 *   <li>두 손가락 확대·축소(1~{@link ZoomMath#MAX_SCALE}배), 확대 중 끌어서 이동, 두 번 탭으로 확대/원래 크기.</li>
 *   <li>원래 크기일 때 좌우로 밀면 {@link SwipeListener}로 이전·다음 이미지를 알린다.</li>
 * </ul>
 */
public class ZoomImageView extends AppCompatImageView {

    interface SwipeListener {
        /** direction: +1 다음, -1 이전. */
        void onSwipe(int direction);
    }

    private final Matrix base = new Matrix();
    private final Matrix matrix = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private float scale = 1f;
    private SwipeListener swipeListener;

    public ZoomImageView(Context context) {
        this(context, null);
    }

    public ZoomImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float next = ZoomMath.clampScale(scale * detector.getScaleFactor());
                float factor = next / scale;
                scale = next;
                matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                applyBounds();
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scale <= 1f) return false;
                matrix.postTranslate(-dx, -dy);
                applyBounds();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float next = scale > 1f ? 1f : ZoomMath.DOUBLE_TAP_SCALE;
                if (next == 1f) {
                    resetZoom();
                } else {
                    float factor = next / scale;
                    scale = next;
                    matrix.postScale(factor, factor, e.getX(), e.getY());
                    applyBounds();
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || swipeListener == null || scale > 1f) return false;
                int direction = ZoomMath.swipeDirection(e2.getX() - e1.getX(), e2.getY() - e1.getY(), vx);
                if (direction == 0) return false;
                swipeListener.onSwipe(direction);
                return true;
            }
        });
    }

    void setSwipeListener(SwipeListener listener) {
        swipeListener = listener;
    }

    /** 확대를 풀고 이미지를 화면 가운데에 맞춘다. */
    void resetZoom() {
        // 상위 생성자가 setImageDrawable 을 부를 때는 아직 필드가 없다.
        if (matrix == null || base == null) return;
        scale = 1f;
        fitBase();
        matrix.set(base);
        setImageMatrix(matrix);
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        resetZoom();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetZoom();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = scaleDetector.onTouchEvent(event);
        handled |= gestureDetector.onTouchEvent(event);
        return handled || super.onTouchEvent(event);
    }

    private void fitBase() {
        base.reset();
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || getHeight() == 0
                || d.getIntrinsicWidth() <= 0 || d.getIntrinsicHeight() <= 0) {
            return;
        }
        base.setRectToRect(new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight()),
                new RectF(0, 0, getWidth(), getHeight()), Matrix.ScaleToFit.CENTER);
    }

    /** 이미지가 화면보다 크면 가장자리가 화면 안으로 들어오지 않게, 작으면 가운데에 둔다. */
    private void applyBounds() {
        Drawable d = getDrawable();
        if (d == null) return;
        RectF rect = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        matrix.mapRect(rect);
        float dx = ZoomMath.boundShift(rect.left, rect.right, getWidth());
        float dy = ZoomMath.boundShift(rect.top, rect.bottom, getHeight());
        matrix.postTranslate(dx, dy);
        setImageMatrix(matrix);
    }
}
