/*
 * Copyright (C) 2008 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.animation.LayoutTransition;
import android.animation.LayoutTransition.TransitionListener;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.ActivityManagerNative;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.provider.Settings;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.widget.FrameLayout;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.view.WindowManager;
import android.view.GestureDetector;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Animation;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.systemui.R;
import com.android.systemui.cm.UserContentObserver;
import com.android.systemui.statusbar.policy.DeadZone;
import com.android.systemui.statusbar.policy.KeyButtonView;
import com.android.systemui.navigation.BaseNavigationBar;
import com.android.systemui.statusbar.BarTransitions;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

import cyanogenmod.providers.CMSettings;

public class NavigationBarView extends BaseNavigationBar {
    final static boolean DEBUG = false;
    final static String TAG = "PhoneStatusBar/NavigationBarView";
    final static float PULSE_ALPHA_FADE = 0.3f; // take bar alpha low so keys are vaguely visible but not intrusive during Pulse
    final static int PULSE_FADE_OUT_DURATION = 250;
    final static int PULSE_FADE_IN_DURATION = 200;
	
    // NavBar Power Button
    private OnLongClickListener mPowerListener = new View.OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            ((KeyButtonView) v).sendEvent(KeyEvent.KEYCODE_POWER, KeyEvent.FLAG_LONG_PRESS);
            return true;
        }
    };

    int mBarSize;

    boolean mShowMenu;

    private Drawable mBackIcon, mBackLandIcon, mBackAltIcon, mBackAltLandIcon;
    private Drawable mRecentIcon;
    private Drawable mRecentLandIcon;
    private Drawable mHomeIcon, mHomeLandIcon;
    private Drawable mRecentAltIcon, mRecentAltLandIcon;

    private NavigationBarViewTaskSwitchHelper mTaskSwitchHelper;
    private DeadZone mDeadZone;
    private final NavigationBarTransitions mBarTransitions;
    private GestureDetector mDoubleTapGesture;

    final static String NAVBAR_EDIT_ACTION = "android.intent.action.NAVBAR_EDIT";

    private boolean mInEditMode;
    private NavbarEditor mEditBar;
    private NavBarReceiver mNavBarReceiver;
    private OnClickListener mRecentsClickListener;
    private OnTouchListener mRecentsPreloadListener;
    private OnTouchListener mHomeSearchActionListener;
    private OnLongClickListener mRecentsBackListener;
    private OnLongClickListener mLongPressHomeListener;
    private OnClickListener mNotificationsClickListener;
    private OnLongClickListener mNotificationsLongListener;

    private SettingsObserver mSettingsObserver;

    // performs manual animation in sync with layout transitions
    private final NavTransitionListener mTransitionListener = new NavTransitionListener();

    private boolean mIsLayoutRtl;
    private boolean mWakeAndUnlocking;

    public boolean mNavSwitch = false ;

    private class NavTransitionListener implements TransitionListener {
        private boolean mBackTransitioning;
        private boolean mHomeAppearing;
        private long mStartDelay;
        private long mDuration;
        private TimeInterpolator mInterpolator;

        @Override
        public void startTransition(LayoutTransition transition, ViewGroup container,
                View view, int transitionType) {
            if (NavbarEditor.NAVBAR_BACK.equals(view.getTag())) {
                mBackTransitioning = true;
            } else if (NavbarEditor.NAVBAR_HOME.equals(view.getTag()) && transitionType == LayoutTransition.APPEARING) {
                mHomeAppearing = true;
                mStartDelay = transition.getStartDelay(transitionType);
                mDuration = transition.getDuration(transitionType);
                mInterpolator = transition.getInterpolator(transitionType);
            }
        }

        @Override
        public void endTransition(LayoutTransition transition, ViewGroup container,
                View view, int transitionType) {
            if (NavbarEditor.NAVBAR_BACK.equals(view.getTag())) {
                mBackTransitioning = false;
            } else if (NavbarEditor.NAVBAR_HOME.equals(view.getTag()) && transitionType == LayoutTransition.APPEARING) {
                mHomeAppearing = false;
            }
        }

        public void onBackAltCleared() {
            // When dismissing ime during unlock, force the back button to run the same appearance
            // animation as home (if we catch this condition early enough).
            if (!mBackTransitioning && getBackButton().getVisibility() == VISIBLE
                    && mHomeAppearing && getHomeButton().getAlpha() == 0) {
                getBackButton().setAlpha(0);
                ValueAnimator a = ObjectAnimator.ofFloat(getBackButton(), "alpha", 0, 1);
                a.setStartDelay(mStartDelay);
                a.setDuration(mDuration);
                a.setInterpolator(mInterpolator);
                a.start();
            }
        }
    }

    private final OnClickListener mImeSwitcherClickListener = new OnClickListener() {
        @Override
        public void onClick(View view) {
            ((InputMethodManager) mContext.getSystemService(Context.INPUT_METHOD_SERVICE))
                    .showInputMethodPicker(true /* showAuxiliarySubtypes */);
        }
    };

    public NavigationBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        final Resources res = getResources();
        mBarSize = res.getDimensionPixelSize(R.dimen.navigation_bar_size);
        mShowMenu = false;
        mTaskSwitchHelper = new NavigationBarViewTaskSwitchHelper(context);

        getIcons(res);

        mBarTransitions = new NavigationBarTransitions(this);

        mNavBarReceiver = new NavBarReceiver();
        getContext().registerReceiver(mNavBarReceiver, new IntentFilter(NAVBAR_EDIT_ACTION));
        mSettingsObserver = new SettingsObserver(new Handler());

        mDoubleTapGesture = new GestureDetector(mContext,
                new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
                if (pm != null) pm.goToSleep(e.getEventTime());
                return true;
            }
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ViewRootImpl root = getViewRootImpl();
        if (root != null) {
            root.setDrawDuringWindowsAnimating(true);
        }
        mSettingsObserver.observe();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mSettingsObserver.unobserve();
    }

	@Override
    public BarTransitions getBarTransitions() {
        return mBarTransitions;
    }

    @Override
    public void setStatusBar(PhoneStatusBar statusbar) {
        super.setStatusBar(statusbar);
        mTaskSwitchHelper.setBar(statusbar);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mUserAutoHideListener != null) {
            mUserAutoHideListener.onTouch(this, event);
        }
        if (!mInEditMode && mTaskSwitchHelper.onTouchEvent(event)) {
            return true;
        }
        if (mDeadZone != null && event.getAction() == MotionEvent.ACTION_OUTSIDE) {
            mDeadZone.poke(event);
        }
        if (Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.DOUBLE_TAP_SLEEP_NAVBAR, 0) == 1)
            mDoubleTapGesture.onTouchEvent(event);

        return super.onTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return !mInEditMode && mTaskSwitchHelper.onInterceptTouchEvent(event);
    }

    public void abortCurrentGesture() {
        ((KeyButtonView)mCurrentView.findViewById(R.id.home)).abortCurrentGesture();
    }

    public View getCurrentView() {
        return mCurrentView;
    }

    public View getRecentsButton() {
        return mCurrentView.findViewWithTag(NavbarEditor.NAVBAR_RECENT);
    }

    public View getMenuButton() {
        return mCurrentView.findViewWithTag(NavbarEditor.NAVBAR_CONDITIONAL_MENU);
    }

    public View getBackButton() {
        return mCurrentView.findViewWithTag(NavbarEditor.NAVBAR_BACK);
    }

    public View getHomeButton() {
        return mCurrentView.findViewById(R.id.home);
    }

    private View getImeSwitchButton() {
        return mCurrentView.findViewById(R.id.ime_switcher);
    }

    private void getIcons(Resources res) {
        mBackIcon = res.getDrawable(R.drawable.ic_sysbar_back);
        mBackLandIcon = res.getDrawable(R.drawable.ic_sysbar_back_land);
        mBackAltIcon = res.getDrawable(R.drawable.ic_sysbar_back_ime);
        mBackAltLandIcon = res.getDrawable(R.drawable.ic_sysbar_back_ime_land);
        mRecentIcon = res.getDrawable(R.drawable.ic_sysbar_recent);
        mRecentLandIcon = mRecentIcon;
        mHomeIcon = res.getDrawable(R.drawable.ic_sysbar_home);
        mHomeLandIcon = mHomeIcon;
        mRecentAltIcon = res.getDrawable(R.drawable.ic_sysbar_recent_clear);
        mRecentAltLandIcon = mRecentAltIcon;
    }

    @Override
    public void onUpdateResources(Resources res) {
        getIcons(getAvailableResources());
        mBarTransitions.updateResources(getAvailableResources());
        for (int i = 0; i < mRotatedViews.length; i++) {
            ViewGroup container = (ViewGroup) mRotatedViews[i];
            if (container != null) {
                updateLightsOutResources(container);
            }
        }
        if (mEditBar != null) {
            mEditBar.updateResources(res);
        }
    }

    private void updateLightsOutResources(ViewGroup container) {
        ViewGroup lightsOut = (ViewGroup) container.findViewById(R.id.lights_out);
        if (lightsOut != null) {
            final int nChildren = lightsOut.getChildCount();
            for (int i = 0; i < nChildren; i++) {
                final View child = lightsOut.getChildAt(i);
                if (child instanceof ImageView) {
                    final ImageView iv = (ImageView) child;
                    // clear out the existing drawable, this is required since the
                    // ImageView keeps track of the resource ID and if it is the same
                    // it will not update the drawable.
                    iv.setImageDrawable(null);
                    iv.setImageDrawable(getAvailableResources().getDrawable(
                            R.drawable.ic_sysbar_lights_out_dot_large));
                }
            }
        }
    }

    @Override
    public void setLayoutDirection(int layoutDirection) {
        getIcons(getAvailableResources());

        super.setLayoutDirection(layoutDirection);
    }

    public void setNavigationIconHints(int hints) {
        setNavigationIconHints(hints, false);
    }

    public void setNavigationIconHints(int hints, boolean force) {
        if (!force && hints == mNavigationIconHints) return;
        final boolean backAlt = (hints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0;
        if ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) != 0 && !backAlt) {
            mTransitionListener.onBackAltCleared();
        }
        if (DEBUG) {
            android.widget.Toast.makeText(getContext(),
                "Navigation icon hints = " + hints,
                500).show();
        }

        mNavigationIconHints = hints;

        ((ImageView)getBackButton()).setImageDrawable(backAlt
                ? (mVertical ? mBackAltLandIcon : mBackAltIcon)
                : (mVertical ? mBackLandIcon : mBackIcon));
	mNavSwitch = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.NAVBAR_RECENTS_SWITCH, 0,
                UserHandle.USER_CURRENT) == 1;
		if(mNavSwitch) {
        ((ImageView)getRecentsButton()).setImageDrawable(
                    (0 != (hints & StatusBarManager.NAVIGATION_HINT_RECENT_ALT))
                            ? (mVertical ? mRecentAltLandIcon : mRecentAltIcon)
                            : (mVertical ? mRecentLandIcon : mRecentIcon));
	} else {
	((ImageView)getRecentsButton()).setImageDrawable(mVertical ? mRecentLandIcon : mRecentIcon);
	}
        ((ImageView)getHomeButton()).setImageDrawable(mVertical ? mHomeLandIcon : mHomeIcon);

        final boolean showImeButton = ((hints & StatusBarManager.NAVIGATION_HINT_IME_SHOWN) != 0);
        getImeSwitchButton().setVisibility(showImeButton ? View.INVISIBLE : View.INVISIBLE);

        setDisabledFlags(mDisabledFlags, true);
        // Update menu button in case the IME state has changed.
        setMenuVisibility(mShowMenu, true);
    }

    public int getNavigationIconHints() {
        return mNavigationIconHints;
    }

    public void setDisabledFlags(int disabledFlags) {
        setDisabledFlags(disabledFlags, false);
    }

    public void setDisabledFlags(int disabledFlags, boolean force) {
		super.setDisabledFlags(disabledFlags, force);

        final boolean disableHome = ((disabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0);
        boolean disableRecent = ((disabledFlags & View.STATUS_BAR_DISABLE_RECENT) != 0);
        final boolean disableBack = ((disabledFlags & View.STATUS_BAR_DISABLE_BACK) != 0)
                && ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) == 0);
        final boolean disableSearch = ((disabledFlags & View.STATUS_BAR_DISABLE_SEARCH) != 0);

        ViewGroup navButtons = (ViewGroup) mCurrentView.findViewById(R.id.nav_buttons);
        if (navButtons != null) {
            LayoutTransition lt = navButtons.getLayoutTransition();
            if (lt != null) {
                if (!lt.getTransitionListeners().contains(mTransitionListener)) {
                    lt.addTransitionListener(mTransitionListener);
                }
            }
        }
        if (inLockTask() && disableRecent && !disableHome) {
            // Don't hide recents when in lock task, it is used for exiting.
            // Unless home is hidden, then in DPM locked mode and no exit available.
            disableRecent = false;
        }

        setButtonWithTagVisibility(NavbarEditor.NAVBAR_BACK, !disableBack);
        setButtonWithTagVisibility(NavbarEditor.NAVBAR_HOME, !disableHome);
        setButtonWithTagVisibility(NavbarEditor.NAVBAR_RECENT, !disableRecent);
        setButtonWithTagVisibility(NavbarEditor.NAVBAR_POWER, !disableRecent);
        setButtonWithTagVisibility(NavbarEditor.NAVBAR_NOTIFICATIONS, !disableRecent);
        setButtonWithTagVisibility(NavbarEditor.NAVBAR_SEARCH, !disableSearch);
    }

    private boolean inLockTask() {
        try {
            return ActivityManagerNative.getDefault().isInLockTaskMode();
        } catch (RemoteException e) {
            return false;
        }
    }

    public void setMenuVisibility(final boolean show) {
        setMenuVisibility(show, false);
    }

    public void setMenuVisibility(final boolean show, final boolean force) {
        if (!force && mShowMenu == show) return;

        mShowMenu = show;

        // Only show Menu if IME switcher not shown.
        final boolean shouldShow = mShowMenu &&
                ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_IME_SHOWN) == 0);
        final boolean shouldShowAlwaysMenu = (mNavigationIconHints &
                StatusBarManager.NAVIGATION_HINT_IME_SHOWN) == 0;
        setButtonWithTagVisibility(NavbarEditor.NAVBAR_ALWAYS_MENU, shouldShowAlwaysMenu);
        setButtonWithTagVisibility(NavbarEditor.NAVBAR_CONDITIONAL_MENU, shouldShow);
        setButtonWithTagVisibility(NavbarEditor.NAVBAR_SEARCH, shouldShowAlwaysMenu);
        setButtonWithTagVisibility(NavbarEditor.NAVBAR_POWER, shouldShowAlwaysMenu);
    }

    @Override
    public void onFinishInflate() {
		super.onFinishInflate();

        getImeSwitchButton().setOnClickListener(mImeSwitcherClickListener);

        updateRTLOrder();
    }

    public void setLeftInLandscape(boolean leftInLandscape) {
        super.setLeftInLandscape(leftInLandscape);
        mDeadZone.setStartFromRight(leftInLandscape);
    }

    public void reorient() {
		super.reorient();

        updateLayoutTransitionsEnabled();

        if (NavbarEditor.isDevicePhone(getContext())) {
            int rotation = mDisplay.getRotation();
            mVertical = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        } else {
            mVertical = getWidth() > 0 && getHeight() > getWidth();
        }
        mEditBar = new NavbarEditor(mCurrentView, mVertical, mIsLayoutRtl, getResources());
        updateSettings();

        getImeSwitchButton().setOnClickListener(mImeSwitcherClickListener);

        mDeadZone = (DeadZone) mCurrentView.findViewById(R.id.deadzone);
        mDeadZone.setStartFromRight(mLeftInLandscape);

        // force the low profile & disabled states into compliance
        mBarTransitions.init();
        setDisabledFlags(mDisabledFlags, true /* force */);
        setMenuVisibility(mShowMenu, true /* force */);

        if (DEBUG) {
            Log.d(TAG, "reorient(): rot=" + mDisplay.getRotation());
        }

        updateTaskSwitchHelper();

        setNavigationIconHints(mNavigationIconHints, true);
    }

    private void updateTaskSwitchHelper() {
        boolean isRtl = (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
        mTaskSwitchHelper.setBarState(mVertical, isRtl);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateRTLOrder();
        updateTaskSwitchHelper();
    }

    /**
     * In landscape, the LinearLayout is not auto mirrored since it is vertical. Therefore we
     * have to do it manually
     */
    private void updateRTLOrder() {
        boolean isLayoutRtl = getResources().getConfiguration()
                .getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        if (mIsLayoutRtl != isLayoutRtl) {
            mIsLayoutRtl = isLayoutRtl;
            reorient();
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NavigationBarView {");
        final Rect r = new Rect();
        final Point size = new Point();
        mDisplay.getRealSize(size);

        pw.println(String.format("      this: " + PhoneStatusBar.viewInfo(this)
                        + " " + visibilityToString(getVisibility())));

        getWindowVisibleDisplayFrame(r);
        final boolean offscreen = r.right > size.x || r.bottom > size.y;
        pw.println("      window: "
                + r.toShortString()
                + " " + visibilityToString(getWindowVisibility())
                + (offscreen ? " OFFSCREEN!" : ""));

        pw.println(String.format("      mCurrentView: id=%s (%dx%d) %s",
                        getResourceName(mCurrentView.getId()),
                        mCurrentView.getWidth(), mCurrentView.getHeight(),
                        visibilityToString(mCurrentView.getVisibility())));

        pw.println(String.format("      disabled=0x%08x vertical=%s menu=%s",
                        mDisabledFlags,
                        mVertical ? "true" : "false",
                        mShowMenu ? "true" : "false"));

        dumpButton(pw, "back", getBackButton());
        dumpButton(pw, "home", getHomeButton());
        dumpButton(pw, "rcnt", getRecentsButton());
        dumpButton(pw, "menu", getMenuButton());

        pw.println("    }");
    }

    private static void dumpButton(PrintWriter pw, String caption, View button) {
        pw.print("      " + caption + ": ");
        if (button == null) {
            pw.print("null");
        } else {
            pw.print(PhoneStatusBar.viewInfo(button)
                            + " " + visibilityToString(button.getVisibility())
                            + " alpha=" + button.getAlpha()
            );
        }
        pw.println();
    }

    void setListeners(OnClickListener recentsClickListener, OnTouchListener recentsPreloadListener,
                      OnLongClickListener recentsBackListener, OnTouchListener homeSearchActionListener,
                      OnLongClickListener longPressHomeListener, OnClickListener notificationsClickListener,
                      OnLongClickListener notificationsLongListener) {
        mRecentsClickListener = recentsClickListener;
        mRecentsPreloadListener = recentsPreloadListener;
        mHomeSearchActionListener = homeSearchActionListener;
        mRecentsBackListener = recentsBackListener;
        mLongPressHomeListener = longPressHomeListener;
        mNotificationsClickListener = notificationsClickListener;
        mNotificationsLongListener = notificationsLongListener;
        updateButtonListeners();
    }

    private void removeButtonListeners() {
        ViewGroup container = (ViewGroup) mCurrentView.findViewById(R.id.container);
        int viewCount = container.getChildCount();
        for (int i = 0; i < viewCount; i++) {
            View button = container.getChildAt(i);
            if (button instanceof KeyButtonView) {
                button.setOnClickListener(null);
                button.setOnTouchListener(null);
                button.setLongClickable(false);
                button.setOnLongClickListener(null);
            }
        }
    }

    protected void updateButtonListeners() {
        View recentView = mCurrentView.findViewWithTag(NavbarEditor.NAVBAR_RECENT);
        if (recentView != null) {
            recentView.setOnClickListener(mRecentsClickListener);
            recentView.setOnTouchListener(mRecentsPreloadListener);
            recentView.setLongClickable(true);
            recentView.setOnLongClickListener(mRecentsBackListener);
        }
        View backView = mCurrentView.findViewWithTag(NavbarEditor.NAVBAR_BACK);
        if (backView != null) {
            backView.setLongClickable(true);
            backView.setOnLongClickListener(mRecentsBackListener);
        }
        View homeView = mCurrentView.findViewWithTag(NavbarEditor.NAVBAR_HOME);
        if (homeView != null) {
            homeView.setOnTouchListener(mHomeSearchActionListener);
            homeView.setLongClickable(true);
            homeView.setOnLongClickListener(mLongPressHomeListener);
        }
        View powerView = mCurrentView.findViewWithTag(NavbarEditor.NAVBAR_POWER);
        if (powerView != null) {
            powerView.setLongClickable(true);
            powerView.setOnLongClickListener(mPowerListener);
        }
        View notificationsView = mCurrentView.findViewWithTag(NavbarEditor.NAVBAR_NOTIFICATIONS);
        if (notificationsView != null) {
            notificationsView.setOnClickListener(mNotificationsClickListener);
            notificationsView.setLongClickable(true);
            notificationsView.setOnLongClickListener(mNotificationsLongListener);
        }
    }

    public boolean isInEditMode() {
        return mInEditMode;
    }

    private void setButtonWithTagVisibility(Object tag, boolean visible) {
        View findView = mCurrentView.findViewWithTag(tag);
        if (findView != null) {
			findView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }
    }

    @Override
    public Resources getResources() {
        return mThemedResources != null ? mThemedResources : getContext().getResources();
    }

    public class NavBarReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean edit = intent.getBooleanExtra("edit", false);
            boolean save = intent.getBooleanExtra("save", false);
            if (edit != mInEditMode) {
                mInEditMode = edit;
                if (edit) {
                    removeButtonListeners();
                    mEditBar.setEditMode(true);
                } else {
                    if (save) {
                        mEditBar.saveKeys();
                    }
                    mEditBar.setEditMode(false);
                    updateSettings();
                }
            }
        }
    }

    public void updateSettings() {
        mEditBar.updateKeys();
        removeButtonListeners();
        updateButtonListeners();
        setDisabledFlags(mDisabledFlags, true /* force */);
        setMenuVisibility(mShowMenu, true);
    }

    private class SettingsObserver extends UserContentObserver {

        SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void observe() {
            super.observe();
            ContentResolver resolver = getContext().getContentResolver();
            // intialize mModlockDisabled
            onChange(false);
        }

        @Override
        public void unobserve() {
            super.unobserve();
            getContext().getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
        }
    }
	
    @Override
    protected void onDispose() {
        if (mSettingsObserver != null) {
            mSettingsObserver.unobserve();
        }
    }

    boolean isBarPulseFaded() {
        if (mPulse == null) {
            return false;
        } else {
            return mPulse.shouldDrawPulse();
        }
    }

    @Override
    public boolean onStartPulse(Animation animatePulseIn) {
        final View currentNavButtons = getCurrentView().findViewById(R.id.nav_buttons);
        final View hiddenNavButtons = getHiddenView().findViewById(R.id.nav_buttons);

        // no need to animate the GONE view, but keep alpha inline since onStartPulse
        // is a oneshot call
        hiddenNavButtons.setAlpha(PULSE_ALPHA_FADE);
        currentNavButtons.animate()
                .alpha(PULSE_ALPHA_FADE)
                .setDuration(PULSE_FADE_OUT_DURATION)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator _a) {
                        // shouldn't be null, mPulse just called into us
                        if (mPulse != null) {
                            mPulse.turnOnPulse();
                        }
                    }
                })
                .start();
        return true;
    }

    @Override
    public void onStopPulse(Animation animatePulseOut) {
        final View currentNavButtons = getCurrentView().findViewById(R.id.nav_buttons);
        final View hiddenNavButtons = getHiddenView().findViewById(R.id.nav_buttons);

        hiddenNavButtons.setAlpha(1.0f);
        currentNavButtons.animate()
                .alpha(1.0f)
                .setDuration(PULSE_FADE_IN_DURATION)
                .start();
    }
}
