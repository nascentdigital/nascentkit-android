package com.nascentdigital.device;

public class DeviceAccessException extends Exception {
    public DeviceAccessException() {
        super();
    }

    public DeviceAccessException(String message) {
        super(message);
    }

    public DeviceAccessException(String message, Throwable cause) {
        super(message, cause);
    }

    public DeviceAccessException(Throwable cause) {
        super(cause);
    }
}
