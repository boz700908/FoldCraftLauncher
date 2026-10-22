/*
 * FCL
 * Copyright (C) 2024 Tungsten
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.tungsten.fcl.control.keyboard;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import com.tungsten.fcl.control.FCLInput;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * 实体键盘的全局按键接管服务。
 * <p>
 * 为什么必须走无障碍服务而不是 Activity 的 {@code dispatchKeyEvent}：
 * <ul>
 *   <li>Activity 只能收到「没有被 View 树消费掉」的按键。输入法窗口、弹出对话框、
 *       侧边抽屉里的列表抢占焦点时，按键根本到不了 Activity，实体键盘会时灵时不灵</li>
 *   <li>部分按键（HOME/META/SEARCH 等）在 WindowManager 层就被系统截走</li>
 *   <li>按键过滤发生在输入法之前，能保证「键盘事件优先给游戏」这一确定顺序</li>
 * </ul>
 * 本服务只做按键转发，不读窗口内容、不监听界面变化，返回 false 时事件会照常
 * 向下派发，因此即使门控判断出现偏差，行为也只是退回到旧的 dispatchKeyEvent 路径。
 */
public class FCLKeyAccessibilityService extends AccessibilityService {

    private static WeakReference<FCLKeyAccessibilityService> sInstance;

    /**
     * 服务可用状态的短时缓存。
     * <p>
     * 状态查询要读 {@link Settings.Secure} 并遍历无障碍服务列表，不适合每次按键都做；
     * 但也不能像原来那样只在开关切换时判断一次——用户可能随时去系统设置里授权或撤销授权，
     * 只在切换开关时判断会让界面上的状态与真实状态长期不一致。这里用短 TTL 兜住高频查询，
     * 同时由系统状态变化监听器在授权变化时立刻让缓存失效
     */
    private static final long STATE_CACHE_TTL_MS = 1000L;
    private static volatile Boolean sCachedEnabled;
    private static volatile long sCachedAt;

    /** 状态变化监听器集合，界面据此刷新开关的可用态提示 */
    private static final java.util.Set<Runnable> sStateListeners =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private static AccessibilityManager sManager;
    private static Object sStateChangeListener;
    private static Object sSettingsObserver;

