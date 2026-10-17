package com.bitwig.extension.api.project.mixer;

public interface DeviceChain {
    java.util.List<?> getDevices();
    void addDevice(Device device);
}
