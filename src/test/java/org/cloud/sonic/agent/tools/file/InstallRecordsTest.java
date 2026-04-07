package org.cloud.sonic.agent.tools.file;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.*;

public class InstallRecordsTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Before
    public void setUp() throws IOException {
        File recordsFile = tempFolder.newFile("install-records.json");
        InstallRecords.setRecordsFile(recordsFile);
    }

    @After
    public void tearDown() {
        InstallRecords.resetForTesting();
    }

    @Test
    public void testRecordAndCheck() {
        String deviceId = "device123";
        String packageName = "com.example.app";
        String fileHash = "abc123hash";

        // 初始应该没有记录
        assertFalse(InstallRecords.isInstalled(deviceId, packageName, fileHash));

        // 记录安装
        InstallRecords.recordInstall(deviceId, packageName, fileHash);

        // 现在应该有记录
        assertTrue(InstallRecords.isInstalled(deviceId, packageName, fileHash));

        // 不同 hash 应该返回 false
        assertFalse(InstallRecords.isInstalled(deviceId, packageName, "differentHash"));
    }

    @Test
    public void testDifferentDevices() {
        String packageName = "com.example.app";
        String fileHash = "abc123hash";

        InstallRecords.recordInstall("device1", packageName, fileHash);

        assertTrue(InstallRecords.isInstalled("device1", packageName, fileHash));
        assertFalse(InstallRecords.isInstalled("device2", packageName, fileHash));
    }
}
