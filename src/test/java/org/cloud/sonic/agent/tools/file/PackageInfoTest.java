package org.cloud.sonic.agent.tools.file;

import org.junit.Test;
import static org.junit.Assert.*;

public class PackageInfoTest {

    @Test
    public void testConstructorAndGetters() {
        PackageInfo info = new PackageInfo("com.example.app", "1.0.0", "1");

        assertEquals("com.example.app", info.getPackageName());
        assertEquals("1.0.0", info.getVersionName());
        assertEquals("1", info.getVersionCode());
    }

    @Test
    public void testToString() {
        PackageInfo info = new PackageInfo("com.example.app", "1.0.0", "1");
        String str = info.toString();

        assertTrue(str.contains("com.example.app"));
        assertTrue(str.contains("1.0.0"));
    }
}
