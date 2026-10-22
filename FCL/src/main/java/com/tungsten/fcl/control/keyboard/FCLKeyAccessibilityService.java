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
        return super.onUnbind(intent);
    }

    /**
     * 判断本服务是否已在系统设置里被用户启用。
     * <p>
     * 这里同时用两种方式判断：{@code Settings.Secure} 里的字符串便于在开关切换时立刻判断，
     * {@link AccessibilityManager} 的列表则能反映「已勾选但服务未跑起来」的真实状态。
     * 任一为真即视为可用。
     */
    public static boolean isEnabled(Context context) {
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
