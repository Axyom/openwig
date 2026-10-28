package com.bitwig.extension.api.project;

public interface Scene extends Titled {
    com.bitwig.extension.api.Color getColor();
    void setColor(com.bitwig.extension.api.Color color);
}
