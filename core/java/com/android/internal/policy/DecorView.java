/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.internal.policy;

import com.android.internal.R;
import com.android.internal.view.FloatingActionMode;
import com.android.internal.view.RootViewSurfaceTaker;
import com.android.internal.view.StandaloneActionMode;
import com.android.internal.view.menu.ContextMenuBuilder;
import com.android.internal.view.menu.MenuDialogHelper;
import com.android.internal.view.menu.MenuPopupHelper;
import com.android.internal.widget.ActionBarContextView;
import com.android.internal.widget.BackgroundFallback;
import com.android.internal.widget.FloatingToolbar;
import com.android.internal.widget.NonClientDecorView;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.InputQueue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ThreadedRenderer;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowCallbacks;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.PopupWindow;

import static android.app.ActivityManager.StackId.FULLSCREEN_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.INVALID_STACK_ID;
import static android.view.View.MeasureSpec.AT_MOST;
import static android.view.View.MeasureSpec.EXACTLY;
import static android.view.View.MeasureSpec.getMode;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;
import static android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;

/** @hide */
public class DecorView extends FrameLayout implements RootViewSurfaceTaker, WindowCallbacks {
    private static final String TAG = "DecorView";

    private static final boolean SWEEP_OPEN_MENU = false;

    int mDefaultOpacity = PixelFormat.OPAQUE;

    /** The feature ID of the panel, or -1 if this is the application's DecorView */
    private final int mFeatureId;

    private final Rect mDrawingBounds = new Rect();

    private final Rect mBackgroundPadding = new Rect();

    private final Rect mFramePadding = new Rect();

    private final Rect mFrameOffsets = new Rect();

    // True if a non client area decor exists.
    private boolean mHasNonClientDecor = false;

    private boolean mChanging;

    private Drawable mMenuBackground;
    private boolean mWatchingForMenu;
    private int mDownY;

    ActionMode mPrimaryActionMode;
    private ActionMode mFloatingActionMode;
    private ActionBarContextView mPrimaryActionModeView;
    private PopupWindow mPrimaryActionModePopup;
    private Runnable mShowPrimaryActionModePopup;
    private ViewTreeObserver.OnPreDrawListener mFloatingToolbarPreDrawListener;
    private View mFloatingActionModeOriginatingView;
    private FloatingToolbar mFloatingToolbar;
    private ObjectAnimator mFadeAnim;

    // View added at runtime to draw under the status bar area
    private View mStatusGuard;
    // View added at runtime to draw under the navigation bar area
    private View mNavigationGuard;

    private final ColorViewState mStatusColorViewState = new ColorViewState(
            SYSTEM_UI_FLAG_FULLSCREEN, FLAG_TRANSLUCENT_STATUS,
            Gravity.TOP, Gravity.LEFT,
            Window.STATUS_BAR_BACKGROUND_TRANSITION_NAME,
            com.android.internal.R.id.statusBarBackground,
            FLAG_FULLSCREEN);
    private final ColorViewState mNavigationColorViewState = new ColorViewState(
            SYSTEM_UI_FLAG_HIDE_NAVIGATION, FLAG_TRANSLUCENT_NAVIGATION,
            Gravity.BOTTOM, Gravity.RIGHT,
            Window.NAVIGATION_BAR_BACKGROUND_TRANSITION_NAME,
            com.android.internal.R.id.navigationBarBackground,
            0 /* hideWindowFlag */);

    private final Interpolator mShowInterpolator;
    private final Interpolator mHideInterpolator;
    private final int mBarEnterExitDuration;

    private final BackgroundFallback mBackgroundFallback = new BackgroundFallback();

    private int mLastTopInset = 0;
    private int mLastBottomInset = 0;
    private int mLastRightInset = 0;
    private boolean mLastHasTopStableInset = false;
    private boolean mLastHasBottomStableInset = false;
    private boolean mLastHasRightStableInset = false;
    private int mLastWindowFlags = 0;

    private int mRootScrollY = 0;

    private PhoneWindow mWindow;

    ViewGroup mContentRoot;

    private Rect mTempRect;
    private Rect mOutsets = new Rect();

    // This is the non client decor view for the window, containing the caption and window control
    // buttons. The visibility of this decor depends on the workspace and the window type.
    // If the window type does not require such a view, this member might be null.
    NonClientDecorView mNonClientDecorView;

    // The non client decor needs to adapt to the used workspace. Since querying and changing the
    // workspace is expensive, this is the workspace value the window is currently set up for.
    int mWorkspaceId;

    private boolean mWindowResizeCallbacksAdded = false;

    public BackdropFrameRenderer mBackdropFrameRenderer = null;
    private Drawable mResizingBackgroundDrawable;
    private Drawable mCaptionBackgroundDrawable;

    DecorView(Context context, int featureId, PhoneWindow window) {
        super(context);
        mFeatureId = featureId;

        mShowInterpolator = AnimationUtils.loadInterpolator(context,
                android.R.interpolator.linear_out_slow_in);
        mHideInterpolator = AnimationUtils.loadInterpolator(context,
                android.R.interpolator.fast_out_linear_in);

        mBarEnterExitDuration = context.getResources().getInteger(
                R.integer.dock_enter_exit_duration);

        setWindow(window);
    }

    public void setBackgroundFallback(int resId) {
        mBackgroundFallback.setDrawable(resId != 0 ? getContext().getDrawable(resId) : null);
        setWillNotDraw(getBackground() == null && !mBackgroundFallback.hasFallback());
    }

    @Override
    public void onDraw(Canvas c) {
        super.onDraw(c);
        mBackgroundFallback.draw(mContentRoot, c, mWindow.mContentParent);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        final int action = event.getAction();
        final boolean isDown = action == KeyEvent.ACTION_DOWN;

        if (isDown && (event.getRepeatCount() == 0)) {
            // First handle chording of panel key: if a panel key is held
            // but not released, try to execute a shortcut in it.
            if ((mWindow.mPanelChordingKey > 0) && (mWindow.mPanelChordingKey != keyCode)) {
                boolean handled = dispatchKeyShortcutEvent(event);
                if (handled) {
                    return true;
                }
            }

            // If a panel is open, perform a shortcut on it without the
            // chorded panel key
            if ((mWindow.mPreparedPanel != null) && mWindow.mPreparedPanel.isOpen) {
                if (mWindow.performPanelShortcut(mWindow.mPreparedPanel, keyCode, event, 0)) {
                    return true;
                }
            }
        }

        if (!mWindow.isDestroyed()) {
            final Window.Callback cb = mWindow.getCallback();
            final boolean handled = cb != null && mFeatureId < 0 ? cb.dispatchKeyEvent(event)
                    : super.dispatchKeyEvent(event);
            if (handled) {
                return true;
            }
        }

        return isDown ? mWindow.onKeyDown(mFeatureId, event.getKeyCode(), event)
                : mWindow.onKeyUp(mFeatureId, event.getKeyCode(), event);
    }

