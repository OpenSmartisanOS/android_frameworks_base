package com.android.systemui.volume;

import android.graphics.Insets;
import android.graphics.Rect;

/** Pure responsive geometry for the 1080 x 2242 Smartisan R2 volume canvas. */
public final class VolumeDialogLayoutModel {
    public static final float REFERENCE_WIDTH = 1080f;
    public static final float REFERENCE_HEIGHT = 2242f;

    public static final class Result {
        public final float scale;
        public final int columnWidth;
        public final int panelHeight;
        public final int rightMargin;
        public final int topMargin;
        public final int mainTop;
        public final int muteHeight;
        public final int expandTop;
        public final int enterTranslation;
        public final int shadowPaddingHorizontal;
        public final int shadowPaddingVertical;
        public final int muteEditorShift;
        public final int timerHeight;
        public final int cancelHeight;
        public final boolean landscape;

        Result(float scale, int columnWidth, int panelHeight, int rightMargin, int topMargin,
                int mainTop, int muteHeight, int expandTop, int enterTranslation,
                int shadowPaddingHorizontal, int shadowPaddingVertical, int muteEditorShift,
                int timerHeight, int cancelHeight, boolean landscape) {
            this.scale = scale;
            this.columnWidth = columnWidth;
            this.panelHeight = panelHeight;
            this.rightMargin = rightMargin;
            this.topMargin = topMargin;
            this.mainTop = mainTop;
            this.muteHeight = muteHeight;
            this.expandTop = expandTop;
            this.enterTranslation = enterTranslation;
            this.shadowPaddingHorizontal = shadowPaddingHorizontal;
            this.shadowPaddingVertical = shadowPaddingVertical;
            this.muteEditorShift = muteEditorShift;
            this.timerHeight = timerHeight;
            this.cancelHeight = cancelHeight;
            this.landscape = landscape;
        }
    }

    private VolumeDialogLayoutModel() {}

    public static Result calculate(
            int width, int height, float density, Insets insets, boolean landscape) {
        int safeWidth = Math.max(1, width - insets.left - insets.right);
        int safeHeight = Math.max(1, height - insets.top - insets.bottom);
        float contentWidth = Math.min(safeWidth, 480f * density);
        // The retail landscape panel is the same R2 column geometry anchored 30 design pixels
        // from the top; it is not a portrait canvas scaled down to 48% of its intended size.
        float heightReference = landscape ? 988f : REFERENCE_HEIGHT;
        float scale = Math.min(contentWidth / REFERENCE_WIDTH, safeHeight / heightReference);
        int column = Math.max(1, Math.round(180f * scale));
        int panel = Math.max(1, Math.round(594f * scale));
        int mainTop = Math.round(126f * scale);
        int muteHeight = Math.round(90f * scale);
        int expandTop = Math.round(756f * scale);
        int shadowPaddingHorizontal = Math.round(41f * scale);
        int shadowPaddingVertical = Math.round(71f * scale);
        int rootHeight = expandTop + muteHeight + shadowPaddingVertical * 2;
        int top;
        if (landscape) {
            top = insets.top + Math.round(30f * scale);
        } else {
            int base = Math.round(441f * scale);
            int extra = Math.max(0, safeHeight - Math.round(REFERENCE_HEIGHT * scale));
            top = insets.top + base
                    + Math.round(extra * (441f / (REFERENCE_HEIGHT - 988f)));
        }
        top = Math.max(insets.top, Math.min(top,
                insets.top + Math.max(0, safeHeight - rootHeight)));
        int cancelHeight = Math.min(panel,
                Math.max(Math.round(126f * scale), Math.round(48f * density)));
        int timerHeight = Math.max(1, panel - cancelHeight);
        return new Result(scale, column, panel,
                insets.right + Math.round(12f * scale), top, mainTop, muteHeight, expandTop,
                Math.round(144f * scale), shadowPaddingHorizontal, shadowPaddingVertical,
                Math.round(216f * scale), timerHeight, cancelHeight, landscape);
    }

    public static Rect safeBounds(int width, int height, Insets insets) {
        return new Rect(insets.left, insets.top, width - insets.right, height - insets.bottom);
    }
}
