package com.hongguo.auto;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.hongguo.auto.core.UiNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 AccessibilityNodeInfo 树转换成纯数据的 UiNode 树，并把结果交给
 * {@link com.hongguo.auto.core.AdDetector}。
 *
 * <p>为了省电，这里分成两步：先用 {@link #collectRawWindows} 拿到"哪个包在前台"（很便宜），
 * 只有确认前台是目标应用时，才把相关窗口的节点树转换出来。
 */
public final class NodeScanner {

    private static final int MAX_DEPTH = 60;
    private static final int MAX_NODES = 4000;

    private NodeScanner() {
    }

    public static UiNode toUiNode(AccessibilityNodeInfo node) {
        if (node == null) {
            return null;
        }
        int[] budget = new int[]{MAX_NODES};
        return convert(node, 0, budget);
    }

    private static UiNode convert(AccessibilityNodeInfo node, int depth, int[] budget) {
        if (node == null || depth > MAX_DEPTH || budget[0] <= 0) {
            return null;
        }
        budget[0]--;
        String text = asString(node.getText());
        String desc = asString(node.getContentDescription());
        String viewId = node.getViewIdResourceName();
        String cls = asString(node.getClassName());
        Rect rect = new Rect();
        try {
            node.getBoundsInScreen(rect);
        } catch (Throwable ignored) {
            rect.setEmpty();
        }
        int count = 0;
        try {
            count = node.getChildCount();
        } catch (Throwable ignored) {
        }
        List<UiNode> children = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Throwable ignored) {
            }
            UiNode converted = convert(child, depth + 1, budget);
            if (converted != null) {
                children.add(converted);
            }
        }
        return new UiNode(text, desc, viewId, cls, rect.left, rect.top, rect.right, rect.bottom, children);
    }

    /** 窗口 + 根节点，尚未转换成 UiNode。 */
    public static final class RawWindow {
        public final String pkg;
        public final int layer;
        public final boolean active;
        public final AccessibilityNodeInfo root;

        public RawWindow(String pkg, int layer, boolean active, AccessibilityNodeInfo root) {
            this.pkg = pkg;
            this.layer = layer;
            this.active = active;
            this.root = root;
        }
    }

    /** 窗口快照（已转换成 UiNode），供诊断导出使用。 */
    public static final class WindowInfo {
        public final String pkg;
        public final int layer;
        public final boolean active;
        public final UiNode root;

        public WindowInfo(String pkg, int layer, boolean active, UiNode root) {
            this.pkg = pkg;
            this.layer = layer;
            this.active = active;
            this.root = root;
        }
    }

    /** 读取所有可见窗口（活动窗口 + 悬浮窗），不转换节点树，开销很小。 */
    public static List<RawWindow> collectRawWindows(AccessibilityService service) {
        List<RawWindow> list = new ArrayList<>();
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) {
                        continue;
                    }
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null) {
                        continue;
                    }
                    list.add(new RawWindow(asString(root.getPackageName()), window.getLayer(),
                            window.isActive(), root));
                }
            }
        } catch (Throwable ignored) {
        }
        if (list.isEmpty()) {
            try {
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                if (root != null) {
                    list.add(new RawWindow(asString(root.getPackageName()), 0, true, root));
                }
            } catch (Throwable ignored) {
            }
        }
        return list;
    }

    public static List<WindowInfo> snapshot(List<RawWindow> rawWindows) {
        List<WindowInfo> list = new ArrayList<>(rawWindows.size());
        for (RawWindow raw : rawWindows) {
            if (raw == null) {
                continue;
            }
            list.add(new WindowInfo(raw.pkg, raw.layer, raw.active, toUiNode(raw.root)));
        }
        return list;
    }

    /** 把窗口节点树导出成文本，方便用户在手机上抓取现场信息。 */
    public static String dumpWindows(AccessibilityService service, List<WindowInfo> windows) {
        StringBuilder sb = new StringBuilder();
        sb.append("屏幕: ").append(service.getResources().getDisplayMetrics().widthPixels)
                .append("x").append(service.getResources().getDisplayMetrics().heightPixels).append('\n');
        for (WindowInfo window : windows) {
            sb.append("--- 窗口 pkg=").append(window.pkg)
                    .append(" layer=").append(window.layer)
                    .append(" active=").append(window.active)
                    .append(" ---\n");
            appendNode(sb, window.root, 0);
        }
        return sb.toString();
    }

    private static void appendNode(StringBuilder sb, UiNode node, int depth) {
        if (node == null || depth > 30) {
            return;
        }
        if (node.viewId != null || (node.text != null && !node.text.isEmpty())
                || (node.desc != null && !node.desc.isEmpty())) {
            for (int i = 0; i < depth; i++) {
                sb.append("  ");
            }
            sb.append("id=").append(node.viewId == null ? "-" : node.viewId);
            if (node.text != null && !node.text.isEmpty()) {
                sb.append(" text=[").append(node.text).append(']');
            }
            if (node.desc != null && !node.desc.isEmpty()) {
                sb.append(" desc=[").append(node.desc).append(']');
            }
            sb.append(" bounds=").append(node.left).append(',').append(node.top).append(',')
                    .append(node.right).append(',').append(node.bottom).append('\n');
        }
        for (int i = 0; i < node.children.size(); i++) {
            appendNode(sb, node.children.get(i), depth + 1);
        }
    }

    private static String asString(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }
}
