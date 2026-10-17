package com.bitwig.extension.api.project.mixer;

import com.bitwig.extension.api.project.parameter.DoubleParameter;

/** Compile-time signature only; the patched JAR keeps Bitwig's own interface. */
public interface Send {
    DoubleParameter getAmount();
}
