# Package Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement download caching and smart installation to avoid redundant package downloads and installations.

**Architecture:** New PackageManager facade coordinating DownloadCache (URL-based caching with 7-day expiry), PackageParser (APK/IPA metadata extraction), and InstallRecords (device installation tracking via file MD5).

**Tech Stack:** Java 17, apk-parser 2.6.10, dd-plist 1.28, fastjson, JUnit

**Spec:** `docs/superpowers/specs/2026-04-07-package-cache-design.md`

---

## File Structure

### New Files
| File | Responsibility |
|------|----------------|
| `src/main/java/org/cloud/sonic/agent/tools/file/PackageInfo.java` | APK/IPA metadata container (packageName, version) |
| `src/main/java/org/cloud/sonic/agent/tools/file/InstallResult.java` | Installation result with status enum |
| `src/main/java/org/cloud/sonic/agent/tools/file/DownloadCache.java` | URL-based download caching with expiry |
| `src/main/java/org/cloud/sonic/agent/tools/file/PackageParser.java` | Parse APK/IPA to extract PackageInfo |
| `src/main/java/org/cloud/sonic/agent/tools/file/InstallRecords.java` | Track installed packages per device |
| `src/main/java/org/cloud/sonic/agent/tools/file/PackageManager.java` | Public API facade |

### Modified Files
| File | Change |
|------|--------|
| `pom.xml` | Add apk-parser and dd-plist dependencies |
| `src/main/java/org/cloud/sonic/agent/websockets/AndroidWSServer.java` | Use PackageManager for APK install |
| `src/main/java/org/cloud/sonic/agent/websockets/IOSWSServer.java` | Use PackageManager for IPA install |
| `src/main/java/org/cloud/sonic/agent/tests/handlers/AndroidStepHandler.java` | Use PackageManager for APK install |
| `src/main/java/org/cloud/sonic/agent/tests/handlers/IOSStepHandler.java` | Use PackageManager for IPA install |

### Test Files
| File | Tests |
|------|-------|
| `src/test/java/org/cloud/sonic/agent/tools/file/PackageInfoTest.java` | Data class behavior |
| `src/test/java/org/cloud/sonic/agent/tools/file/InstallResultTest.java` | Factory methods and status checks |
| `src/test/java/org/cloud/sonic/agent/tools/file/DownloadCacheTest.java` | Caching, expiry, LRU, concurrency |
| `src/test/java/org/cloud/sonic/agent/tools/file/PackageParserTest.java` | APK/IPA parsing |
| `src/test/java/org/cloud/sonic/agent/tools/file/InstallRecordsTest.java` | Record management, expiry |

---

## Task 1: Add Maven Dependencies

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add apk-parser dependency**

Add before the closing `</dependencies>` tag:

```xml
        <!-- APK 解析 -->
        <dependency>
            <groupId>net.dongliu</groupId>
            <artifactId>apk-parser</artifactId>
            <version>2.6.10</version>
        </dependency>
```

- [ ] **Step 2: Add dd-plist dependency**

Add after apk-parser:

```xml
        <!-- IPA plist 解析 -->
        <dependency>
            <groupId>com.googlecode.plist</groupId>
            <artifactId>dd-plist</artifactId>
            <version>1.28</version>
        </dependency>
```

- [ ] **Step 3: Verify dependencies resolve**

Run: `mvn dependency:resolve -q`
Expected: No errors

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "build: add apk-parser and dd-plist dependencies for package caching"
```

---

## Task 2: Create PackageInfo Data Class

**Files:**
- Create: `src/main/java/org/cloud/sonic/agent/tools/file/PackageInfo.java`
- Create: `src/test/java/org/cloud/sonic/agent/tools/file/PackageInfoTest.java`

- [ ] **Step 1: Write test for PackageInfo**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=PackageInfoTest -q`
Expected: Compilation error (PackageInfo not found)

- [ ] **Step 3: Create PackageInfo class**

