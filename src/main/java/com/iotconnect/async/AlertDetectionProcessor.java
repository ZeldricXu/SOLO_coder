package com.iotconnect.async;

import com.iotconnect.entity.Device;
import com.iotconnect.entity.DeviceData;

public interface AlertDetectionProcessor {
    void processAlertDetection(Device device, DeviceData deviceData);
}
