package com.bitwig.extension.api;

/** Compile-time signature only; the patched JAR keeps Bitwig's own class. */
public class Color {
    public static Color fromRGB(double red, double green, double blue) { throw new UnsupportedOperationException(); }
    public static Color fromRGBA(double red, double green, double blue, double alpha) { throw new UnsupportedOperationException(); }
    public String toHex() { throw new UnsupportedOperationException(); }
    public double getRed() { throw new UnsupportedOperationException(); }
    public double getGreen() { throw new UnsupportedOperationException(); }
    public double getBlue() { throw new UnsupportedOperationException(); }
    public double getAlpha() { throw new UnsupportedOperationException(); }
}
