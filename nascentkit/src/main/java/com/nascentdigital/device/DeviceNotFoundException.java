package com.nascentdigital.device;

public class DeviceNotFoundException extends Exception {
    public DeviceNotFoundException() {
        super();
    }

    public DeviceNotFoundException(String message) {
        super(message);
    }

    public DeviceNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }

    public DeviceNotFoundException(Throwable cause) {
        super(cause);
    }
}
