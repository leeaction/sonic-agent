package org.cloud.sonic.agent.controller;

import com.android.ddmlib.IDevice;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceBridgeTool;
import org.cloud.sonic.agent.bridge.ios.SibTool;
import org.cloud.sonic.agent.common.maps.IOSDeviceManagerMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/health/devices")
    public List<Map<String, Object>> deviceReadiness() {
        List<Map<String, Object>> result = new ArrayList<>();

        // iOS devices — testReady only when WDA process is alive
        for (String udId : IOSDeviceManagerMap.getMap().keySet()) {
            result.add(Map.of(
                "udId", udId,
                "platform", "iOS",
                "testReady", SibTool.isWdaReady(udId)
            ));
        }

        // Android devices — testReady when ADB reports ONLINE state
        IDevice[] androidDevices = AndroidDeviceBridgeTool.getRealOnLineDevices();
        if (androidDevices != null) {
            for (IDevice device : androidDevices) {
                if (IDevice.DeviceState.ONLINE.equals(device.getState())) {
                    result.add(Map.of(
                        "udId", device.getSerialNumber(),
                        "platform", "Android",
                        "testReady", true
                    ));
                }
            }
        }

        return result;
    }
}
