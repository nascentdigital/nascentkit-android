package com.nascentdigital.device;

public class DeviceDiscoveryException extends Exception {
    public DeviceDiscoveryException() {
        super();
    }

    public DeviceDiscoveryException(String message) {
        super(message);
    }

    public DeviceDiscoveryException(String message, Throwable cause) {
        super(message, cause);
    }

    public DeviceDiscoveryException(Throwable cause) {
        super(cause);
    }
}