```java
package org.cloud.sonic.agent.tools.file;

/**
 * 安装包信息
 */
public class PackageInfo {
    private final String packageName;  // Android: packageName, iOS: bundleId
    private final String versionName;
    private final String versionCode;

    public PackageInfo(String packageName, String versionName, String versionCode) {
        this.packageName = packageName;
        this.versionName = versionName;
        this.versionCode = versionCode;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getVersionName() {
        return versionName;
    }

    public String getVersionCode() {
        return versionCode;
    }

    @Override
    public String toString() {
        return "PackageInfo{" +
                "packageName='" + packageName + '\'' +
                ", versionName='" + versionName + '\'' +
                ", versionCode='" + versionCode + '\'' +
                '}';
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=PackageInfoTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/cloud/sonic/agent/tools/file/PackageInfo.java \
        src/test/java/org/cloud/sonic/agent/tools/file/PackageInfoTest.java
git commit -m "feat: add PackageInfo data class for APK/IPA metadata"
```

---

## Task 3: Create InstallResult Class

**Files:**
- Create: `src/main/java/org/cloud/sonic/agent/tools/file/InstallResult.java`
- Create: `src/test/java/org/cloud/sonic/agent/tools/file/InstallResultTest.java`

- [ ] **Step 1: Write test for InstallResult**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=InstallResultTest -q`
Expected: Compilation error

- [ ] **Step 3: Create InstallResult class**

```java
package org.cloud.sonic.agent.tools.file;

/**
 * 安装结果
 */
public class InstallResult {
    private final Status status;
    private final String message;
    private final Exception exception;

    public enum Status {
        INSTALLED,  // 已安装
        SKIPPED,    // 跳过（已存在相同版本）
        FAILED      // 安装失败
    }

    private InstallResult(Status status, String message, Exception exception) {
        this.status = status;
        this.message = message;
        this.exception = exception;
    }

    public static InstallResult installed() {
        return new InstallResult(Status.INSTALLED, "安装成功", null);
    }

    public static InstallResult skipped(String reason) {
        return new InstallResult(Status.SKIPPED, reason, null);
    }

    public static InstallResult failed(String message, Exception e) {
        return new InstallResult(Status.FAILED, message, e);
    }

    public Status getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public Exception getException() {
        return exception;
    }

    public boolean isSuccess() {
        return status != Status.FAILED;
    }

    @Override
    public String toString() {
        return "InstallResult{" +
                "status=" + status +
                ", message='" + message + '\'' +
                '}';
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=InstallResultTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/cloud/sonic/agent/tools/file/InstallResult.java \
        src/test/java/org/cloud/sonic/agent/tools/file/InstallResultTest.java
git commit -m "feat: add InstallResult class with status enum and factory methods"
```

---

## Task 4: Create PackageParser

**Files:**
- Create: `src/main/java/org/cloud/sonic/agent/tools/file/PackageParser.java`
- Create: `src/test/java/org/cloud/sonic/agent/tools/file/PackageParserTest.java`

- [ ] **Step 1: Write test for APK parsing**

```java
package org.cloud.sonic.agent.tools.file;

import org.junit.Test;
import org.junit.Before;
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=PackageParserTest -q`
Expected: Compilation error

- [ ] **Step 3: Create PackageParser class**

```java
package org.cloud.sonic.agent.tools.file;

import com.dd.plist.NSDictionary;
import com.dd.plist.PropertyListParser;
import net.dongliu.apk.parser.ApkFile;
import net.dongliu.apk.parser.bean.ApkMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 安装包解析器
 */
public class PackageParser {
    private static final Logger log = LoggerFactory.getLogger(PackageParser.class);

    /**
     * 解析安装包信息
     * @param file 安装包文件 (APK 或 IPA)
     * @return 包信息
     */
    public static PackageInfo parse(File file) throws IOException {
        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".apk")) {
            return parseApk(file);
        } else if (fileName.endsWith(".ipa")) {
            return parseIpa(file);
        } else {
            throw new IOException("不支持的文件类型: " + fileName);
        }
    }

