package com.bitwig.extension.api.project.mixer;

import com.bitwig.extension.api.project.parameter.BooleanParameter;
import com.bitwig.extension.api.project.parameter.DoubleParameter;

public interface Mixer {
    DoubleParameter getVolume();
    DoubleParameter getPan();
    BooleanParameter getMute();
    com.bitwig.extension.api.project.parameter.EnumParameter getCrossfadeMode();
    Send getSend(int index);
    int getSendCount();
}
