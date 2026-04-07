package org.cloud.sonic.agent.tools.file;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class PackageParserTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testParseApk() throws IOException {
        // 使用 plugins 目录下的真实 APK 文件
        File apkFile = new File("plugins/sonic-android-apk.apk");
        if (!apkFile.exists()) {
            System.out.println("Skipping APK test - no test APK available");
            return;
        }

        PackageInfo info = PackageParser.parse(apkFile);

        assertNotNull(info);
        assertNotNull(info.getPackageName());
        assertTrue(info.getPackageName().contains("sonic"));
    }

    @Test
    public void testParseUnknownExtension() throws IOException {
        File unknownFile = tempFolder.newFile("test.txt");
        Files.writeString(unknownFile.toPath(), "not a package");

        try {
            PackageParser.parse(unknownFile);
            fail("Should throw IOException for unknown file type");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("不支持的文件类型"));
        }
    }
}