    /**
     * 取当前已连接的服务实例，未连接时返回 null。
     */
    public static FCLKeyAccessibilityService getInstance() {
        return sInstance == null ? null : sInstance.get();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = new WeakReference<>(this);

        // 请求按键过滤权限。flags 必须在运行时设置一次才生效，
        // 只靠 XML 里的 accessibilityFlags 在部分 ROM（尤其是国产 ROM）上不生效
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            info = new AccessibilityServiceInfo();
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.notificationTimeout = 0;
        }
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);
        notifyStateChanged();
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        // 返回 true = 事件已被消费，系统不再派发；false = 继续正常派发，
        // 也就是回落到 FCLInput/JVMActivity 原有的 dispatchKeyEvent 链路
        if (event == null) {
            return false;
        }
        try {
            FCLInput input = FCLInput.getInstance();
            if (input == null || !input.canHandleAccessibilityKey(event)) {
                return false;
            }
            return input.handleKeyEvent(event);
        } catch (Throwable t) {
            // 服务抛异常会被系统直接判定为崩溃并停用服务，这里必须兜住，
            // 最坏情况是这颗按键走回旧路径
            return false;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 只做按键过滤，不关心界面变化
    }

    @Override
    public void onInterrupt() {
        // 无需响应中断
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sInstance = null;
        notifyStateChanged();
        return super.onUnbind(intent);
    }

    /**
     * 判断本服务是否已在系统设置里被用户启用，带短时缓存。
     * <p>
     * 缓存在两种情况下立即失效：系统广播无障碍状态变化、{@code Settings.Secure} 中的
     * 已启用服务列表发生变更。因此用户在系统设置里授权或撤销授权后，界面与门控都会立刻跟上，
     * 不需要重新进入游戏或重新拨一次开关
     */
    public static boolean isEnabled(Context context) {
        if (context == null) {
            return false;
        }
        // 服务实例连着就是最直接的证据，不必走缓存
        if (getInstance() != null) {
            return true;
        }
        long now = System.currentTimeMillis();
        Boolean cached = sCachedEnabled;
        if (cached != null && now - sCachedAt < STATE_CACHE_TTL_MS) {
            return cached;
        }
        boolean result = isEnabledFresh(context);
        sCachedEnabled = result;
        sCachedAt = now;
        return result;
    }

    /** 丢弃状态缓存，下次查询重新读取系统状态 */
    public static void invalidateStateCache() {
        sCachedEnabled = null;
        sCachedAt = 0L;
    }

    /**
     * 直接向系统查询服务是否启用，不使用缓存。
     */
    public static boolean isEnabledFresh(Context context) {
        if (context == null) {
            return false;
        }
        if (getInstance() != null) {
            return true;
        }
        try {
            ComponentName componentName = new ComponentName(context, FCLKeyAccessibilityService.class);
            String serviceId = componentName.flattenToString();

            String enabled = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled != null) {
                for (String item : enabled.split(":")) {
                    // 某些 ROM 会把组件名写成「包名/类名」的短形式，统一转小写后比较，
                    // 并兼容短类名写法
                    String trimmed = item.trim();
                    if (trimmed.equalsIgnoreCase(serviceId)
                            || trimmed.toLowerCase().endsWith("/" + componentName.getShortClassName().toLowerCase())) {
                        return true;
                    }
                }
            }

            AccessibilityManager manager =
                    (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (manager != null && manager.isEnabled()) {
                List<AccessibilityServiceInfo> list = manager.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                for (AccessibilityServiceInfo info : list) {
                    if (info == null || info.getResolveInfo() == null
                            || info.getResolveInfo().serviceInfo == null) {
                        continue;
                    }
                    ComponentName cn = new ComponentName(
                            info.getResolveInfo().serviceInfo.packageName,
                            info.getResolveInfo().serviceInfo.name);
                    if (context.getPackageName().equals(cn.getPackageName())
                            && FCLKeyAccessibilityService.class.getName().equals(cn.getClassName())) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }

    /**
     * 注册无障碍状态变化监听：用户在系统设置里授权/撤销授权、或服务被系统回收时触发。
     * <p>
     * 监听器用弱引用保存，界面销毁后不会因为忘记反注册而泄漏；回调统一切到主线程执行
     */
    public static void addStateChangeListener(Context context, final Runnable listener) {
        if (context == null || listener == null) {
            return;
        }
        sStateListeners.add(listener);
        ensureStateWatchers(context.getApplicationContext());
    }

    public static void removeStateChangeListener(Runnable listener) {
        if (listener != null) {
            sStateListeners.remove(listener);
        }
    }

    private static void notifyStateChanged() {
        invalidateStateCache();
        for (Runnable listener : new java.util.ArrayList<>(sStateListeners)) {
            if (listener == null) {
                continue;
            }
            try {
                listener.run();
            } catch (Throwable ignored) {
                // 单个监听器异常不能影响其它监听器
            }
        }
    }

    /**
     * 装一次系统级监听。两条通路都装：
     * <ul>
     *   <li>{@link AccessibilityManager} 的状态变化回调，覆盖「无障碍总开关」与被接管服务集合的变化</li>
     *   <li>{@code Settings.Secure} 的 ContentObserver，覆盖部分 ROM 上回调不触发的场景</li>
     * </ul>
     */
    private static synchronized void ensureStateWatchers(final Context appContext) {
        if (sStateChangeListener == null) {
            try {
                AccessibilityManager manager =
                        (AccessibilityManager) appContext.getSystemService(Context.ACCESSIBILITY_SERVICE);
                if (manager != null) {
                    sManager = manager;
                    AccessibilityManager.AccessibilityStateChangeListener l =
                            enabled -> notifyStateChanged();
                    sStateChangeListener = l;
                    manager.addAccessibilityStateChangeListener(l);
                }
            } catch (Throwable ignored) {
                sStateChangeListener = null;
            }
        }
        if (sSettingsObserver == null) {
            try {
                android.database.ContentObserver observer = new android.database.ContentObserver(
                        new android.os.Handler(android.os.Looper.getMainLooper())) {
                    @Override
                    public void onChange(boolean selfChange) {
                        notifyStateChanged();
                    }
                };
                sSettingsObserver = observer;
                appContext.getContentResolver().registerContentObserver(
                        Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                        false, observer);
            } catch (Throwable ignored) {
                sSettingsObserver = null;
            }
        }
    }

    /**
     * 跳到系统无障碍设置页，让用户为本服务授权。
     */
    public static void openAccessibilitySettings(Context context) {
        if (context == null) {
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            try {
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Throwable ignored) {
                // 系统设置页都无法打开，只能作罢
            }
        }
    }
}
