package com.bitwig.extension.api.project.timeline;

import java.util.List;

public interface EventTimeline {
    void addEvent(Event event);
    List getEvents();
}
