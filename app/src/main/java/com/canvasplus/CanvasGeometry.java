package com.canvasplus;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 窗格使用画布内坐标，应用按同一长宽比获得自己的局部窗口尺寸。 */
final class CanvasGeometry {
    static final float LANDSCAPE_LEFT_SHARE = 0.5f;

    private CanvasGeometry() {}

    static List<Rect> panes(int width, int height, int gap, int count, int mode) {
        return panes(width, height, gap, count, mode, defaultLeftShare(count, mode));
    }

    static List<Rect> panes(int width, int height, int gap, int count, int mode,
                            float leftShare) {
        ArrayList<Rect> panes = new ArrayList<>(count);
        if (width <= gap * 2 + 3 || height <= gap + 2) return panes;

        int halfWidth = (width - gap) / 2;
        int halfHeight = (height - gap) / 2;
        if (count == 4) {
            int leftWidth = Math.round((width - gap) * leftShare);
            Rect top = new Rect(0, 0, width, halfHeight);
            Rect bottom = new Rect(0, halfHeight + gap, width, height);
            addColumns(panes, top, leftWidth, gap);
            addColumns(panes, bottom, leftWidth, gap);
            return panes;
        }
        if (count != 3) return panes;

        if (mode == 3) {
            int columnWidth = (width - 2 * gap) / 3;
            for (int index = 0; index < 3; index++) {
                int left = index * (columnWidth + gap);
                int right = index == 2 ? width : left + columnWidth;
                panes.add(new Rect(left, 0, right, height));
            }
        } else if (mode == 4 || mode == 5) {
            int mainHeight = mainSize(height - gap);
            if (mode == 4) {
                panes.add(new Rect(0, 0, width, mainHeight));
                addColumns(panes, new Rect(0, mainHeight + gap, width, height), halfWidth, gap);
            } else {
                int smallHeight = height - gap - mainHeight;
                addColumns(panes, new Rect(0, 0, width, smallHeight), halfWidth, gap);
                panes.add(new Rect(0, smallHeight + gap, width, height));
            }
        } else {
            int leftWidth = Math.round((width - gap) * leftShare);
            if (mode == 7) {
                addRows(panes, 0, leftWidth, height, halfHeight, gap);
                panes.add(new Rect(leftWidth + gap, 0, width, height));
            } else {
                panes.add(new Rect(0, 0, leftWidth, height));
                addRows(panes, leftWidth + gap, width, height, halfHeight, gap);
            }
        }
        return panes;
    }

    private static int mainSize(int available) {
        return Math.round(available * 2f / 3f);
    }

    static float defaultLeftShare(int count, int mode) {
        if (count == 4) return 0.5f;
        return mode == 7 ? 1f / 3f : 2f / 3f;
    }

    static float clampLeftShare(float leftShare) {
        return Math.max(0.25f, Math.min(0.75f, leftShare));
    }

    static boolean hasPhoneRatioColumns(int count, int mode) {
        return count == 4 || (count == 3 && (mode == 6 || mode == 7));
    }

    static int mainPane(int count, int mode) {
        if (count != 3) return -1;
        if (mode == 6) return 0;
        return mode == 7 ? 2 : -1;
    }

    static boolean swapMainPane(List<?> tasksBySlot, int mode, int selectedSlot) {
        int main = mainPane(tasksBySlot.size(), mode);
        if (main < 0 || selectedSlot < 0 || selectedSlot >= tasksBySlot.size()
                || selectedSlot == main) return false;
        Collections.swap(tasksBySlot, main, selectedSlot);
        return true;
    }

