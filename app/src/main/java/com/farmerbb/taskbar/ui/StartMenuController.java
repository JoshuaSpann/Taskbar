/* Copyright 2016 Braden Farmer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.farmerbb.taskbar.ui;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.widget.SearchView;
import android.util.Patterns;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.URLUtil;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.TextView;

import com.farmerbb.taskbar.R;
import com.farmerbb.taskbar.activity.HomeActivity;
import com.farmerbb.taskbar.activity.HomeActivityDelegate;
import com.farmerbb.taskbar.activity.InvisibleActivity;
import com.farmerbb.taskbar.activity.InvisibleActivityAlt;
import com.farmerbb.taskbar.adapter.StartMenuAdapter;
import com.farmerbb.taskbar.util.AppEntry;
import com.farmerbb.taskbar.util.Blacklist;
import com.farmerbb.taskbar.util.FreeformHackHelper;
import com.farmerbb.taskbar.util.IconCache;
import com.farmerbb.taskbar.util.LauncherHelper;
import com.farmerbb.taskbar.util.MenuHelper;
import com.farmerbb.taskbar.util.TopApps;
import com.farmerbb.taskbar.util.U;
import com.farmerbb.taskbar.widget.StartMenuLayout;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class StartMenuController implements Controller {

    private Context context;
    private StartMenuLayout layout;
    private GridView startMenu;
    private SearchView searchView;
    private TextView textView;
    private PackageManager pm;
    private StartMenuAdapter adapter;

    private Handler handler;
    private Thread thread;

    private boolean shouldShowSearchBox = false;
    private boolean hasSubmittedQuery = false;
    private boolean hasHardwareKeyboard = false;

    private int layoutId = R.layout.start_menu_left;

    private List<String> currentStartMenuIds = new ArrayList<>();

    private View.OnClickListener ocl = view -> toggleStartMenu();

    private BroadcastReceiver toggleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            toggleStartMenu();
        }
    };

    private BroadcastReceiver showSpaceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            layout.findViewById(R.id.start_menu_space).setVisibility(View.VISIBLE);
        }
    };

    private BroadcastReceiver hideSpaceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            layout.findViewById(R.id.start_menu_space).setVisibility(View.GONE);
        }
    };

    private BroadcastReceiver hideReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            hideStartMenu(true);
        }
    };

    private BroadcastReceiver hideReceiverNoReset = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            hideStartMenu(false);
        }
    };

    private BroadcastReceiver resetReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            startMenu.setSelection(0);
        }
    };

    private Comparator<LauncherActivityInfo> comparator = (ai1, ai2) -> {
        String label1;
        String label2;

        try {
            label1 = ai1.getLabel().toString();
            label2 = ai2.getLabel().toString();
        } catch (OutOfMemoryError e) {
            System.gc();

            label1 = ai1.getApplicationInfo().packageName;
            label2 = ai2.getApplicationInfo().packageName;
        }

        return Collator.getInstance().compare(label1, label2);
    };

    public StartMenuController(Context context) {
        this.context = context;
    }

    @TargetApi(Build.VERSION_CODES.M)
    @Override
    public void onCreateHost(Host host) {
        hasHardwareKeyboard = context.getResources().getConfiguration().keyboard != Configuration.KEYBOARD_NOKEYS;

        SharedPreferences pref = U.getSharedPreferences(context);
        if(pref.getBoolean("taskbar_active", false) || LauncherHelper.getInstance().isOnHomeScreen()) {
            if(U.canDrawOverlays(context, host instanceof HomeActivityDelegate))
                drawStartMenu(host);
            else {
                pref.edit().putBoolean("taskbar_active", false).apply();

                host.terminate();
            }
        } else host.terminate();
    }

    @SuppressLint("RtlHardcoded")
    private void drawStartMenu(Host host) {
        IconCache.getInstance(context).clearCache();

        final SharedPreferences pref = U.getSharedPreferences(context);
        switch(pref.getString("show_search_bar", "keyboard")) {
            case "always":
                shouldShowSearchBox = true;
                break;
            case "keyboard":
                shouldShowSearchBox = hasHardwareKeyboard;
                break;
            case "never":
                shouldShowSearchBox = false;
                break;
        }

        // Initialize layout params
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        U.setCachedRotation(windowManager.getDefaultDisplay().getRotation());

        final ViewParams params = new ViewParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                -1,
                shouldShowSearchBox ? 0 : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        );

        // Determine where to show the start menu on screen
        switch(U.getTaskbarPosition(context)) {
            case "bottom_left":
                layoutId = R.layout.start_menu_left;
                params.gravity = Gravity.BOTTOM | Gravity.LEFT;
                break;
            case "bottom_vertical_left":
                layoutId = R.layout.start_menu_vertical_left;
                params.gravity = Gravity.BOTTOM | Gravity.LEFT;
                break;
            case "bottom_right":
                layoutId = R.layout.start_menu_right;
                params.gravity = Gravity.BOTTOM | Gravity.RIGHT;
                break;
            case "bottom_vertical_right":
                layoutId = R.layout.start_menu_vertical_right;
                params.gravity = Gravity.BOTTOM | Gravity.RIGHT;
                break;
            case "top_left":
                layoutId = R.layout.start_menu_top_left;
                params.gravity = Gravity.TOP | Gravity.LEFT;
                break;
            case "top_vertical_left":
                layoutId = R.layout.start_menu_vertical_left;
                params.gravity = Gravity.TOP | Gravity.LEFT;
                break;
            case "top_right":
                layoutId = R.layout.start_menu_top_right;
                params.gravity = Gravity.TOP | Gravity.RIGHT;
                break;
            case "top_vertical_right":
                layoutId = R.layout.start_menu_vertical_right;
                params.gravity = Gravity.TOP | Gravity.RIGHT;
                break;
        }

        // Initialize views
        layout = (StartMenuLayout) LayoutInflater.from(U.wrapContext(context)).inflate(layoutId, null);
        layout.setAlpha(0);

        startMenu = layout.findViewById(R.id.start_menu);

        if((shouldShowSearchBox && !hasHardwareKeyboard) || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1)
            layout.viewHandlesBackButton();

        boolean scrollbar = pref.getBoolean("scrollbar", false);
        startMenu.setFastScrollEnabled(scrollbar);
        startMenu.setFastScrollAlwaysVisible(scrollbar);
        startMenu.setScrollBarStyle(scrollbar ? View.SCROLLBARS_OUTSIDE_INSET : View.SCROLLBARS_INSIDE_OVERLAY);

        if(pref.getBoolean("transparent_start_menu", false))
            startMenu.setBackgroundColor(0);

        if(U.visualFeedbackEnabled(context))
            startMenu.setRecyclerListener(view -> view.setBackgroundColor(0));

        searchView = layout.findViewById(R.id.search);

        int backgroundTint = U.getBackgroundTint(context);

        FrameLayout startMenuFrame = layout.findViewById(R.id.start_menu_frame);
        FrameLayout searchViewLayout = layout.findViewById(R.id.search_view_layout);
        startMenuFrame.setBackgroundColor(backgroundTint);
        searchViewLayout.setBackgroundColor(backgroundTint);

        if(shouldShowSearchBox) {
            if(!hasHardwareKeyboard) searchView.setIconifiedByDefault(true);

            searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    if(!hasSubmittedQuery) {
                        ListAdapter adapter = startMenu.getAdapter();
                        if(adapter != null) {
                            hasSubmittedQuery = true;

                            if(adapter.getCount() > 0) {
                                View view = adapter.getView(0, null, startMenu);
                                LinearLayout layout = view.findViewById(R.id.entry);
                                layout.performClick();
                            } else {
                                if(U.shouldCollapse(context, true))
                                    LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent("com.farmerbb.taskbar.HIDE_TASKBAR"));
                                else
                                    LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent("com.farmerbb.taskbar.HIDE_START_MENU"));

                                Intent intent;

                                if(Patterns.WEB_URL.matcher(query).matches()) {
                                    intent = new Intent(Intent.ACTION_VIEW);
                                    intent.setData(Uri.parse(URLUtil.guessUrl(query)));
                                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                } else {
                                    intent = new Intent(Intent.ACTION_WEB_SEARCH);
                                    intent.putExtra(SearchManager.QUERY, query);
                                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                }

                                if(intent.resolveActivity(context.getPackageManager()) != null)
                                    context.startActivity(intent);
                                else {
                                    Uri uri = new Uri.Builder()
                                            .scheme("https")
                                            .authority("www.google.com")
                                            .path("search")
                                            .appendQueryParameter("q", query)
                                            .build();

                                    intent = new Intent(Intent.ACTION_VIEW);
                                    intent.setData(uri);
                                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                                    try {
                                        context.startActivity(intent);
                                    } catch (ActivityNotFoundException e) { /* Gracefully fail */ }
                                }
                            }
                        }
                    }
                    return true;
                }

                @Override
                public boolean onQueryTextChange(String newText) {
                    searchView.setIconified(false);

                    View closeButton = searchView.findViewById(R.id.search_close_btn);
                    if(closeButton != null) closeButton.setVisibility(View.GONE);

                    refreshApps(newText, false);

                    if(Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
                        new Handler().postDelayed(() -> {
                            EditText editText = searchView.findViewById(R.id.search_src_text);
                            if(editText != null) {
                                editText.requestFocus();
                                editText.setSelection(editText.getText().length());
                            }
                        }, 50);
                    }

                    return true;
                }
            });

            searchView.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);

            LinearLayout powerButton = layout.findViewById(R.id.power_button);
            powerButton.setOnClickListener(view -> {
                int[] location = new int[2];
                view.getLocationOnScreen(location);
                openContextMenu(location);
            });

            powerButton.setOnGenericMotionListener((view, motionEvent) -> {
                if(motionEvent.getAction() == MotionEvent.ACTION_BUTTON_PRESS
                        && motionEvent.getButtonState() == MotionEvent.BUTTON_SECONDARY) {
                    int[] location = new int[2];
                    view.getLocationOnScreen(location);
                    openContextMenu(location);
                }
                return false;
            });

            searchViewLayout.setOnClickListener(view -> searchView.setIconified(false));

            startMenu.setOnItemClickListener((viewParent, view, position, id) -> {
                hideStartMenu(true);

                AppEntry entry = (AppEntry) viewParent.getAdapter().getItem(position);
                U.launchApp(context, entry.getPackageName(), entry.getComponentName(), entry.getUserId(context), null, false, false);
            });

            if(pref.getBoolean("transparent_start_menu", false))
                layout.findViewById(R.id.search_view_child_layout).setBackgroundColor(0);
        } else
            searchViewLayout.setVisibility(View.GONE);

        textView = layout.findViewById(R.id.no_apps_found);

        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(context);

        lbm.unregisterReceiver(toggleReceiver);
        lbm.unregisterReceiver(hideReceiver);
        lbm.unregisterReceiver(hideReceiverNoReset);
        lbm.unregisterReceiver(showSpaceReceiver);
        lbm.unregisterReceiver(hideSpaceReceiver);
        lbm.unregisterReceiver(resetReceiver);

        lbm.registerReceiver(toggleReceiver, new IntentFilter("com.farmerbb.taskbar.TOGGLE_START_MENU"));
        lbm.registerReceiver(hideReceiver, new IntentFilter("com.farmerbb.taskbar.HIDE_START_MENU"));
        lbm.registerReceiver(hideReceiverNoReset, new IntentFilter("com.farmerbb.taskbar.HIDE_START_MENU_NO_RESET"));
        lbm.registerReceiver(showSpaceReceiver, new IntentFilter("com.farmerbb.taskbar.SHOW_START_MENU_SPACE"));
        lbm.registerReceiver(hideSpaceReceiver, new IntentFilter("com.farmerbb.taskbar.HIDE_START_MENU_SPACE"));
        lbm.registerReceiver(resetReceiver, new IntentFilter("com.farmerbb.taskbar.RESET_START_MENU"));

        handler = new Handler();
        refreshApps(true);

        host.addView(layout, params);
    }

    private void refreshApps(boolean firstDraw) {
        refreshApps(null, firstDraw);
    }

    private void refreshApps(final String query, final boolean firstDraw) {
        if(thread != null) thread.interrupt();

        handler = new Handler();
        thread = new Thread(() -> {
            if(pm == null) pm = context.getPackageManager();

            UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
            LauncherApps launcherApps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);

            final List<UserHandle> userHandles = userManager.getUserProfiles();
            final List<LauncherActivityInfo> unfilteredList = new ArrayList<>();

            for(UserHandle handle : userHandles) {
                unfilteredList.addAll(launcherApps.getActivityList(null, handle));
            }

            final List<LauncherActivityInfo> topAppsList = new ArrayList<>();
            final List<LauncherActivityInfo> allAppsList = new ArrayList<>();
            final List<LauncherActivityInfo> list = new ArrayList<>();

            TopApps topApps = TopApps.getInstance(context);
            for(LauncherActivityInfo appInfo : unfilteredList) {
                if(topApps.isTopApp(appInfo.getComponentName().flattenToString())
                        || topApps.isTopApp(appInfo.getName()))
                    topAppsList.add(appInfo);
            }

            Blacklist blacklist = Blacklist.getInstance(context);
            for(LauncherActivityInfo appInfo : unfilteredList) {
                if(!(blacklist.isBlocked(appInfo.getComponentName().flattenToString())
                        || blacklist.isBlocked(appInfo.getName()))
                        && !(topApps.isTopApp(appInfo.getComponentName().flattenToString())
                        || topApps.isTopApp(appInfo.getName())))
                    allAppsList.add(appInfo);
            }

            Collections.sort(topAppsList, comparator);
            Collections.sort(allAppsList, comparator);

            list.addAll(topAppsList);
            list.addAll(allAppsList);

            topAppsList.clear();
            allAppsList.clear();

            List<LauncherActivityInfo> queryList;
            if(query == null)
                queryList = list;
            else {
                queryList = new ArrayList<>();
                for(LauncherActivityInfo appInfo : list) {
                    if(appInfo.getLabel().toString().toLowerCase().contains(query.toLowerCase()))
                        queryList.add(appInfo);
                }
            }

            // Now that we've generated the list of apps,
            // we need to determine if we need to redraw the start menu or not
            boolean shouldRedrawStartMenu = false;
            List<String> finalApplicationIds = new ArrayList<>();

            if(query == null && !firstDraw) {
                for(LauncherActivityInfo appInfo : queryList) {
                    finalApplicationIds.add(appInfo.getApplicationInfo().packageName);
                }

                if(finalApplicationIds.size() != currentStartMenuIds.size())
                    shouldRedrawStartMenu = true;
                else {
                    for(int i = 0; i < finalApplicationIds.size(); i++) {
                        if(!finalApplicationIds.get(i).equals(currentStartMenuIds.get(i))) {
                            shouldRedrawStartMenu = true;
                            break;
                        }
                    }
                }
            } else shouldRedrawStartMenu = true;

            if(shouldRedrawStartMenu) {
                if(query == null) currentStartMenuIds = finalApplicationIds;

                Drawable defaultIcon = pm.getDefaultActivityIcon();

                final List<AppEntry> entries = new ArrayList<>();
                for(LauncherActivityInfo appInfo : queryList) {

                    // Attempt to work around frequently reported OutOfMemoryErrors
                    String label;
                    Drawable icon;

                    try {
                        label = appInfo.getLabel().toString();
                        icon = IconCache.getInstance(context).getIcon(context, pm, appInfo);
                    } catch (OutOfMemoryError e) {
                        System.gc();

                        label = appInfo.getApplicationInfo().packageName;
                        icon = defaultIcon;
                    }

                    AppEntry newEntry = new AppEntry(
                            appInfo.getApplicationInfo().packageName,
                            new ComponentName(
                                    appInfo.getApplicationInfo().packageName,
                                    appInfo.getName()).flattenToString(),
                            label,
                            icon,
                            false);

                    newEntry.setUserId(userManager.getSerialNumberForUser(appInfo.getUser()));
                    entries.add(newEntry);
                }

                handler.post(() -> {
                    String queryText = searchView.getQuery().toString();
                    if(query == null && queryText.length() == 0
                            || query != null && query.equals(queryText)) {

                        if(firstDraw) {
                            SharedPreferences pref = U.getSharedPreferences(context);
                            if(pref.getString("start_menu_layout", "list").equals("grid")) {
                                startMenu.setNumColumns(3);
                                adapter = new StartMenuAdapter(context, R.layout.row_alt, entries);
                            } else
                                adapter = new StartMenuAdapter(context, R.layout.row, entries);

                            startMenu.setAdapter(adapter);
                        }

                        int position = startMenu.getFirstVisiblePosition();

                        if(!firstDraw && adapter != null)
                            adapter.updateList(entries);

                        startMenu.setSelection(position);

                        if(adapter != null && adapter.getCount() > 0)
                            textView.setText(null);
                        else if(query != null)
                            textView.setText(context.getString(Patterns.WEB_URL.matcher(query).matches() ? R.string.press_enter_alt : R.string.press_enter));
                        else
                            textView.setText(context.getString(R.string.nothing_to_see_here));
                    }
                });
            }
        });

        thread.start();
    }

    private void toggleStartMenu() {
        if(layout.getVisibility() == View.GONE)
            showStartMenu();
        else
            hideStartMenu(true);
    }

    @SuppressWarnings("deprecation")
    @TargetApi(Build.VERSION_CODES.N)
    private void showStartMenu() {
        if(layout.getVisibility() == View.GONE) {
            layout.setOnClickListener(ocl);
            layout.setVisibility(View.VISIBLE);

            if(Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1 && !hasHardwareKeyboard)
                layout.setAlpha(1);

            MenuHelper.getInstance().setStartMenuOpen(true);

            LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent("com.farmerbb.taskbar.START_MENU_APPEARING"));

            boolean onHomeScreen = LauncherHelper.getInstance().isOnHomeScreen();
            boolean inFreeformMode = FreeformHackHelper.getInstance().isInFreeformWorkspace();

            if(!U.isChromeOs(context) && (!onHomeScreen || inFreeformMode)) {
                Class clazz = inFreeformMode && !U.hasBrokenSetLaunchBoundsApi()
                        ? InvisibleActivityAlt.class
                        : InvisibleActivity.class;

                Intent intent = new Intent(context, clazz);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);

                if(inFreeformMode) {
                    if(clazz.equals(InvisibleActivity.class))
                        U.startActivityLowerRight(context, intent);
                    else if(clazz.equals(InvisibleActivityAlt.class))
                        U.startActivityMaximized(context, intent);
                } else
                    context.startActivity(intent);
            }

            EditText editText = searchView.findViewById(R.id.search_src_text);
            if(searchView.getVisibility() == View.VISIBLE) {
                if(hasHardwareKeyboard) {
                    searchView.setIconifiedByDefault(true);

                    if(editText != null)
                        editText.setShowSoftInputOnFocus(false);
                } else
                    searchView.requestFocus();
            }

            refreshApps(false);

            new Handler().postDelayed(() -> {
                if(Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1 || hasHardwareKeyboard)
                    layout.setAlpha(1);

                if(hasHardwareKeyboard) {
                    searchView.setIconifiedByDefault(false);
                    if(editText != null)
                        editText.setShowSoftInputOnFocus(true);

                    searchView.requestFocus();
                }

                searchView.setOnQueryTextFocusChangeListener((view, b) -> {
                    if(!hasHardwareKeyboard) {
                        ViewGroup.LayoutParams params1 = startMenu.getLayoutParams();
                        params1.height = context.getResources().getDimensionPixelSize(
                                b && !isSecondScreenDisablingKeyboard()
                                        ? R.dimen.start_menu_height_half
                                        : R.dimen.start_menu_height);
                        startMenu.setLayoutParams(params1);
                    }

                    if(!b) {
                        if(hasHardwareKeyboard && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1)
                            LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent("com.farmerbb.taskbar.HIDE_START_MENU"));
                        else {
                            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
                        }
                    }
                });

                InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(layout.getWindowToken(), 0);
            }, 100);
        }
    }

    private void hideStartMenu(boolean shouldReset) {
        if(layout.getVisibility() == View.VISIBLE) {
            layout.setOnClickListener(null);
            layout.setAlpha(0);

            MenuHelper.getInstance().setStartMenuOpen(false);

            LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent("com.farmerbb.taskbar.START_MENU_DISAPPEARING"));

            layout.postDelayed(() -> {
                layout.setVisibility(View.GONE);

                if(!hasHardwareKeyboard)
                    searchView.setQuery(null, false);

                searchView.setIconified(true);
                searchView.setOnQueryTextFocusChangeListener(null);
                hasSubmittedQuery = false;

                if(shouldReset) {
                    startMenu.smoothScrollBy(0, 0);
                    startMenu.setSelection(0);
                }

                InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(layout.getWindowToken(), 0);
            }, 100);
        }
    }

    @Override
    public void onDestroyHost(Host host) {
        if(layout != null)
            try {
                host.removeView(layout);
            } catch (IllegalArgumentException e) { /* Gracefully fail */ }

        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(context);

        lbm.unregisterReceiver(toggleReceiver);
        lbm.unregisterReceiver(hideReceiver);
        lbm.unregisterReceiver(hideReceiverNoReset);
        lbm.unregisterReceiver(showSpaceReceiver);
        lbm.unregisterReceiver(hideSpaceReceiver);
        lbm.unregisterReceiver(resetReceiver);

        lbm.sendBroadcast(new Intent("com.farmerbb.taskbar.START_MENU_DISAPPEARING"));
    }

    @TargetApi(Build.VERSION_CODES.M)
    @Override
    public void onRecreateHost(Host host) {
        if(layout != null) {
            try {
                host.removeView(layout);
            } catch (IllegalArgumentException e) { /* Gracefully fail */ }

            if(U.canDrawOverlays(context, host instanceof HomeActivityDelegate))
                drawStartMenu(host);
            else {
                SharedPreferences pref = U.getSharedPreferences(context);
                pref.edit().putBoolean("taskbar_active", false).apply();

                host.terminate();
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void openContextMenu(final int[] location) {
        LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent("com.farmerbb.taskbar.HIDE_START_MENU_NO_RESET"));

        Bundle args = new Bundle();
        args.putBoolean("launched_from_start_menu", true);
        args.putBoolean("is_overflow_menu", true);
        args.putInt("x", location[0]);
        args.putInt("y", location[1]);

        new Handler().postDelayed(() -> U.startContextMenuActivity(context, args), shouldDelay() ? 100 : 0);
    }

    private boolean shouldDelay() {
        SharedPreferences pref = U.getSharedPreferences(context);
        return U.hasFreeformSupport(context)
                && pref.getBoolean("freeform_hack", false)
                && !FreeformHackHelper.getInstance().isFreeformHackActive();
    }

    private boolean isSecondScreenDisablingKeyboard() {
        return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD)
                .startsWith("com.farmerbb.secondscreen");
    }
}
