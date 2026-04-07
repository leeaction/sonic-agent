package org.cloud.sonic.agent.tools.file;

import org.junit.Test;
import static org.junit.Assert.*;

public class InstallResultTest {

    @Test
    public void testInstalled() {
        InstallResult result = InstallResult.installed();

        assertEquals(InstallResult.Status.INSTALLED, result.getStatus());
        assertTrue(result.isSuccess());
        assertNull(result.getException());
    }

    @Test
    public void testSkipped() {
        InstallResult result = InstallResult.skipped("Already installed");

        assertEquals(InstallResult.Status.SKIPPED, result.getStatus());
        assertTrue(result.isSuccess());
        assertEquals("Already installed", result.getMessage());
    }

    @Test
    public void testFailed() {
        Exception e = new RuntimeException("Install error");
        InstallResult result = InstallResult.failed("Installation failed", e);

        assertEquals(InstallResult.Status.FAILED, result.getStatus());
        assertFalse(result.isSuccess());
        assertEquals(e, result.getException());
    }
}
