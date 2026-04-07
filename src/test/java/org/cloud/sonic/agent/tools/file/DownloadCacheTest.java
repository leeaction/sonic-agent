package org.cloud.sonic.agent.tools.file;

import com.alibaba.fastjson.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class DownloadCacheTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Path cacheDir;
    private Path indexFile;

    @Before
    public void setUp() throws IOException {
        cacheDir = tempFolder.newFolder("package-cache").toPath();
        indexFile = cacheDir.resolve("cache-index.json");
        DownloadCache.setCacheDirectory(cacheDir.toFile());
    }

    @After
    public void tearDown() {
        DownloadCache.resetForTesting();
    }

    @Test
    public void testMd5Hash() {
        String hash1 = DownloadCache.md5("https://example.com/app.apk");
        String hash2 = DownloadCache.md5("https://example.com/app.apk");
        String hash3 = DownloadCache.md5("https://example.com/other.apk");

        assertEquals(hash1, hash2);
        assertNotEquals(hash1, hash3);
        assertEquals(32, hash1.length()); // MD5 is 32 hex chars
    }

    @Test
    public void testCacheEntryExpiry() {
        long now = System.currentTimeMillis();
        long sevenDaysAgo = now - (8 * 24 * 60 * 60 * 1000L);
        long oneDayAgo = now - (1 * 24 * 60 * 60 * 1000L);

        assertTrue(DownloadCache.isExpired(sevenDaysAgo));
        assertFalse(DownloadCache.isExpired(oneDayAgo));
    }

    @Test
    public void testLoadCorruptedIndex() throws IOException {
        // 写入无效 JSON
        Files.writeString(indexFile, "not valid json {{{");

        // 应该返回空对象而不是抛异常
        JSONObject index = DownloadCache.loadCacheIndex();
        assertNotNull(index);
        assertTrue(index.isEmpty());
    }
}
