package com.bitwig.extension.api.project.mixer;

import com.bitwig.extension.api.project.parameter.BooleanParameter;
import com.bitwig.extension.api.project.parameter.DoubleParameter;
import com.bitwig.extension.api.project.parameter.EnumParameter;

public interface NativeDevice extends Device {
    DoubleParameter getDoubleParameter(String name);
    BooleanParameter getBooleanParameter(String name);
    EnumParameter getEnumParameter(String name);
}
