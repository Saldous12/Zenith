package com.zenith.category;

/**
 * Module categories, each with a display name and ARGB accent colour.
 */
public enum Category {
    CLIENT ("Client",  0xFF4A9EFF),   // blue
    COMBAT ("Combat",  0xFFFF4A4A),   // red
    DONUT  ("Donut",   0xFFFF8C42),   // orange
    RENDER ("Render",  0xFF42E8FF),   // cyan
    VISUAL ("Visual",  0xFF42FF6E);   // green

    public final String displayName;
    public final int    color;        // ARGB

    Category(String displayName, int color) {
        this.displayName = displayName;
        this.color       = color;
    }

    /** Extract red channel (0-255). */
    public float getR() { return ((color >> 16) & 0xFF) / 255f; }
    /** Extract green channel (0-255). */
    public float getG() { return ((color >> 8)  & 0xFF) / 255f; }
    /** Extract blue channel (0-255). */
    public float getB() { return  (color        & 0xFF) / 255f; }
}