    /**
     * 解析 APK 文件
     */
    private static PackageInfo parseApk(File file) throws IOException {
        try (ApkFile apkFile = new ApkFile(file)) {
            ApkMeta meta = apkFile.getApkMeta();
            return new PackageInfo(
                    meta.getPackageName(),
                    meta.getVersionName(),
                    String.valueOf(meta.getVersionCode())
            );
        }
    }

    /**
     * 解析 IPA 文件
     */
    private static PackageInfo parseIpa(File file) throws IOException {
        try (ZipFile zipFile = new ZipFile(file)) {
            ZipEntry plistEntry = findInfoPlist(zipFile);
            try (InputStream is = zipFile.getInputStream(plistEntry)) {
                NSDictionary plist = (NSDictionary) PropertyListParser.parse(is);

                String bundleId = getString(plist, "CFBundleIdentifier");
                String versionName = getString(plist, "CFBundleShortVersionString");
                String versionCode = getString(plist, "CFBundleVersion");

                return new PackageInfo(bundleId, versionName, versionCode);
            } catch (Exception e) {
                throw new IOException("解析 Info.plist 失败: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 在 IPA 中查找 Info.plist
     */
    private static ZipEntry findInfoPlist(ZipFile zipFile) throws IOException {
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            // 匹配 Payload/xxx.app/Info.plist
            if (entry.getName().matches("Payload/[^/]+\\.app/Info\\.plist")) {
                return entry;
            }
        }
        throw new IOException("无效的 IPA 文件：未找到 Info.plist");
    }

    private static String getString(NSDictionary dict, String key) {
        Object value = dict.get(key);
        return value != null ? value.toString() : "";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=PackageParserTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/cloud/sonic/agent/tools/file/PackageParser.java \
        src/test/java/org/cloud/sonic/agent/tools/file/PackageParserTest.java
git commit -m "feat: add PackageParser for APK and IPA metadata extraction"
```

---

## Task 5: Create DownloadCache

**Files:**
- Create: `src/main/java/org/cloud/sonic/agent/tools/file/DownloadCache.java`
- Create: `src/test/java/org/cloud/sonic/agent/tools/file/DownloadCacheTest.java`

- [ ] **Step 1: Write tests for DownloadCache**

```java
package org.cloud.sonic.agent.tools.file;

import com.alibaba.fastjson.JSON;
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=DownloadCacheTest -q`
Expected: Compilation error

- [ ] **Step 3: Create DownloadCache class**

```java
package org.cloud.sonic.agent.tools.file;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 下载缓存管理
 */
public class DownloadCache {
    private static final Logger log = LoggerFactory.getLogger(DownloadCache.class);

    private static final long CACHE_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000L; // 7天
    private static final long MAX_CACHE_SIZE_BYTES = 5L * 1024 * 1024 * 1024; // 5GB

    private static File cacheDirectory = new File("test-output" + File.separator + "package-cache");
    private static final ReentrantReadWriteLock indexLock = new ReentrantReadWriteLock();
    private static final ConcurrentHashMap<String, CompletableFuture<File>> downloadingUrls = new ConcurrentHashMap<>();

    /**
     * 带缓存的下载
     */
    public static File downloadWithCache(String url) throws IOException {
        String urlHash = md5(url);

        // 1. 读锁检查缓存
        indexLock.readLock().lock();
        try {
            JSONObject entry = getCacheEntry(urlHash);
            if (entry != null && !isExpired(entry.getLongValue("downloadedAt"))) {
                File cachedFile = new File(entry.getString("filePath"));
                if (cachedFile.exists() && cachedFile.canRead() && cachedFile.length() > 0) {
                    updateLastAccessedAt(urlHash);
                    log.info("缓存命中: {}", url);
                    return cachedFile;
                }
            }
        } finally {
            indexLock.readLock().unlock();
        }

        // 2. 防止同一 URL 重复下载
        CompletableFuture<File> future = downloadingUrls.computeIfAbsent(urlHash,
                k -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return doDownload(url, urlHash);
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                }));

        try {
            return future.get();
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("下载失败: " + url, e);
        } finally {
            downloadingUrls.remove(urlHash);
        }
    }

    private static File doDownload(String url, String urlHash) throws IOException {
        ensureCacheDirectory();

        String extension = getExtension(url);
        File downloaded = new File(cacheDirectory, urlHash + extension);

        log.info("开始下载: {}", url);
        downloadFile(url, downloaded);
        log.info("下载完成: {}", downloaded.getAbsolutePath());

        // 写锁更新索引
        indexLock.writeLock().lock();
        try {
            updateCacheIndex(urlHash, url, downloaded);
        } finally {
            indexLock.writeLock().unlock();
        }

        // 异步清理
        triggerAsyncCleanup();

        return downloaded;
    }

    private static void downloadFile(String urlString, File target) throws IOException {
        URL url = new URL(urlString);
        URLConnection con = url.openConnection();
        con.setConnectTimeout(30000);
        con.setReadTimeout(300000);

        try (InputStream is = con.getInputStream();
             FileOutputStream os = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
        }
    }

    private static void ensureCacheDirectory() {
        if (!cacheDirectory.exists()) {
            cacheDirectory.mkdirs();
        }
    }

    private static String getExtension(String url) {
        int lastDot = url.lastIndexOf(".");
        int lastSlash = url.lastIndexOf("/");
        if (lastDot > lastSlash && lastDot < url.length() - 1) {
            String ext = url.substring(lastDot);
            // 去掉查询参数
            int queryStart = ext.indexOf("?");
            if (queryStart > 0) {
                ext = ext.substring(0, queryStart);
            }
            return ext;
        }
        return "";
    }

    // === 索引管理 ===

    static JSONObject loadCacheIndex() {
        Path indexPath = cacheDirectory.toPath().resolve("cache-index.json");
        if (!Files.exists(indexPath)) {
            return new JSONObject();
        }
        try {
            String content = Files.readString(indexPath, StandardCharsets.UTF_8);
            return JSON.parseObject(content);
        } catch (Exception e) {
            log.warn("缓存索引文件损坏，将重新创建: {}", e.getMessage());
            try {
                Files.deleteIfExists(indexPath);
            } catch (IOException ignored) {}
            return new JSONObject();
        }
    }

    private static void saveCacheIndex(JSONObject index) {
        Path indexPath = cacheDirectory.toPath().resolve("cache-index.json");
        try {
            Files.writeString(indexPath, index.toJSONString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("保存缓存索引失败: {}", e.getMessage());
        }
    }

    private static JSONObject getCacheEntry(String urlHash) {
        return loadCacheIndex().getJSONObject(urlHash);
    }

    private static void updateCacheIndex(String urlHash, String url, File file) {
        JSONObject index = loadCacheIndex();
        JSONObject entry = new JSONObject();
        entry.put("url", url);
        entry.put("filePath", file.getAbsolutePath());
        entry.put("downloadedAt", System.currentTimeMillis());
        entry.put("lastAccessedAt", System.currentTimeMillis());
        index.put(urlHash, entry);
        saveCacheIndex(index);
    }

    private static void updateLastAccessedAt(String urlHash) {
        indexLock.writeLock().lock();
        try {
            JSONObject index = loadCacheIndex();
            JSONObject entry = index.getJSONObject(urlHash);
            if (entry != null) {
                entry.put("lastAccessedAt", System.currentTimeMillis());
                saveCacheIndex(index);
            }
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    // === 清理 ===

    static boolean isExpired(long downloadedAt) {
        return System.currentTimeMillis() - downloadedAt > CACHE_EXPIRY_MS;
    }

    private static void triggerAsyncCleanup() {
        CompletableFuture.runAsync(DownloadCache::cleanCache)
                .exceptionally(e -> {
                    log.warn("缓存清理失败: {}", e.getMessage());
                    return null;
                });
    }

    /**
     * 清理缓存
     */
    public static void cleanCache() {
        indexLock.writeLock().lock();
        try {
            JSONObject index = loadCacheIndex();
            List<String> toRemove = new ArrayList<>();

            // 清理过期缓存
            for (String key : index.keySet()) {
                JSONObject entry = index.getJSONObject(key);
                if (isExpired(entry.getLongValue("downloadedAt"))) {
                    toRemove.add(key);
                    deleteFile(entry.getString("filePath"));
                }
            }

            for (String key : toRemove) {
                index.remove(key);
            }

            // LRU 清理（如果超过大小限制）
            long totalSize = calculateCacheSize();
            if (totalSize > MAX_CACHE_SIZE_BYTES) {
                cleanByLRU(index, totalSize - MAX_CACHE_SIZE_BYTES);
            }

            saveCacheIndex(index);
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    private static long calculateCacheSize() {
        if (!cacheDirectory.exists()) return 0;
        long size = 0;
        File[] files = cacheDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && !file.getName().endsWith(".json")) {
                    size += file.length();
                }
            }
        }
        return size;
    }

    private static void cleanByLRU(JSONObject index, long bytesToFree) {
        // 按 lastAccessedAt 排序
        List<Map.Entry<String, Object>> entries = new ArrayList<>(index.entrySet());
        entries.sort((a, b) -> {
            JSONObject ea = (JSONObject) a.getValue();
            JSONObject eb = (JSONObject) b.getValue();
            return Long.compare(ea.getLongValue("lastAccessedAt"), eb.getLongValue("lastAccessedAt"));
        });

        long freed = 0;
        for (Map.Entry<String, Object> entry : entries) {
            if (freed >= bytesToFree) break;

            JSONObject e = (JSONObject) entry.getValue();
            File file = new File(e.getString("filePath"));
            if (file.exists()) {
                freed += file.length();
                deleteFile(file.getAbsolutePath());
            }
            index.remove(entry.getKey());
        }
    }

    private static void deleteFile(String path) {
        try {
            Files.deleteIfExists(Path.of(path));
        } catch (IOException e) {
            log.warn("删除文件失败: {}", path);
        }
    }

    // === 工具方法 ===

    public static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 计算文件 MD5
     */
    public static String fileMd5(File file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (InputStream is = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    md.update(buffer, 0, len);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    // === 测试支持 ===

    static void setCacheDirectory(File dir) {
        cacheDirectory = dir;
    }

    static void resetForTesting() {
        cacheDirectory = new File("test-output" + File.separator + "package-cache");
        downloadingUrls.clear();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=DownloadCacheTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/cloud/sonic/agent/tools/file/DownloadCache.java \
        src/test/java/org/cloud/sonic/agent/tools/file/DownloadCacheTest.java
git commit -m "feat: add DownloadCache with URL-based caching and LRU cleanup"
```

---

## Task 6: Create InstallRecords

**Files:**
- Create: `src/main/java/org/cloud/sonic/agent/tools/file/InstallRecords.java`
- Create: `src/test/java/org/cloud/sonic/agent/tools/file/InstallRecordsTest.java`

- [ ] **Step 1: Write tests for InstallRecords**

```java
package org.cloud.sonic.agent.tools.file;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=InstallRecordsTest -q`
Expected: Compilation error

- [ ] **Step 3: Create InstallRecords class**

```java
package org.cloud.sonic.agent.tools.file;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 安装记录管理
 */
public class InstallRecords {
    private static final Logger log = LoggerFactory.getLogger(InstallRecords.class);

    private static final long RECORD_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000L; // 7天
    private static File recordsFile = new File("test-output" + File.separator + "install-records.json");
    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * 检查是否已安装相同的包
     */
    public static boolean isInstalled(String deviceId, String packageName, String fileHash) {
        lock.readLock().lock();
        try {
            String key = makeKey(deviceId, packageName);
            JSONObject records = loadRecords();
            JSONObject record = records.getJSONObject(key);

            if (record == null) {
                return false;
            }

            // 检查是否过期
            if (isExpired(record.getLongValue("installedAt"))) {
                return false;
            }

            // 检查 hash 是否相同
            return fileHash.equals(record.getString("hash"));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 记录安装信息
     */
    public static void recordInstall(String deviceId, String packageName, String fileHash) {
        lock.writeLock().lock();
        try {
            String key = makeKey(deviceId, packageName);
            JSONObject records = loadRecords();

            JSONObject record = new JSONObject();
            record.put("hash", fileHash);
            record.put("installedAt", System.currentTimeMillis());

            records.put(key, record);
            saveRecords(records);

            // 异步清理过期记录
            cleanExpiredRecordsAsync();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 清理过期记录
     */
    public static void cleanExpiredRecords() {
        lock.writeLock().lock();
        try {
            JSONObject records = loadRecords();
            List<String> toRemove = new ArrayList<>();

            for (String key : records.keySet()) {
                JSONObject record = records.getJSONObject(key);
                if (isExpired(record.getLongValue("installedAt"))) {
                    toRemove.add(key);
                }
            }

            for (String key : toRemove) {
                records.remove(key);
            }

            if (!toRemove.isEmpty()) {
                saveRecords(records);
                log.info("清理了 {} 条过期安装记录", toRemove.size());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static void cleanExpiredRecordsAsync() {
        new Thread(InstallRecords::cleanExpiredRecords).start();
    }

    private static String makeKey(String deviceId, String packageName) {
        return deviceId + ":" + packageName;
    }

    private static boolean isExpired(long installedAt) {
        return System.currentTimeMillis() - installedAt > RECORD_EXPIRY_MS;
    }

    private static JSONObject loadRecords() {
        if (!recordsFile.exists()) {
            return new JSONObject();
        }
        try {
            String content = Files.readString(recordsFile.toPath(), StandardCharsets.UTF_8);
            return JSON.parseObject(content);
        } catch (Exception e) {
            log.warn("安装记录文件损坏，将重新创建: {}", e.getMessage());
            try {
                Files.deleteIfExists(recordsFile.toPath());
            } catch (IOException ignored) {}
            return new JSONObject();
        }
    }

    private static void saveRecords(JSONObject records) {
        try {
            File parent = recordsFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            Files.writeString(recordsFile.toPath(), records.toJSONString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("保存安装记录失败: {}", e.getMessage());
        }
    }

    // === 测试支持 ===

    static void setRecordsFile(File file) {
        recordsFile = file;
    }

    static void resetForTesting() {
        recordsFile = new File("test-output" + File.separator + "install-records.json");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=InstallRecordsTest -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/cloud/sonic/agent/tools/file/InstallRecords.java \
        src/test/java/org/cloud/sonic/agent/tools/file/InstallRecordsTest.java
git commit -m "feat: add InstallRecords for tracking installed packages per device"
```

---

## Task 7: Create PackageManager Facade

**Files:**
- Create: `src/main/java/org/cloud/sonic/agent/tools/file/PackageManager.java`

- [ ] **Step 1: Create PackageManager class**

```java
package org.cloud.sonic.agent.tools.file;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.InstallException;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceBridgeTool;
import org.cloud.sonic.agent.bridge.ios.SibTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * 包管理器 - 统一接口
 */
public class PackageManager {
    private static final Logger log = LoggerFactory.getLogger(PackageManager.class);

    /**
     * 带缓存的下载
     */
    public static File downloadWithCache(String url) throws IOException {
        return DownloadCache.downloadWithCache(url);
    }

    /**
     * Android 智能安装
     */
    public static InstallResult installIfNeeded(IDevice iDevice, File apkFile, boolean force) {
        try {
            // 解析 APK
            PackageInfo packageInfo = PackageParser.parse(apkFile);
            String packageName = packageInfo.getPackageName();
            String fileHash = DownloadCache.fileMd5(apkFile);
            String deviceId = iDevice.getSerialNumber();

            // 检查是否需要安装
            if (!force && InstallRecords.isInstalled(deviceId, packageName, fileHash)) {
                log.info("APK 已安装且未变化，跳过: {} on {}", packageName, deviceId);
                return InstallResult.skipped("相同版本已安装: " + packageName);
            }

            // 执行安装
            log.info("开始安装 APK: {} on {}", packageName, deviceId);
            AndroidDeviceBridgeTool.install(iDevice, apkFile.getAbsolutePath());

            // 记录安装
            InstallRecords.recordInstall(deviceId, packageName, fileHash);
            log.info("APK 安装成功: {} on {}", packageName, deviceId);

            return InstallResult.installed();
        } catch (IOException e) {
            log.error("APK 解析失败: {}", e.getMessage());
            return InstallResult.failed("APK 解析失败: " + e.getMessage(), e);
        } catch (InstallException e) {
            log.error("APK 安装失败: {}", e.getMessage());
            return InstallResult.failed("APK 安装失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("安装异常: {}", e.getMessage());
            return InstallResult.failed("安装异常: " + e.getMessage(), e);
        }
    }

    /**
     * iOS 智能安装
     */
    public static InstallResult installIfNeeded(String udId, File ipaFile, boolean force) {
        try {
            // 解析 IPA
            PackageInfo packageInfo = PackageParser.parse(ipaFile);
            String bundleId = packageInfo.getPackageName();
            String fileHash = DownloadCache.fileMd5(ipaFile);

            // 检查是否需要安装
            if (!force && InstallRecords.isInstalled(udId, bundleId, fileHash)) {
                log.info("IPA 已安装且未变化，跳过: {} on {}", bundleId, udId);
                return InstallResult.skipped("相同版本已安装: " + bundleId);
            }

            // 执行安装
            log.info("开始安装 IPA: {} on {}", bundleId, udId);
            SibTool.install(udId, ipaFile.getAbsolutePath());

            // 记录安装
            InstallRecords.recordInstall(udId, bundleId, fileHash);
            log.info("IPA 安装成功: {} on {}", bundleId, udId);

            return InstallResult.installed();
        } catch (IOException e) {
            log.error("IPA 解析失败: {}", e.getMessage());
            return InstallResult.failed("IPA 解析失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("安装异常: {}", e.getMessage());
            return InstallResult.failed("安装异常: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/cloud/sonic/agent/tools/file/PackageManager.java
git commit -m "feat: add PackageManager facade for cached download and smart install"
```

---

## Task 8: Integrate with AndroidWSServer

**Files:**
- Modify: `src/main/java/org/cloud/sonic/agent/websockets/AndroidWSServer.java`

- [ ] **Step 1: Add import statements**

Add after existing imports (around line 30):

```java
import org.cloud.sonic.agent.tools.file.PackageManager;
import org.cloud.sonic.agent.tools.file.InstallResult;
```

- [ ] **Step 2: Update APK install handler**

Find the `case "install"` block (around line 321-336). The code runs in a thread pool. Update the inner logic:

```java
case "install" -> AndroidDeviceThreadPool.cachedThreadPool.execute(() -> {
    JSONObject result = new JSONObject();
    result.put("msg", "installFinish");
    try {
        File localFile = new File(msg.getString("apk"));
        if (msg.getString("apk").contains("http")) {
            localFile = PackageManager.downloadWithCache(msg.getString("apk"));
        }
        boolean force = msg.getBooleanValue("forceInstall", false);
        InstallResult installResult = PackageManager.installIfNeeded(iDevice, localFile, force);
        if (installResult.getStatus() == InstallResult.Status.FAILED) {
            throw new Exception(installResult.getMessage());
        }
        result.put("status", "success");
        if (installResult.getStatus() == InstallResult.Status.SKIPPED) {
            result.put("skipped", true);
            result.put("reason", installResult.getMessage());
        }
    } catch (Exception e) {
        result.put("status", "fail");
        e.printStackTrace();
    }
    BytesTool.sendText(session, result.toJSONString());
});
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/cloud/sonic/agent/websockets/AndroidWSServer.java
git commit -m "feat: integrate PackageManager with AndroidWSServer for smart APK install"
```

---

## Task 9: Integrate with IOSWSServer

**Files:**
- Modify: `src/main/java/org/cloud/sonic/agent/websockets/IOSWSServer.java`

- [ ] **Step 1: Add import statements**

Add after existing imports:

```java
import org.cloud.sonic.agent.tools.file.PackageManager;
import org.cloud.sonic.agent.tools.file.InstallResult;
```

- [ ] **Step 2: Update IPA install handler**

Find the `case "install"` block (around line 557-569). Update the full block:

```java
case "install" -> {
    JSONObject result = new JSONObject();
    result.put("msg", "installFinish");
    try {
        File localFile = new File(msg.getString("ipa"));
        if (msg.getString("ipa").contains("http")) {
            localFile = PackageManager.downloadWithCache(msg.getString("ipa"));
        }
        boolean force = msg.getBooleanValue("forceInstall", false);
        InstallResult installResult = PackageManager.installIfNeeded(udId, localFile, force);
        if (installResult.getStatus() == InstallResult.Status.FAILED) {
            throw new Exception(installResult.getMessage());
        }
        result.put("status", "success");
        if (installResult.getStatus() == InstallResult.Status.SKIPPED) {
            result.put("skipped", true);
            result.put("reason", installResult.getMessage());
        }
    } catch (Exception e) {
        result.put("status", "fail");
        e.printStackTrace();
    }
    sendText(session, result.toJSONString());
}
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/cloud/sonic/agent/websockets/IOSWSServer.java
git commit -m "feat: integrate PackageManager with IOSWSServer for smart IPA install"
```

---

## Task 10: Integrate with AndroidStepHandler

**Files:**
- Modify: `src/main/java/org/cloud/sonic/agent/tests/handlers/AndroidStepHandler.java`

- [ ] **Step 1: Add import statements**

Add after existing imports:

```java
import org.cloud.sonic.agent.tools.file.PackageManager;
import org.cloud.sonic.agent.tools.file.InstallResult;
```

- [ ] **Step 2: Update install method**

Find the install handling code (around line 369-375) and update:

```java
// 原代码:
// File localFile = new File(path);
// if (path.contains("http")) {
//     localFile = DownloadTool.download(path);
// }
// AndroidDeviceBridgeTool.install(iDevice, localFile.getAbsolutePath());

// 新代码:
File localFile = new File(path);
if (path.contains("http")) {
    localFile = PackageManager.downloadWithCache(path);
}
InstallResult installResult = PackageManager.installIfNeeded(iDevice, localFile, false);
if (installResult.getStatus() == InstallResult.Status.SKIPPED) {
    log.sendStepLog(StepType.INFO, "", "相同版本已安装，跳过安装");
} else if (installResult.getStatus() == InstallResult.Status.FAILED) {
    throw new Exception(installResult.getMessage());
}
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/cloud/sonic/agent/tests/handlers/AndroidStepHandler.java
git commit -m "feat: integrate PackageManager with AndroidStepHandler"
```

---

## Task 11: Integrate with IOSStepHandler

**Files:**
- Modify: `src/main/java/org/cloud/sonic/agent/tests/handlers/IOSStepHandler.java`

- [ ] **Step 1: Add import statements**

Add after existing imports:

```java
import org.cloud.sonic.agent.tools.file.PackageManager;
import org.cloud.sonic.agent.tools.file.InstallResult;
```

- [ ] **Step 2: Update install method**

Find the install handling code (around line 325-331) and update:

```java
// 原代码:
// File localFile = new File(path);
// if (path.contains("http")) {
//     localFile = DownloadTool.download(path);
// }
// SibTool.install(udId, localFile.getAbsolutePath());

// 新代码:
File localFile = new File(path);
if (path.contains("http")) {
    localFile = PackageManager.downloadWithCache(path);
}
InstallResult installResult = PackageManager.installIfNeeded(udId, localFile, false);
if (installResult.getStatus() == InstallResult.Status.SKIPPED) {
    log.sendStepLog(StepType.INFO, "", "相同版本已安装，跳过安装");
} else if (installResult.getStatus() == InstallResult.Status.FAILED) {
    throw new Exception(installResult.getMessage());
}
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/cloud/sonic/agent/tests/handlers/IOSStepHandler.java
git commit -m "feat: integrate PackageManager with IOSStepHandler"
```

---

## Task 12: Final Verification

- [ ] **Step 1: Run all tests**

Run: `mvn test -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: Build complete project**

Run: `mvn package -DskipTests -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Create summary commit**

```bash
git log --oneline -12
```

Verify all commits are in place.
