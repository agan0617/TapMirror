package io.github.agan0617.tapmirror;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.webkit.WebView;

import java.util.ArrayList;
import java.util.List;

/**
 * 把「點一下」的 X 座標左右鏡射（x → 寬度 − x）再交給網頁的 WebView。
 * 網頁分不出點擊是真的還是鏡射過的，所以不管它是用 click、touchstart、pointer 事件判斷左右，都會被對調。
 *
 * 只對「點一下」鏡射；拖曳、捲動、雙指縮放、長按都原樣送出，不然橫向捲動會反過來、長按會選到另一邊的字。
 * 做法：手指按下時先不送給網頁，把事件暫存起來——
 *   - 放開前移動沒超過系統的 touch slop → 是點一下，把暫存的事件全部鏡射後一次送出
 *   - 移動超過 slop、多了第二根手指、或按超過長按時間 → 不是點一下，把暫存的原始事件照原樣補送，之後的事件直接放行
 */
public class MirrorWebView extends WebView {
    private boolean mirror = true;
    private final int slop;
    private final long longPressTimeout;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final List<MotionEvent> pending = new ArrayList<>();
    private float downX, downY;
    private boolean passthrough;   // 已判定不是點一下，後續事件直接放行
    private boolean tracking;      // 正在暫存一次按壓

    private final Runnable longPressFlush = this::flushOriginal;

    public MirrorWebView(Context ctx) {
        super(ctx);
        ViewConfiguration vc = ViewConfiguration.get(ctx);
        slop = vc.getScaledTouchSlop();
        longPressTimeout = ViewConfiguration.getLongPressTimeout();
    }

    public void setMirror(boolean on) {
        if (mirror == on) return;
        mirror = on;
        if (tracking) flushOriginal();   // 切換時正在按著的那一下，照原樣送完
    }

    public boolean isMirror() { return mirror; }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (!mirror && !tracking) return super.dispatchTouchEvent(ev);

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                reset();
                tracking = true;
                downX = ev.getX();
                downY = ev.getY();
                pending.add(MotionEvent.obtain(ev));
                handler.postDelayed(longPressFlush, longPressTimeout);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!tracking) return super.dispatchTouchEvent(ev);
                if (passthrough) return super.dispatchTouchEvent(ev);
                if (Math.abs(ev.getX() - downX) > slop || Math.abs(ev.getY() - downY) > slop) {
                    flushOriginal();
                    return super.dispatchTouchEvent(ev);
                }
                pending.add(MotionEvent.obtain(ev));
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (!tracking || passthrough) return super.dispatchTouchEvent(ev);
                flushOriginal();
                return super.dispatchTouchEvent(ev);

            case MotionEvent.ACTION_UP:
                if (!tracking) return super.dispatchTouchEvent(ev);
                if (passthrough) {
                    reset();
                    return super.dispatchTouchEvent(ev);
                }
                // 是點一下：整串鏡射後送出
                handler.removeCallbacks(longPressFlush);
                for (MotionEvent e : pending) {
                    super.dispatchTouchEvent(mirrored(e));
                    e.recycle();
                }
                pending.clear();
                MotionEvent up = mirrored(ev);
                boolean r = super.dispatchTouchEvent(up);
                up.recycle();
                reset();
                return r;

            case MotionEvent.ACTION_CANCEL:
                if (!tracking) return super.dispatchTouchEvent(ev);
                boolean wasPassthrough = passthrough;
                reset();
                return wasPassthrough ? super.dispatchTouchEvent(ev) : true;

            default:
                return super.dispatchTouchEvent(ev);
        }
    }

    /** 判定不是點一下：把暫存的原始事件照原樣補送給網頁，之後直接放行 */
    private void flushOriginal() {
        handler.removeCallbacks(longPressFlush);
        if (!tracking || passthrough) return;
        passthrough = true;
        for (MotionEvent e : pending) {
            super.dispatchTouchEvent(e);
            e.recycle();
        }
        pending.clear();
    }

    private MotionEvent mirrored(MotionEvent src) {
        MotionEvent e = MotionEvent.obtain(src);
        e.setLocation(getWidth() - src.getX(), src.getY());
        return e;
    }

    private void reset() {
        handler.removeCallbacks(longPressFlush);
        for (MotionEvent e : pending) e.recycle();
        pending.clear();
        tracking = false;
        passthrough = false;
    }

    @Override
    protected void onDetachedFromWindow() {
        reset();
        super.onDetachedFromWindow();
    }
}
