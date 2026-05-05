package com.iotconnect.async;

import com.iotconnect.entity.Device;
import com.iotconnect.entity.DeviceData;

import java.util.concurrent.Callable;

public class AlertDetectionTask implements Callable<AlertDetectionResult> {

    private final Device device;
    private final DeviceData deviceData;
    private final AlertDetectionProcessor processor;

    public AlertDetectionTask(Device device, DeviceData deviceData, AlertDetectionProcessor processor) {
        this.device = device;
        this.deviceData = deviceData;
        this.processor = processor;
    }

    @Override
    public AlertDetectionResult call() throws Exception {
        long startTime = System.currentTimeMillis();
        
        try {
            processor.processAlertDetection(device, deviceData);
            long duration = System.currentTimeMillis() - startTime;
            
            return new AlertDetectionResult(
                    device.getDeviceId(),
                    deviceData.getDataType(),
                    true,
                    duration,
                    null
            );
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            return new AlertDetectionResult(
                    device.getDeviceId(),
                    deviceData.getDataType(),
                    false,
                    duration,
                    e.getMessage()
            );
        }
    }

    public Device getDevice() {
        return device;
    }

    public DeviceData getDeviceData() {
        return deviceData;
    }
}
