package com.bitwig.extension.api.project.timeline;

/** Compile-time signatures only; runtime interface is supplied by Bitwig. */
public interface NoteExpression {
    void setValue(double value, com.bitwig.extension.api.project.parameter.Unit unit);
    void setEvents(com.bitwig.extension.api.project.parameter.AutomationEvents events,
                   com.bitwig.extension.api.project.parameter.Unit unit);
}