    static List<Rect> landscapeRow(int width, int height, int gap, int count, float leftShare) {
        List<Rect> panes = new ArrayList<>(count);
        int space = width - gap * (count - 1);
        if ((count != 3 && count != 4) || space < count * 10 || height <= 0) return panes;
        if (count == 3) {
            // 默认左侧沿用四窗的前两列，右侧主窗占用余下两列及其内缝。
            int leftWidth = (width - 3 * gap) / 2
                    + Math.round(space * (leftShare - LANDSCAPE_LEFT_SHARE));
            int first = leftWidth / 2;
            panes.add(new Rect(0, 0, first, height));
            panes.add(new Rect(first + gap, 0, leftWidth + gap, height));
            panes.add(new Rect(leftWidth + 2 * gap, 0, width, height));
            return panes;
        }
        for (int index = 0; index < count; index++) {
            int left = space * index / count + gap * index;
            int right = space * (index + 1) / count + gap * index;
            panes.add(new Rect(left, 0, right, height));
        }
        return panes;
    }

    static List<Rect> phoneRatioColumns(int width, int height, int gap, int count, int mode,
                                        float leftShare, Rect phone) {
        ArrayList<Rect> panes = new ArrayList<>(count);
        if (width <= gap + 2 || height <= gap + 2 || phone.isEmpty()) return panes;

        int leftRows = count == 4 || mode == 7 ? 2 : 1;
        int rightRows = count == 4 || mode == 6 ? 2 : 1;
        float aspect = (float) phone.height() / phone.width();
        int maxLeftWidth = (int) (((height - (leftRows - 1) * gap) / leftRows) / aspect);
        int maxRightWidth = (int) (((height - (rightRows - 1) * gap) / rightRows) / aspect);
        int columnsWidth = Math.min(width - gap, (int) Math.min(
                maxLeftWidth / leftShare, maxRightWidth / (1f - leftShare)));
        int leftWidth = Math.round(columnsWidth * leftShare);
        int rightWidth = columnsWidth - leftWidth;
        if (leftWidth <= 0 || rightWidth <= 0) return panes;

        int leftPaneHeight = Math.round(leftWidth * aspect);
        int rightPaneHeight = Math.round(rightWidth * aspect);
        int leftTop = (height - leftRows * leftPaneHeight - (leftRows - 1) * gap) / 2;
        int rightTop = (height - rightRows * rightPaneHeight - (rightRows - 1) * gap) / 2;

        Rect leftFirst = new Rect(0, leftTop, leftWidth, leftTop + leftPaneHeight);
        Rect rightFirst = new Rect(leftWidth + gap, rightTop, columnsWidth + gap,
                rightTop + rightPaneHeight);
        panes.add(leftFirst);
        if (count == 4) {
            panes.add(rightFirst);
            panes.add(below(leftFirst, gap));
            panes.add(below(rightFirst, gap));
        } else if (leftRows == 2) {
            panes.add(below(leftFirst, gap));
            panes.add(rightFirst);
        } else {
            panes.add(rightFirst);
            panes.add(below(rightFirst, gap));
        }
        return panes;
    }

    private static Rect below(Rect pane, int gap) {
        return new Rect(pane.left, pane.bottom + gap, pane.right,
                pane.bottom + gap + pane.height());
    }

    private static void addColumns(List<Rect> panes, Rect row, int halfWidth, int gap) {
        panes.add(new Rect(0, row.top, halfWidth, row.bottom));
        panes.add(new Rect(halfWidth + gap, row.top, row.right, row.bottom));
    }

    private static void addRows(List<Rect> panes, int left, int right, int height,
                                int halfHeight, int gap) {
        panes.add(new Rect(left, 0, right, halfHeight));
        panes.add(new Rect(left, halfHeight + gap, right, height));
    }

    static Rect taskBounds(Rect pane, Rect display, int minWidth) {
        float requested = Math.max(1f, (float) minWidth / pane.width());
        float supported = Math.min((float) display.width() / pane.width(),
                (float) display.height() / pane.height());
        float scale = Math.min(requested, supported);
        return new Rect(0, 0, Math.round(pane.width() * scale), Math.round(pane.height() * scale));
    }
}
