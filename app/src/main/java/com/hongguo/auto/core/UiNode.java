package com.hongguo.auto.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 无障碍节点树的纯数据快照。Android 的 AccessibilityNodeInfo 会被转换成这个结构，
 * 这样检测逻辑就可以脱离设备做单元测试。
 */
public final class UiNode {

    public final String text;
    public final String desc;
    public final String viewId;
    public final String className;
    public final int left;
    public final int top;
    public final int right;
    public final int bottom;
    public final List<UiNode> children;

    public UiNode(String text, String desc, String viewId, String className,
                  int left, int top, int right, int bottom, List<UiNode> children) {
        this.text = text;
        this.desc = desc;
        this.viewId = viewId;
        this.className = className;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.children = children == null ? Collections.<UiNode>emptyList() : children;
    }

    /** 测试辅助：只有文本的可见小方块。 */
    public static UiNode text(String value) {
        return new UiNode(value, null, null, "android.widget.TextView", 0, 0, 100, 40, null);
    }

    /** 测试辅助：文本 + 描述 + 控件 id。 */
    public static UiNode leaf(String text, String desc, String viewId) {
        return new UiNode(text, desc, viewId, "android.widget.TextView", 0, 0, 200, 40, null);
    }

    /** 测试辅助：带子节点的容器，铺满给定区域。 */
    public static UiNode group(UiNode... kids) {
        return group(1080, 0, 2400, kids);
    }

    public static UiNode group(int width, int top, int bottom, UiNode... kids) {
        return new UiNode(null, null, null, "android.widget.FrameLayout",
                0, top, width, bottom, new ArrayList<>(Arrays.asList(kids)));
    }

    public boolean isVisible() {
        return right > left && bottom > top;
    }

    public int width() {
        return right - left;
    }

    public int height() {
        return bottom - top;
    }

    /** 返回 "包名:id/名字" 里 ":" 后面的部分，取不到时返回 null。 */
    public String idName() {
        if (viewId == null) {
            return null;
        }
        int i = viewId.indexOf(":id/");
        if (i >= 0) {
            return viewId.substring(i + 4);
        }
        return viewId;
    }

    public boolean hasId(String name) {
        return name.equals(idName());
    }
}
