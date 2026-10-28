package com.bitwig.extension.api.project.parameter;

public interface DoubleParameter extends Parameter {
    double getValue(Unit unit);
    void setValue(double value, Unit unit);
}
