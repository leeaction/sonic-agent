package org.cloud.sonic.agent.controller;

import org.cloud.sonic.agent.bridge.ios.SibTool;
import org.cloud.sonic.agent.common.interfaces.DeviceStatus;
import org.cloud.sonic.agent.common.maps.AndroidDeviceManagerMap;
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

        // iOS devices
        for (String udId : IOSDeviceManagerMap.getMap().keySet()) {
            result.add(Map.of(
                "udId", udId,
                "platform", "iOS",
                "testReady", SibTool.isWdaReady(udId)
            ));
        }

        // Android devices
        for (Map.Entry<String, String> entry : AndroidDeviceManagerMap.getStatusMap().entrySet()) {
            result.add(Map.of(
                "udId", entry.getKey(),
                "platform", "Android",
                "testReady", DeviceStatus.ONLINE.equals(entry.getValue())
            ));
        }

        return result;
    }
}