    @Override
    public boolean dispatchKeyShortcutEvent(KeyEvent ev) {
        // If the panel is already prepared, then perform the shortcut using it.
        boolean handled;
        if (mWindow.mPreparedPanel != null) {
            handled = mWindow.performPanelShortcut(mWindow.mPreparedPanel, ev.getKeyCode(), ev,
                    Menu.FLAG_PERFORM_NO_CLOSE);
            if (handled) {
                if (mWindow.mPreparedPanel != null) {
                    mWindow.mPreparedPanel.isHandled = true;
                }
                return true;
            }
        }

        // Shortcut not handled by the panel.  Dispatch to the view hierarchy.
        final Window.Callback cb = mWindow.getCallback();
        handled = cb != null && !mWindow.isDestroyed() && mFeatureId < 0
                ? cb.dispatchKeyShortcutEvent(ev) : super.dispatchKeyShortcutEvent(ev);
        if (handled) {
            return true;
        }

        // If the panel is not prepared, then we may be trying to handle a shortcut key
        // combination such as Control+C.  Temporarily prepare the panel then mark it
        // unprepared again when finished to ensure that the panel will again be prepared
        // the next time it is shown for real.
        PhoneWindow.PanelFeatureState st =
                mWindow.getPanelState(Window.FEATURE_OPTIONS_PANEL, false);
        if (st != null && mWindow.mPreparedPanel == null) {
            mWindow.preparePanel(st, ev);
            handled = mWindow.performPanelShortcut(st, ev.getKeyCode(), ev,
                    Menu.FLAG_PERFORM_NO_CLOSE);
            st.isPrepared = false;
            if (handled) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        final Window.Callback cb = mWindow.getCallback();
        return cb != null && !mWindow.isDestroyed() && mFeatureId < 0
                ? cb.dispatchTouchEvent(ev) : super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent ev) {
        final Window.Callback cb = mWindow.getCallback();
        return cb != null && !mWindow.isDestroyed() && mFeatureId < 0
                ? cb.dispatchTrackballEvent(ev) : super.dispatchTrackballEvent(ev);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        final Window.Callback cb = mWindow.getCallback();
        return cb != null && !mWindow.isDestroyed() && mFeatureId < 0
                ? cb.dispatchGenericMotionEvent(ev) : super.dispatchGenericMotionEvent(ev);
    }

    public boolean superDispatchKeyEvent(KeyEvent event) {
        // Give priority to closing action modes if applicable.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            final int action = event.getAction();
            // Back cancels action modes first.
            if (mPrimaryActionMode != null) {
                if (action == KeyEvent.ACTION_UP) {
                    mPrimaryActionMode.finish();
                }
                return true;
            }
        }

        return super.dispatchKeyEvent(event);
    }

    public boolean superDispatchKeyShortcutEvent(KeyEvent event) {
        return super.dispatchKeyShortcutEvent(event);
    }

    public boolean superDispatchTouchEvent(MotionEvent event) {
        return super.dispatchTouchEvent(event);
    }

    public boolean superDispatchTrackballEvent(MotionEvent event) {
        return super.dispatchTrackballEvent(event);
    }

    public boolean superDispatchGenericMotionEvent(MotionEvent event) {
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return onInterceptTouchEvent(event);
    }

    private boolean isOutOfInnerBounds(int x, int y) {
        return x < 0 || y < 0 || x > getWidth() || y > getHeight();
    }

    private boolean isOutOfBounds(int x, int y) {
        return x < -5 || y < -5 || x > (getWidth() + 5)
                || y > (getHeight() + 5);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        int action = event.getAction();
        if (mHasNonClientDecor && mNonClientDecorView.mVisible) {
            // Don't dispatch ACTION_DOWN to the non client decor if the window is
            // resizable and the event was (starting) outside the window.
            // Window resizing events should be handled by WindowManager.
            // TODO: Investigate how to handle the outside touch in window manager
            //       without generating these events.
            //       Currently we receive these because we need to enlarge the window's
            //       touch region so that the monitor channel receives the events
            //       in the outside touch area.
            if (action == MotionEvent.ACTION_DOWN) {
                final int x = (int) event.getX();
                final int y = (int) event.getY();
                if (isOutOfInnerBounds(x, y)) {
                    return true;
                }
            }
        }

        if (mFeatureId >= 0) {
            if (action == MotionEvent.ACTION_DOWN) {
                int x = (int)event.getX();
                int y = (int)event.getY();
                if (isOutOfBounds(x, y)) {
                    mWindow.closePanel(mFeatureId);
                    return true;
                }
            }
        }

        if (!SWEEP_OPEN_MENU) {
            return false;
        }

        if (mFeatureId >= 0) {
            if (action == MotionEvent.ACTION_DOWN) {
                Log.i(TAG, "Watchiing!");
                mWatchingForMenu = true;
                mDownY = (int) event.getY();
                return false;
            }

            if (!mWatchingForMenu) {
                return false;
            }

            int y = (int)event.getY();
            if (action == MotionEvent.ACTION_MOVE) {
                if (y > (mDownY+30)) {
                    Log.i(TAG, "Closing!");
                    mWindow.closePanel(mFeatureId);
                    mWatchingForMenu = false;
                    return true;
                }
            } else if (action == MotionEvent.ACTION_UP) {
                mWatchingForMenu = false;
            }

            return false;
        }

        //Log.i(TAG, "Intercept: action=" + action + " y=" + event.getY()
        //        + " (in " + getHeight() + ")");

        if (action == MotionEvent.ACTION_DOWN) {
            int y = (int)event.getY();
            if (y >= (getHeight()-5) && !mWindow.hasChildren()) {
                Log.i(TAG, "Watching!");
                mWatchingForMenu = true;
            }
            return false;
        }

        if (!mWatchingForMenu) {
            return false;
        }

        int y = (int)event.getY();
        if (action == MotionEvent.ACTION_MOVE) {
            if (y < (getHeight()-30)) {
                Log.i(TAG, "Opening!");
                mWindow.openPanel(Window.FEATURE_OPTIONS_PANEL, new KeyEvent(
                        KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU));
                mWatchingForMenu = false;
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP) {
            mWatchingForMenu = false;
        }

        return false;
    }

    @Override
    public void sendAccessibilityEvent(int eventType) {
        if (!AccessibilityManager.getInstance(mContext).isEnabled()) {
            return;
        }

        // if we are showing a feature that should be announced and one child
        // make this child the event source since this is the feature itself
        // otherwise the callback will take over and announce its client
        if ((mFeatureId == Window.FEATURE_OPTIONS_PANEL ||
                mFeatureId == Window.FEATURE_CONTEXT_MENU ||
                mFeatureId == Window.FEATURE_PROGRESS ||
                mFeatureId == Window.FEATURE_INDETERMINATE_PROGRESS)
                && getChildCount() == 1) {
            getChildAt(0).sendAccessibilityEvent(eventType);
        } else {
            super.sendAccessibilityEvent(eventType);
        }
    }

    @Override
    public boolean dispatchPopulateAccessibilityEventInternal(AccessibilityEvent event) {
        final Window.Callback cb = mWindow.getCallback();
        if (cb != null && !mWindow.isDestroyed()) {
            if (cb.dispatchPopulateAccessibilityEvent(event)) {
                return true;
            }
        }
        return super.dispatchPopulateAccessibilityEventInternal(event);
    }

    @Override
    protected boolean setFrame(int l, int t, int r, int b) {
        boolean changed = super.setFrame(l, t, r, b);
        if (changed) {
            final Rect drawingBounds = mDrawingBounds;
            getDrawingRect(drawingBounds);

            Drawable fg = getForeground();
            if (fg != null) {
                final Rect frameOffsets = mFrameOffsets;
                drawingBounds.left += frameOffsets.left;
                drawingBounds.top += frameOffsets.top;
                drawingBounds.right -= frameOffsets.right;
                drawingBounds.bottom -= frameOffsets.bottom;
                fg.setBounds(drawingBounds);
                final Rect framePadding = mFramePadding;
                drawingBounds.left += framePadding.left - frameOffsets.left;
                drawingBounds.top += framePadding.top - frameOffsets.top;
                drawingBounds.right -= framePadding.right - frameOffsets.right;
                drawingBounds.bottom -= framePadding.bottom - frameOffsets.bottom;
            }

            Drawable bg = getBackground();
            if (bg != null) {
                bg.setBounds(drawingBounds);
            }

            if (SWEEP_OPEN_MENU) {
                if (mMenuBackground == null && mFeatureId < 0
                        && mWindow.getAttributes().height
                        == WindowManager.LayoutParams.MATCH_PARENT) {
                    mMenuBackground = getContext().getDrawable(
                            R.drawable.menu_background);
                }
                if (mMenuBackground != null) {
                    mMenuBackground.setBounds(drawingBounds.left,
                            drawingBounds.bottom-6, drawingBounds.right,
                            drawingBounds.bottom+20);
                }
            }
        }
        return changed;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        final boolean isPortrait = metrics.widthPixels < metrics.heightPixels;

        final int widthMode = getMode(widthMeasureSpec);
        final int heightMode = getMode(heightMeasureSpec);

        boolean fixedWidth = false;
        if (widthMode == AT_MOST) {
            final TypedValue tvw = isPortrait ? mWindow.mFixedWidthMinor
                    : mWindow.mFixedWidthMajor;
            if (tvw != null && tvw.type != TypedValue.TYPE_NULL) {
                final int w;
                if (tvw.type == TypedValue.TYPE_DIMENSION) {
                    w = (int) tvw.getDimension(metrics);
                } else if (tvw.type == TypedValue.TYPE_FRACTION) {
                    w = (int) tvw.getFraction(metrics.widthPixels, metrics.widthPixels);
                } else {
                    w = 0;
                }

                if (w > 0) {
                    final int widthSize = MeasureSpec.getSize(widthMeasureSpec);
                    widthMeasureSpec = MeasureSpec.makeMeasureSpec(
                            Math.min(w, widthSize), EXACTLY);
                    fixedWidth = true;
                }
            }
        }

        if (heightMode == AT_MOST) {
            final TypedValue tvh = isPortrait ? mWindow.mFixedHeightMajor
                    : mWindow.mFixedHeightMinor;
            if (tvh != null && tvh.type != TypedValue.TYPE_NULL) {
                final int h;
                if (tvh.type == TypedValue.TYPE_DIMENSION) {
                    h = (int) tvh.getDimension(metrics);
                } else if (tvh.type == TypedValue.TYPE_FRACTION) {
                    h = (int) tvh.getFraction(metrics.heightPixels, metrics.heightPixels);
                } else {
                    h = 0;
                }
                if (h > 0) {
                    final int heightSize = MeasureSpec.getSize(heightMeasureSpec);
                    heightMeasureSpec = MeasureSpec.makeMeasureSpec(
                            Math.min(h, heightSize), EXACTLY);
                }
            }
        }

        getOutsets(mOutsets);
        if (mOutsets.top > 0 || mOutsets.bottom > 0) {
            int mode = MeasureSpec.getMode(heightMeasureSpec);
            if (mode != MeasureSpec.UNSPECIFIED) {
                int height = MeasureSpec.getSize(heightMeasureSpec);
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(
                        height + mOutsets.top + mOutsets.bottom, mode);
            }
        }
        if (mOutsets.left > 0 || mOutsets.right > 0) {
            int mode = MeasureSpec.getMode(widthMeasureSpec);
            if (mode != MeasureSpec.UNSPECIFIED) {
                int width = MeasureSpec.getSize(widthMeasureSpec);
                widthMeasureSpec = MeasureSpec.makeMeasureSpec(
                        width + mOutsets.left + mOutsets.right, mode);
            }
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int width = getMeasuredWidth();
        boolean measure = false;

        widthMeasureSpec = MeasureSpec.makeMeasureSpec(width, EXACTLY);

        if (!fixedWidth && widthMode == AT_MOST) {
            final TypedValue tv = isPortrait ? mWindow.mMinWidthMinor : mWindow.mMinWidthMajor;
            if (tv.type != TypedValue.TYPE_NULL) {
                final int min;
                if (tv.type == TypedValue.TYPE_DIMENSION) {
                    min = (int)tv.getDimension(metrics);
                } else if (tv.type == TypedValue.TYPE_FRACTION) {
                    min = (int)tv.getFraction(metrics.widthPixels, metrics.widthPixels);
                } else {
                    min = 0;
                }

                if (width < min) {
                    widthMeasureSpec = MeasureSpec.makeMeasureSpec(min, EXACTLY);
                    measure = true;
                }
            }
        }

        // TODO: Support height?

        if (measure) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        getOutsets(mOutsets);
        if (mOutsets.left > 0) {
            offsetLeftAndRight(-mOutsets.left);
        }
        if (mOutsets.top > 0) {
            offsetTopAndBottom(-mOutsets.top);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);

        if (mMenuBackground != null) {
            mMenuBackground.draw(canvas);
        }
    }

    @Override
    public boolean showContextMenuForChild(View originalView) {
        // Reuse the context menu builder
        if (mWindow.mContextMenu == null) {
            mWindow.mContextMenu = new ContextMenuBuilder(getContext());
            mWindow.mContextMenu.setCallback(mWindow.mContextMenuCallback);
        } else {
            mWindow.mContextMenu.clearAll();
        }

        final MenuDialogHelper helper = mWindow.mContextMenu.show(originalView,
                originalView.getWindowToken());
        if (helper != null) {
            helper.setPresenterCallback(mWindow.mContextMenuCallback);
        } else if (mWindow.mContextMenuHelper != null) {
            // No menu to show, but if we have a menu currently showing it just became blank.
            // Close it.
            mWindow.mContextMenuHelper.dismiss();
        }
        mWindow.mContextMenuHelper = helper;
        return helper != null;
    }

    @Override
    public boolean showContextMenuForChild(View originalView, float x, float y) {
        // Reuse the context menu builder
        if (mWindow.mContextMenu == null) {
            mWindow.mContextMenu = new ContextMenuBuilder(getContext());
            mWindow.mContextMenu.setCallback(mWindow.mContextMenuCallback);
        } else {
            mWindow.mContextMenu.clearAll();
        }

        final MenuPopupHelper helper = mWindow.mContextMenu.showPopup(
                getContext(), originalView, x, y);
        if (helper != null) {
            helper.setCallback(mWindow.mContextMenuCallback);
        } else if (mWindow.mContextMenuPopupHelper != null) {
            // No menu to show, but if we have a menu currently showing it just became blank.
            // Close it.
            mWindow.mContextMenuPopupHelper.dismiss();
        }
        mWindow.mContextMenuPopupHelper = helper;
        return helper != null;
    }

    @Override
    public ActionMode startActionModeForChild(View originalView,
            ActionMode.Callback callback) {
        return startActionModeForChild(originalView, callback, ActionMode.TYPE_PRIMARY);
    }

    @Override
    public ActionMode startActionModeForChild(
            View child, ActionMode.Callback callback, int type) {
        return startActionMode(child, callback, type);
    }

    @Override
    public ActionMode startActionMode(ActionMode.Callback callback) {
        return startActionMode(callback, ActionMode.TYPE_PRIMARY);
    }

    @Override
    public ActionMode startActionMode(ActionMode.Callback callback, int type) {
        return startActionMode(this, callback, type);
    }

    private ActionMode startActionMode(
            View originatingView, ActionMode.Callback callback, int type) {
        ActionMode.Callback2 wrappedCallback = new ActionModeCallback2Wrapper(callback);
        ActionMode mode = null;
        if (mWindow.getCallback() != null && !mWindow.isDestroyed()) {
            try {
                mode = mWindow.getCallback().onWindowStartingActionMode(wrappedCallback, type);
            } catch (AbstractMethodError ame) {
                // Older apps might not implement the typed version of this method.
                if (type == ActionMode.TYPE_PRIMARY) {
                    try {
                        mode = mWindow.getCallback().onWindowStartingActionMode(
                                wrappedCallback);
                    } catch (AbstractMethodError ame2) {
                        // Older apps might not implement this callback method at all.
                    }
                }
            }
        }
        if (mode != null) {
            if (mode.getType() == ActionMode.TYPE_PRIMARY) {
                cleanupPrimaryActionMode();
                mPrimaryActionMode = mode;
            } else if (mode.getType() == ActionMode.TYPE_FLOATING) {
                if (mFloatingActionMode != null) {
                    mFloatingActionMode.finish();
                }
                mFloatingActionMode = mode;
            }
        } else {
            mode = createActionMode(type, wrappedCallback, originatingView);
            if (mode != null && wrappedCallback.onCreateActionMode(mode, mode.getMenu())) {
                setHandledActionMode(mode);
            } else {
                mode = null;
            }
        }
        if (mode != null && mWindow.getCallback() != null && !mWindow.isDestroyed()) {
            try {
                mWindow.getCallback().onActionModeStarted(mode);
            } catch (AbstractMethodError ame) {
                // Older apps might not implement this callback method.
            }
        }
        return mode;
    }

    private void cleanupPrimaryActionMode() {
        if (mPrimaryActionMode != null) {
            mPrimaryActionMode.finish();
            mPrimaryActionMode = null;
        }
        if (mPrimaryActionModeView != null) {
            mPrimaryActionModeView.killMode();
        }
    }

    private void cleanupFloatingActionModeViews() {
        if (mFloatingToolbar != null) {
            mFloatingToolbar.dismiss();
            mFloatingToolbar = null;
        }
        if (mFloatingActionModeOriginatingView != null) {
            if (mFloatingToolbarPreDrawListener != null) {
                mFloatingActionModeOriginatingView.getViewTreeObserver()
                    .removeOnPreDrawListener(mFloatingToolbarPreDrawListener);
                mFloatingToolbarPreDrawListener = null;
            }
            mFloatingActionModeOriginatingView = null;
        }
    }

    public void startChanging() {
        mChanging = true;
    }

    public void finishChanging() {
        mChanging = false;
        drawableChanged();
    }

    public void setWindowBackground(Drawable drawable) {
        if (getBackground() != drawable) {
            setBackgroundDrawable(drawable);
            if (drawable != null) {
                drawable.getPadding(mBackgroundPadding);
            } else {
                mBackgroundPadding.setEmpty();
            }
            drawableChanged();
        }
    }

    public void setWindowFrame(Drawable drawable) {
        if (getForeground() != drawable) {
            setForeground(drawable);
            if (drawable != null) {
                drawable.getPadding(mFramePadding);
            } else {
                mFramePadding.setEmpty();
            }
            drawableChanged();
        }
    }

    @Override
    public void onWindowSystemUiVisibilityChanged(int visible) {
        updateColorViews(null /* insets */, true /* animate */);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        mFrameOffsets.set(insets.getSystemWindowInsets());
        insets = updateColorViews(insets, true /* animate */);
        insets = updateStatusGuard(insets);
        updateNavigationGuard(insets);
        if (getForeground() != null) {
            drawableChanged();
        }
        return insets;
    }

    @Override
    public boolean isTransitionGroup() {
        return false;
    }

    WindowInsets updateColorViews(WindowInsets insets, boolean animate) {
        WindowManager.LayoutParams attrs = mWindow.getAttributes();
        int sysUiVisibility = attrs.systemUiVisibility | getWindowSystemUiVisibility();

        if (!mWindow.mIsFloating && ActivityManager.isHighEndGfx()) {
            boolean disallowAnimate = !isLaidOut();
            disallowAnimate |= ((mLastWindowFlags ^ attrs.flags)
                    & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0;
            mLastWindowFlags = attrs.flags;

            if (insets != null) {
                mLastTopInset = Math.min(insets.getStableInsetTop(),
                        insets.getSystemWindowInsetTop());
                mLastBottomInset = Math.min(insets.getStableInsetBottom(),
                        insets.getSystemWindowInsetBottom());
                mLastRightInset = Math.min(insets.getStableInsetRight(),
                        insets.getSystemWindowInsetRight());

                // Don't animate if the presence of stable insets has changed, because that
                // indicates that the window was either just added and received them for the
                // first time, or the window size or position has changed.
                boolean hasTopStableInset = insets.getStableInsetTop() != 0;
                disallowAnimate |= (hasTopStableInset != mLastHasTopStableInset);
                mLastHasTopStableInset = hasTopStableInset;

                boolean hasBottomStableInset = insets.getStableInsetBottom() != 0;
                disallowAnimate |= (hasBottomStableInset != mLastHasBottomStableInset);
                mLastHasBottomStableInset = hasBottomStableInset;

                boolean hasRightStableInset = insets.getStableInsetRight() != 0;
                disallowAnimate |= (hasRightStableInset != mLastHasRightStableInset);
                mLastHasRightStableInset = hasRightStableInset;
            }

            boolean navBarToRightEdge = mLastBottomInset == 0 && mLastRightInset > 0;
            int navBarSize = navBarToRightEdge ? mLastRightInset : mLastBottomInset;
            updateColorViewInt(mNavigationColorViewState, sysUiVisibility,
                    mWindow.mNavigationBarColor, navBarSize, navBarToRightEdge,
                    0 /* rightInset */, animate && !disallowAnimate);

            boolean statusBarNeedsRightInset = navBarToRightEdge
                    && mNavigationColorViewState.present;
            int statusBarRightInset = statusBarNeedsRightInset ? mLastRightInset : 0;
            updateColorViewInt(mStatusColorViewState, sysUiVisibility, mWindow.mStatusBarColor,
                    mLastTopInset, false /* matchVertical */, statusBarRightInset,
                    animate && !disallowAnimate);
        }

        // When we expand the window with FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS, we still need
        // to ensure that the rest of the view hierarchy doesn't notice it, unless they've
        // explicitly asked for it.

        boolean consumingNavBar =
                (attrs.flags & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0
                        && (sysUiVisibility & SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION) == 0
                        && (sysUiVisibility & SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0;

        int consumedRight = consumingNavBar ? mLastRightInset : 0;
        int consumedBottom = consumingNavBar ? mLastBottomInset : 0;

        if (mContentRoot != null
                && mContentRoot.getLayoutParams() instanceof MarginLayoutParams) {
            MarginLayoutParams lp = (MarginLayoutParams) mContentRoot.getLayoutParams();
            if (lp.rightMargin != consumedRight || lp.bottomMargin != consumedBottom) {
                lp.rightMargin = consumedRight;
                lp.bottomMargin = consumedBottom;
                mContentRoot.setLayoutParams(lp);

                if (insets == null) {
                    // The insets have changed, but we're not currently in the process
                    // of dispatching them.
                    requestApplyInsets();
                }
            }
            if (insets != null) {
                insets = insets.replaceSystemWindowInsets(
                        insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight() - consumedRight,
                        insets.getSystemWindowInsetBottom() - consumedBottom);
            }
        }

        if (insets != null) {
            insets = insets.consumeStableInsets();
        }
        return insets;
    }

    /**
     * Update a color view
     *
     * @param state the color view to update.
     * @param sysUiVis the current systemUiVisibility to apply.
     * @param color the current color to apply.
     * @param size the current size in the non-parent-matching dimension.
     * @param verticalBar if true the view is attached to a vertical edge, otherwise to a
     *                    horizontal edge,
     * @param rightMargin rightMargin for the color view.
     * @param animate if true, the change will be animated.
     */
    private void updateColorViewInt(final ColorViewState state, int sysUiVis, int color,
            int size, boolean verticalBar, int rightMargin, boolean animate) {
        state.present = size > 0 && (sysUiVis & state.systemUiHideFlag) == 0
                && (mWindow.getAttributes().flags & state.hideWindowFlag) == 0
                && (mWindow.getAttributes().flags & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0;
        boolean show = state.present
                && (color & Color.BLACK) != 0
                && (mWindow.getAttributes().flags & state.translucentFlag) == 0;

        boolean visibilityChanged = false;
        View view = state.view;

        int resolvedHeight = verticalBar ? LayoutParams.MATCH_PARENT : size;
        int resolvedWidth = verticalBar ? size : LayoutParams.MATCH_PARENT;
        int resolvedGravity = verticalBar ? state.horizontalGravity : state.verticalGravity;

        if (view == null) {
            if (show) {
                state.view = view = new View(mContext);
                view.setBackgroundColor(color);
                view.setTransitionName(state.transitionName);
                view.setId(state.id);
                visibilityChanged = true;
                view.setVisibility(INVISIBLE);
                state.targetVisibility = VISIBLE;

                LayoutParams lp = new LayoutParams(resolvedWidth, resolvedHeight,
                        resolvedGravity);
                lp.rightMargin = rightMargin;
                addView(view, lp);
                updateColorViewTranslations();
            }
        } else {
            int vis = show ? VISIBLE : INVISIBLE;
            visibilityChanged = state.targetVisibility != vis;
            state.targetVisibility = vis;
            LayoutParams lp = (LayoutParams) view.getLayoutParams();
            if (lp.height != resolvedHeight || lp.width != resolvedWidth
                    || lp.gravity != resolvedGravity || lp.rightMargin != rightMargin) {
                lp.height = resolvedHeight;
                lp.width = resolvedWidth;
                lp.gravity = resolvedGravity;
                lp.rightMargin = rightMargin;
                view.setLayoutParams(lp);
            }
            if (show) {
                view.setBackgroundColor(color);
            }
        }
        if (visibilityChanged) {
            view.animate().cancel();
            if (animate) {
                if (show) {
                    if (view.getVisibility() != VISIBLE) {
                        view.setVisibility(VISIBLE);
                        view.setAlpha(0.0f);
                    }
                    view.animate().alpha(1.0f).setInterpolator(mShowInterpolator).
                            setDuration(mBarEnterExitDuration);
                } else {
                    view.animate().alpha(0.0f).setInterpolator(mHideInterpolator)
                            .setDuration(mBarEnterExitDuration)
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    state.view.setAlpha(1.0f);
                                    state.view.setVisibility(INVISIBLE);
                                }
                            });
                }
            } else {
                view.setAlpha(1.0f);
                view.setVisibility(show ? VISIBLE : INVISIBLE);
            }
        }
    }

    private void updateColorViewTranslations() {
        // Put the color views back in place when they get moved off the screen
        // due to the the ViewRootImpl panning.
        int rootScrollY = mRootScrollY;
        if (mStatusColorViewState.view != null) {
            mStatusColorViewState.view.setTranslationY(rootScrollY > 0 ? rootScrollY : 0);
        }
        if (mNavigationColorViewState.view != null) {
            mNavigationColorViewState.view.setTranslationY(rootScrollY < 0 ? rootScrollY : 0);
        }
    }

    private WindowInsets updateStatusGuard(WindowInsets insets) {
        boolean showStatusGuard = false;
        // Show the status guard when the non-overlay contextual action bar is showing
        if (mPrimaryActionModeView != null) {
            if (mPrimaryActionModeView.getLayoutParams() instanceof MarginLayoutParams) {
                // Insets are magic!
                final MarginLayoutParams mlp = (MarginLayoutParams)
                        mPrimaryActionModeView.getLayoutParams();
                boolean mlpChanged = false;
                if (mPrimaryActionModeView.isShown()) {
                    if (mTempRect == null) {
                        mTempRect = new Rect();
                    }
                    final Rect rect = mTempRect;

                    // If the parent doesn't consume the insets, manually
                    // apply the default system window insets.
                    mWindow.mContentParent.computeSystemWindowInsets(insets, rect);
                    final int newMargin = rect.top == 0 ? insets.getSystemWindowInsetTop() : 0;
                    if (mlp.topMargin != newMargin) {
                        mlpChanged = true;
                        mlp.topMargin = insets.getSystemWindowInsetTop();

                        if (mStatusGuard == null) {
                            mStatusGuard = new View(mContext);
                            mStatusGuard.setBackgroundColor(mContext.getColor(
                                    R.color.input_method_navigation_guard));
                            addView(mStatusGuard, indexOfChild(mStatusColorViewState.view),
                                    new LayoutParams(LayoutParams.MATCH_PARENT,
                                            mlp.topMargin, Gravity.START | Gravity.TOP));
                        } else {
                            final LayoutParams lp = (LayoutParams)
                                    mStatusGuard.getLayoutParams();
                            if (lp.height != mlp.topMargin) {
                                lp.height = mlp.topMargin;
                                mStatusGuard.setLayoutParams(lp);
                            }
                        }
                    }

                    // The action mode's theme may differ from the app, so
                    // always show the status guard above it if we have one.
                    showStatusGuard = mStatusGuard != null;

                    // We only need to consume the insets if the action
                    // mode is overlaid on the app content (e.g. it's
                    // sitting in a FrameLayout, see
                    // screen_simple_overlay_action_mode.xml).
                    final boolean nonOverlay = (mWindow.getLocalFeaturesPrivate()
                            & (1 << Window.FEATURE_ACTION_MODE_OVERLAY)) == 0;
                    insets = insets.consumeSystemWindowInsets(
                            false, nonOverlay && showStatusGuard /* top */, false, false);
                } else {
                    // reset top margin
                    if (mlp.topMargin != 0) {
                        mlpChanged = true;
                        mlp.topMargin = 0;
                    }
                }
                if (mlpChanged) {
                    mPrimaryActionModeView.setLayoutParams(mlp);
                }
            }
        }
        if (mStatusGuard != null) {
            mStatusGuard.setVisibility(showStatusGuard ? View.VISIBLE : View.GONE);
        }
        return insets;
    }

    private void updateNavigationGuard(WindowInsets insets) {
        // IMEs lay out below the nav bar, but the content view must not (for back compat)
        if (mWindow.getAttributes().type == WindowManager.LayoutParams.TYPE_INPUT_METHOD) {
            // prevent the content view from including the nav bar height
            if (mWindow.mContentParent != null) {
                if (mWindow.mContentParent.getLayoutParams() instanceof MarginLayoutParams) {
                    MarginLayoutParams mlp =
                            (MarginLayoutParams) mWindow.mContentParent.getLayoutParams();
                    mlp.bottomMargin = insets.getSystemWindowInsetBottom();
                    mWindow.mContentParent.setLayoutParams(mlp);
                }
            }
            // position the navigation guard view, creating it if necessary
            if (mNavigationGuard == null) {
                mNavigationGuard = new View(mContext);
                mNavigationGuard.setBackgroundColor(mContext.getColor(
                        R.color.input_method_navigation_guard));
                addView(mNavigationGuard, indexOfChild(mNavigationColorViewState.view),
                        new LayoutParams(LayoutParams.MATCH_PARENT,
                                insets.getSystemWindowInsetBottom(),
                                Gravity.START | Gravity.BOTTOM));
            } else {
                LayoutParams lp = (LayoutParams) mNavigationGuard.getLayoutParams();
                lp.height = insets.getSystemWindowInsetBottom();
                mNavigationGuard.setLayoutParams(lp);
            }
        }
    }

    private void drawableChanged() {
        if (mChanging) {
            return;
        }

        setPadding(mFramePadding.left + mBackgroundPadding.left,
                mFramePadding.top + mBackgroundPadding.top,
                mFramePadding.right + mBackgroundPadding.right,
                mFramePadding.bottom + mBackgroundPadding.bottom);
        requestLayout();
        invalidate();

        int opacity = PixelFormat.OPAQUE;
        if (windowHasShadow()) {
            // If the window has a shadow, it must be translucent.
            opacity = PixelFormat.TRANSLUCENT;
        } else{
            // Note: If there is no background, we will assume opaque. The
            // common case seems to be that an application sets there to be
            // no background so it can draw everything itself. For that,
            // we would like to assume OPAQUE and let the app force it to
            // the slower TRANSLUCENT mode if that is really what it wants.
            Drawable bg = getBackground();
            Drawable fg = getForeground();
            if (bg != null) {
                if (fg == null) {
                    opacity = bg.getOpacity();
                } else if (mFramePadding.left <= 0 && mFramePadding.top <= 0
                        && mFramePadding.right <= 0 && mFramePadding.bottom <= 0) {
                    // If the frame padding is zero, then we can be opaque
                    // if either the frame -or- the background is opaque.
                    int fop = fg.getOpacity();
                    int bop = bg.getOpacity();
                    if (false)
                        Log.v(TAG, "Background opacity: " + bop + ", Frame opacity: " + fop);
                    if (fop == PixelFormat.OPAQUE || bop == PixelFormat.OPAQUE) {
                        opacity = PixelFormat.OPAQUE;
                    } else if (fop == PixelFormat.UNKNOWN) {
                        opacity = bop;
                    } else if (bop == PixelFormat.UNKNOWN) {
                        opacity = fop;
                    } else {
                        opacity = Drawable.resolveOpacity(fop, bop);
                    }
                } else {
                    // For now we have to assume translucent if there is a
                    // frame with padding... there is no way to tell if the
                    // frame and background together will draw all pixels.
                    if (false)
                        Log.v(TAG, "Padding: " + mFramePadding);
                    opacity = PixelFormat.TRANSLUCENT;
                }
            }
            if (false)
                Log.v(TAG, "Background: " + bg + ", Frame: " + fg);
        }

        if (false)
            Log.v(TAG, "Selected default opacity: " + opacity);

        mDefaultOpacity = opacity;
        if (mFeatureId < 0) {
            mWindow.setDefaultWindowFormat(opacity);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);

        // If the user is chording a menu shortcut, release the chord since
        // this window lost focus
        if (mWindow.hasFeature(Window.FEATURE_OPTIONS_PANEL) && !hasWindowFocus
                && mWindow.mPanelChordingKey != 0) {
            mWindow.closePanel(Window.FEATURE_OPTIONS_PANEL);
        }

        final Window.Callback cb = mWindow.getCallback();
        if (cb != null && !mWindow.isDestroyed() && mFeatureId < 0) {
            cb.onWindowFocusChanged(hasWindowFocus);
        }

        if (mPrimaryActionMode != null) {
            mPrimaryActionMode.onWindowFocusChanged(hasWindowFocus);
        }
        if (mFloatingActionMode != null) {
            mFloatingActionMode.onWindowFocusChanged(hasWindowFocus);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        final Window.Callback cb = mWindow.getCallback();
        if (cb != null && !mWindow.isDestroyed() && mFeatureId < 0) {
            cb.onAttachedToWindow();
        }

        if (mFeatureId == -1) {
            /*
             * The main window has been attached, try to restore any panels
             * that may have been open before. This is called in cases where
             * an activity is being killed for configuration change and the
             * menu was open. When the activity is recreated, the menu
             * should be shown again.
             */
            mWindow.openPanelsAfterRestore();
        }

        if (!mWindowResizeCallbacksAdded) {
            // If there is no window callback installed there was no window set before. Set it now.
            // Note that our ViewRootImpl object will not change.
            getViewRootImpl().addWindowCallbacks(this);
            mWindowResizeCallbacksAdded = true;
        } else if (mBackdropFrameRenderer != null) {
            // We are resizing and this call happened due to a configuration change. Tell the
            // renderer about it.
            mBackdropFrameRenderer.onConfigurationChange();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        final Window.Callback cb = mWindow.getCallback();
        if (cb != null && mFeatureId < 0) {
            cb.onDetachedFromWindow();
        }

        if (mWindow.mDecorContentParent != null) {
            mWindow.mDecorContentParent.dismissPopups();
        }

        if (mPrimaryActionModePopup != null) {
            removeCallbacks(mShowPrimaryActionModePopup);
            if (mPrimaryActionModePopup.isShowing()) {
                mPrimaryActionModePopup.dismiss();
            }
            mPrimaryActionModePopup = null;
        }
        if (mFloatingToolbar != null) {
            mFloatingToolbar.dismiss();
            mFloatingToolbar = null;
        }

        PhoneWindow.PanelFeatureState st = mWindow.getPanelState(Window.FEATURE_OPTIONS_PANEL, false);
        if (st != null && st.menu != null && mFeatureId < 0) {
            st.menu.close();
        }

        if (mWindowResizeCallbacksAdded) {
            getViewRootImpl().removeWindowCallbacks(this);
            mWindowResizeCallbacksAdded = false;
        }
    }

    @Override
    public void onCloseSystemDialogs(String reason) {
        if (mFeatureId >= 0) {
            mWindow.closeAllPanels();
        }
    }

    public android.view.SurfaceHolder.Callback2 willYouTakeTheSurface() {
        return mFeatureId < 0 ? mWindow.mTakeSurfaceCallback : null;
    }

    public InputQueue.Callback willYouTakeTheInputQueue() {
        return mFeatureId < 0 ? mWindow.mTakeInputQueueCallback : null;
    }

    public void setSurfaceType(int type) {
        mWindow.setType(type);
    }

    public void setSurfaceFormat(int format) {
        mWindow.setFormat(format);
    }

    public void setSurfaceKeepScreenOn(boolean keepOn) {
        if (keepOn) mWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else mWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onRootViewScrollYChanged(int rootScrollY) {
        mRootScrollY = rootScrollY;
        updateColorViewTranslations();
    }

    private ActionMode createActionMode(
            int type, ActionMode.Callback2 callback, View originatingView) {
        switch (type) {
            case ActionMode.TYPE_PRIMARY:
            default:
                return createStandaloneActionMode(callback);
            case ActionMode.TYPE_FLOATING:
                return createFloatingActionMode(originatingView, callback);
        }
    }

    private void setHandledActionMode(ActionMode mode) {
        if (mode.getType() == ActionMode.TYPE_PRIMARY) {
            setHandledPrimaryActionMode(mode);
        } else if (mode.getType() == ActionMode.TYPE_FLOATING) {
            setHandledFloatingActionMode(mode);
        }
    }

    private ActionMode createStandaloneActionMode(ActionMode.Callback callback) {
        endOnGoingFadeAnimation();
        cleanupPrimaryActionMode();
        if (mPrimaryActionModeView == null) {
            if (mWindow.isFloating()) {
                // Use the action bar theme.
                final TypedValue outValue = new TypedValue();
                final Resources.Theme baseTheme = mContext.getTheme();
                baseTheme.resolveAttribute(R.attr.actionBarTheme, outValue, true);

                final Context actionBarContext;
                if (outValue.resourceId != 0) {
                    final Resources.Theme actionBarTheme = mContext.getResources().newTheme();
                    actionBarTheme.setTo(baseTheme);
                    actionBarTheme.applyStyle(outValue.resourceId, true);

                    actionBarContext = new ContextThemeWrapper(mContext, 0);
                    actionBarContext.getTheme().setTo(actionBarTheme);
                } else {
                    actionBarContext = mContext;
                }

                mPrimaryActionModeView = new ActionBarContextView(actionBarContext);
                mPrimaryActionModePopup = new PopupWindow(actionBarContext, null,
                        R.attr.actionModePopupWindowStyle);
                mPrimaryActionModePopup.setWindowLayoutType(
                        WindowManager.LayoutParams.TYPE_APPLICATION);
                mPrimaryActionModePopup.setContentView(mPrimaryActionModeView);
                mPrimaryActionModePopup.setWidth(MATCH_PARENT);

                actionBarContext.getTheme().resolveAttribute(
                        R.attr.actionBarSize, outValue, true);
                final int height = TypedValue.complexToDimensionPixelSize(outValue.data,
                        actionBarContext.getResources().getDisplayMetrics());
                mPrimaryActionModeView.setContentHeight(height);
                mPrimaryActionModePopup.setHeight(WRAP_CONTENT);
                mShowPrimaryActionModePopup = new Runnable() {
                    public void run() {
                        mPrimaryActionModePopup.showAtLocation(
                                mPrimaryActionModeView.getApplicationWindowToken(),
                                Gravity.TOP | Gravity.FILL_HORIZONTAL, 0, 0);
                        endOnGoingFadeAnimation();
                        mFadeAnim = ObjectAnimator.ofFloat(mPrimaryActionModeView, View.ALPHA,
                                0f, 1f);
                        mFadeAnim.addListener(new Animator.AnimatorListener() {
                            @Override
                            public void onAnimationStart(Animator animation) {
                                mPrimaryActionModeView.setVisibility(VISIBLE);
                            }

                            @Override
                            public void onAnimationEnd(Animator animation) {
                                mPrimaryActionModeView.setAlpha(1f);
                                mFadeAnim = null;
                            }

                            @Override
                            public void onAnimationCancel(Animator animation) {

                            }

                            @Override
                            public void onAnimationRepeat(Animator animation) {

                            }
                        });
                        mFadeAnim.start();
                    }
                };
            } else {
                ViewStub stub = (ViewStub) findViewById(R.id.action_mode_bar_stub);
                if (stub != null) {
                    mPrimaryActionModeView = (ActionBarContextView) stub.inflate();
                }
            }
        }
        if (mPrimaryActionModeView != null) {
            mPrimaryActionModeView.killMode();
            ActionMode mode = new StandaloneActionMode(
                    mPrimaryActionModeView.getContext(), mPrimaryActionModeView,
                    callback, mPrimaryActionModePopup == null);
            return mode;
        }
        return null;
    }

    private void endOnGoingFadeAnimation() {
        if (mFadeAnim != null) {
            mFadeAnim.end();
        }
    }

    private void setHandledPrimaryActionMode(ActionMode mode) {
        endOnGoingFadeAnimation();
        mPrimaryActionMode = mode;
        mPrimaryActionMode.invalidate();
        mPrimaryActionModeView.initForMode(mPrimaryActionMode);
        if (mPrimaryActionModePopup != null) {
            post(mShowPrimaryActionModePopup);
        } else {
            mFadeAnim = ObjectAnimator.ofFloat(mPrimaryActionModeView, View.ALPHA, 0f, 1f);
            mFadeAnim.addListener(new Animator.AnimatorListener() {
                @Override
                public void onAnimationStart(Animator animation) {
                    mPrimaryActionModeView.setVisibility(View.VISIBLE);
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    mPrimaryActionModeView.setAlpha(1f);
                    mFadeAnim = null;
                }

                @Override
                public void onAnimationCancel(Animator animation) {

                }

                @Override
                public void onAnimationRepeat(Animator animation) {

                }
            });
            mFadeAnim.start();
        }
        mPrimaryActionModeView.sendAccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
    }

    private ActionMode createFloatingActionMode(
            View originatingView, ActionMode.Callback2 callback) {
        if (mFloatingActionMode != null) {
            mFloatingActionMode.finish();
        }
        cleanupFloatingActionModeViews();
        final FloatingActionMode mode =
                new FloatingActionMode(mContext, callback, originatingView);
        mFloatingActionModeOriginatingView = originatingView;
        mFloatingToolbarPreDrawListener =
            new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    mode.updateViewLocationInWindow();
                    return true;
                }
            };
        return mode;
    }

    private void setHandledFloatingActionMode(ActionMode mode) {
        mFloatingActionMode = mode;
        mFloatingToolbar = new FloatingToolbar(mContext, mWindow);
        ((FloatingActionMode) mFloatingActionMode).setFloatingToolbar(mFloatingToolbar);
        mFloatingActionMode.invalidate();  // Will show the floating toolbar if necessary.
        mFloatingActionModeOriginatingView.getViewTreeObserver()
            .addOnPreDrawListener(mFloatingToolbarPreDrawListener);
    }

    /**
     * Informs the decor if a non client decor is attached and visible.
     * @param attachedAndVisible true when the decor is visible.
     * Note that this will even be called if there is no non client decor.
     **/
    void enableNonClientDecor(boolean attachedAndVisible) {
        if (mHasNonClientDecor != attachedAndVisible) {
            mHasNonClientDecor = attachedAndVisible;
            if (getForeground() != null) {
                drawableChanged();
            }
        }
    }

    /**
     * Returns true if the window has a non client decor.
     * @return If there is a non client decor - even if it is not visible.
     **/
    private boolean windowHasNonClientDecor() {
        return mHasNonClientDecor;
    }

    /**
     * Returns true if the Window is free floating and has a shadow (although at some times
     * it might not be displaying it, e.g. during a resize). Note that non overlapping windows
     * do not have a shadow since it could not be seen anyways (a small screen / tablet
     * "tiles" the windows side by side but does not overlap them).
     * @return Returns true when the window has a shadow created by the non client decor.
     **/
    private boolean windowHasShadow() {
        return windowHasNonClientDecor() && ActivityManager.StackId.hasWindowShadow(mWorkspaceId);
    }

    void setWindow(PhoneWindow phoneWindow) {
        mWindow = phoneWindow;
        Context context = getContext();
        if (context instanceof DecorContext) {
            DecorContext decorContext = (DecorContext) context;
            decorContext.setPhoneWindow(mWindow);
        }
    }

    void onConfigurationChanged() {
        if (mNonClientDecorView != null) {
            int workspaceId = getWorkspaceId();
            if (mWorkspaceId != workspaceId) {
                mWorkspaceId = workspaceId;
                // We might have to change the kind of surface before we do anything else.
                mNonClientDecorView.onConfigurationChanged(
                        ActivityManager.StackId.hasWindowDecor(mWorkspaceId),
                        ActivityManager.StackId.hasWindowShadow(mWorkspaceId));
                enableNonClientDecor(ActivityManager.StackId.hasWindowDecor(workspaceId));
            }
        }
    }

    View onResourcesLoaded(LayoutInflater inflater, int layoutResource) {
        mWorkspaceId = getWorkspaceId();

        mResizingBackgroundDrawable = getResizingBackgroundDrawable(
                mWindow.mBackgroundResource, mWindow.mBackgroundFallbackResource);
        mCaptionBackgroundDrawable =
                getContext().getDrawable(R.drawable.non_client_decor_title_focused);

        if (mBackdropFrameRenderer != null) {
            mBackdropFrameRenderer.onResourcesLoaded(
                    this, mResizingBackgroundDrawable, mCaptionBackgroundDrawable);
        }

        mNonClientDecorView = createNonClientDecorView(inflater);
        final View root = inflater.inflate(layoutResource, null);
        if (mNonClientDecorView != null) {
            if (mNonClientDecorView.getParent() == null) {
                addView(mNonClientDecorView,
                        new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
            }
            mNonClientDecorView.addView(root,
                    new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        } else {
            addView(root, new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        }
        mContentRoot = (ViewGroup) root;
        return root;
    }

    // Free floating overlapping windows require a non client decor with a caption and shadow..
    private NonClientDecorView createNonClientDecorView(LayoutInflater inflater) {
        NonClientDecorView nonClientDecorView = null;
        for (int i = getChildCount() - 1; i >= 0 && nonClientDecorView == null; i--) {
            View view = getChildAt(i);
            if (view instanceof NonClientDecorView) {
                // The decor was most likely saved from a relaunch - so reuse it.
                nonClientDecorView = (NonClientDecorView) view;
                removeViewAt(i);
            }
        }
        final WindowManager.LayoutParams attrs = mWindow.getAttributes();
        boolean isApplication = attrs.type == TYPE_BASE_APPLICATION ||
                attrs.type == TYPE_APPLICATION;
        // Only a non floating application window on one of the allowed workspaces can get a non
        // client decor.
        final boolean hasNonClientDecor = ActivityManager.StackId.hasWindowDecor(mWorkspaceId);
        if (!mWindow.isFloating() && isApplication && hasNonClientDecor) {
            // Dependent on the brightness of the used title we either use the
            // dark or the light button frame.
            if (nonClientDecorView == null) {
                Context context = getContext();
                TypedValue value = new TypedValue();
                context.getTheme().resolveAttribute(R.attr.colorPrimary, value, true);
                inflater = inflater.from(context);
                if (Color.luminance(value.data) < 0.5) {
                    nonClientDecorView = (NonClientDecorView) inflater.inflate(
                            R.layout.non_client_decor_dark, null);
                } else {
                    nonClientDecorView = (NonClientDecorView) inflater.inflate(
                            R.layout.non_client_decor_light, null);
                }
            }
            nonClientDecorView.setPhoneWindow(mWindow,
                    ActivityManager.StackId.hasWindowDecor(mWorkspaceId),
                    ActivityManager.StackId.hasWindowShadow(mWorkspaceId));
        } else {
            nonClientDecorView = null;
        }

        // Tell the decor if it has a visible non client decor.
        enableNonClientDecor(nonClientDecorView != null && hasNonClientDecor);
        return nonClientDecorView;
    }

    /**
     * Returns the color used to fill areas the app has not rendered content to yet when the
     * user is resizing the window of an activity in multi-window mode.
     */
    private Drawable getResizingBackgroundDrawable(int backgroundRes, int backgroundFallbackRes) {
        final Context context = getContext();

        if (backgroundRes != 0) {
            final Drawable drawable = context.getDrawable(backgroundRes);
            if (drawable != null) {
                return drawable;
            }
        }

        if (backgroundFallbackRes != 0) {
            final Drawable fallbackDrawable = context.getDrawable(backgroundFallbackRes);
            if (fallbackDrawable != null) {
                return fallbackDrawable;
            }
        }

        // We shouldn't really get here as the background fallback should be always available since
        // it is defaulted by the system.
        Log.w(TAG, "Failed to find background drawable for PhoneWindow=" + mWindow);
        return null;
    }

    /**
     * Returns the Id of the workspace which contains this window.
     * Note that if no workspace can be determined - which usually means that it was not
     * created for an activity - the fullscreen workspace ID will be returned.
     * @return Returns the workspace stack id which contains this window.
     **/
    private int getWorkspaceId() {
        int workspaceId = INVALID_STACK_ID;
        final Window.WindowControllerCallback callback = mWindow.getWindowControllerCallback();
        if (callback != null) {
            try {
                workspaceId = callback.getWindowStackId();
            } catch (RemoteException ex) {
                Log.e(TAG, "Failed to get the workspace ID of a PhoneWindow.");
            }
        }
        if (workspaceId == INVALID_STACK_ID) {
            return FULLSCREEN_WORKSPACE_STACK_ID;
        }
        return workspaceId;
    }

    void clearContentView() {
        if (mNonClientDecorView != null) {
            if (mNonClientDecorView.getChildCount() > 1) {
                mNonClientDecorView.removeViewAt(1);
            }
        } else {
            // This window doesn't have non client decor, so we need to just remove the
            // children of the decor view.
            removeAllViews();
        }
    }

    @Override
    public void onWindowSizeIsChanging(Rect newBounds) {
        if (mBackdropFrameRenderer != null) {
            mBackdropFrameRenderer.setTargetRect(newBounds);
        }
    }

    @Override
    public void onWindowDragResizeStart(Rect initialBounds) {
        if (mWindow.isDestroyed()) {
            // If the owner's window is gone, we should not be able to come here anymore.
            releaseThreadedRenderer();
            return;
        }
        if (mBackdropFrameRenderer != null) {
            return;
        }
        final ThreadedRenderer renderer = (ThreadedRenderer) getHardwareRenderer();
        if (renderer != null) {
            mBackdropFrameRenderer = new BackdropFrameRenderer(this, renderer,
                    initialBounds, mResizingBackgroundDrawable, mCaptionBackgroundDrawable);

            // Get rid of the shadow while we are resizing. Shadow drawing takes considerable time.
            // If we want to get the shadow shown while resizing, we would need to elevate a new
            // element which owns the caption and has the elevation.
            updateElevation();
        }
    }

    @Override
    public void onWindowDragResizeEnd() {
        releaseThreadedRenderer();
    }

    @Override
    public boolean onContentDrawn(int offsetX, int offsetY, int sizeX, int sizeY) {
        if (mBackdropFrameRenderer == null) {
            return false;
        }
        return mBackdropFrameRenderer.onContentDrawn(offsetX, offsetY, sizeX, sizeY);
    }

    @Override
    public void onRequestDraw(boolean reportNextDraw) {
        if (mBackdropFrameRenderer != null) {
            mBackdropFrameRenderer.onRequestDraw(reportNextDraw);
        } else if (reportNextDraw) {
            // If render thread is gone, just report immediately.
            if (isAttachedToWindow()) {
                getViewRootImpl().reportDrawFinish();
            }
        }
    }

    /** Release the renderer thread which is usually done when the user stops resizing. */
    private void releaseThreadedRenderer() {
        if (mBackdropFrameRenderer != null) {
            mBackdropFrameRenderer.releaseRenderer();
            mBackdropFrameRenderer = null;
            // Bring the shadow back.
            updateElevation();
        }
    }

    private void updateElevation() {
        if (mNonClientDecorView != null) {
            mNonClientDecorView.updateElevation();
        }
    }

    boolean isShowingCaption() {
        return mNonClientDecorView != null && mNonClientDecorView.isShowingDecor();
    }

    int getCaptionHeight() {
        return isShowingCaption() ? mNonClientDecorView.getDecorCaptionHeight() : 0;
    }

    private static class ColorViewState {
        View view = null;
        int targetVisibility = View.INVISIBLE;
        boolean present = false;

        final int id;
        final int systemUiHideFlag;
        final int translucentFlag;
        final int verticalGravity;
        final int horizontalGravity;
        final String transitionName;
        final int hideWindowFlag;

        ColorViewState(int systemUiHideFlag,
                int translucentFlag, int verticalGravity, int horizontalGravity,
                String transitionName, int id, int hideWindowFlag) {
            this.id = id;
            this.systemUiHideFlag = systemUiHideFlag;
            this.translucentFlag = translucentFlag;
            this.verticalGravity = verticalGravity;
            this.horizontalGravity = horizontalGravity;
            this.transitionName = transitionName;
            this.hideWindowFlag = hideWindowFlag;
        }
    }

    /**
     * Clears out internal references when the action mode is destroyed.
     */
    private class ActionModeCallback2Wrapper extends ActionMode.Callback2 {
        private final ActionMode.Callback mWrapped;

        public ActionModeCallback2Wrapper(ActionMode.Callback wrapped) {
            mWrapped = wrapped;
        }

        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            return mWrapped.onCreateActionMode(mode, menu);
        }

        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            requestFitSystemWindows();
            return mWrapped.onPrepareActionMode(mode, menu);
        }

        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            return mWrapped.onActionItemClicked(mode, item);
        }

        public void onDestroyActionMode(ActionMode mode) {
            mWrapped.onDestroyActionMode(mode);
            final boolean isMncApp = mContext.getApplicationInfo().targetSdkVersion
                    >= Build.VERSION_CODES.M;
            final boolean isPrimary;
            final boolean isFloating;
            if (isMncApp) {
                isPrimary = mode == mPrimaryActionMode;
                isFloating = mode == mFloatingActionMode;
                if (!isPrimary && mode.getType() == ActionMode.TYPE_PRIMARY) {
                    Log.e(TAG, "Destroying unexpected ActionMode instance of TYPE_PRIMARY; "
                            + mode + " was not the current primary action mode! Expected "
                            + mPrimaryActionMode);
                }
                if (!isFloating && mode.getType() == ActionMode.TYPE_FLOATING) {
                    Log.e(TAG, "Destroying unexpected ActionMode instance of TYPE_FLOATING; "
                            + mode + " was not the current floating action mode! Expected "
                            + mFloatingActionMode);
                }
            } else {
                isPrimary = mode.getType() == ActionMode.TYPE_PRIMARY;
                isFloating = mode.getType() == ActionMode.TYPE_FLOATING;
            }
            if (isPrimary) {
                if (mPrimaryActionModePopup != null) {
                    removeCallbacks(mShowPrimaryActionModePopup);
                }
                if (mPrimaryActionModeView != null) {
                    endOnGoingFadeAnimation();
                    mFadeAnim = ObjectAnimator.ofFloat(mPrimaryActionModeView, View.ALPHA,
                            1f, 0f);
                    mFadeAnim.addListener(new Animator.AnimatorListener() {
                                @Override
                                public void onAnimationStart(Animator animation) {

                                }

                                @Override
                                public void onAnimationEnd(Animator animation) {
                                    mPrimaryActionModeView.setVisibility(GONE);
                                    if (mPrimaryActionModePopup != null) {
                                        mPrimaryActionModePopup.dismiss();
                                    }
                                    mPrimaryActionModeView.removeAllViews();
                                    mFadeAnim = null;
                                }

                                @Override
                                public void onAnimationCancel(Animator animation) {

                                }

                                @Override
                                public void onAnimationRepeat(Animator animation) {

                                }
                            });
                    mFadeAnim.start();
                }

                mPrimaryActionMode = null;
            } else if (isFloating) {
                cleanupFloatingActionModeViews();
                mFloatingActionMode = null;
            }
            if (mWindow.getCallback() != null && !mWindow.isDestroyed()) {
                try {
                    mWindow.getCallback().onActionModeFinished(mode);
                } catch (AbstractMethodError ame) {
                    // Older apps might not implement this callback method.
                }
            }
            requestFitSystemWindows();
        }

        @Override
        public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
            if (mWrapped instanceof ActionMode.Callback2) {
                ((ActionMode.Callback2) mWrapped).onGetContentRect(mode, view, outRect);
            } else {
                super.onGetContentRect(mode, view, outRect);
            }
        }
    }
}
