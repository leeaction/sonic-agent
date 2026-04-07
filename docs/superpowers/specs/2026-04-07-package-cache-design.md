# 安装包下载缓存与智能安装设计文档

## 概述

优化 sonic-agent 的安装包下载和安装流程，避免重复下载相同的安装包，并检测设备上是否已安装相同的包以跳过不必要的安装操作。

## 背景

当前 `DownloadTool.download()` 方法每次调用都会重新下载文件，即使是相同的 URL。在测试场景中，同一个安装包可能被多次下载和安装，造成不必要的网络开销和时间浪费。

## 目标

1. **下载缓存**：相同 URL 的安装包只下载一次，缓存 7 天
2. **智能安装**：检测设备上是否已安装相同的包，相同则跳过安装
3. **支持双平台**：Android (APK) 和 iOS (IPA)
4. **强制重装**：提供 force 参数支持强制重新安装

## 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        调用方                                    │
│  (AndroidWSServer, IOSWSServer, StepHandler 等)                 │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                   PackageManager (新增)                          │
│  - downloadWithCache(url): File                                 │
│  - installIfNeeded(device, file, force): InstallResult          │
└───────────────────────────┬─────────────────────────────────────┘
                            │
        ┌───────────────────┼───────────────────┐
        ▼                   ▼                   ▼
┌──────────────┐   ┌──────────────────┐   ┌──────────────────┐
│DownloadCache │   │ PackageParser    │   │ InstallRecords   │
│  URL→文件    │   │ - ApkParser      │   │ 设备+包名→hash   │
│  7天过期     │   │ - IpaParser      │   │ 7天过期          │
└──────────────┘   └──────────────────┘   └──────────────────┘
```

## 新增依赖

```xml
<!-- APK 解析 -->
<dependency>
    <groupId>net.dongliu</groupId>
    <artifactId>apk-parser</artifactId>
    <version>2.6.10</version>
</dependency>

<!-- IPA plist 解析 -->
<dependency>
    <groupId>com.googlecode.plist</groupId>
    <artifactId>dd-plist</artifactId>
    <version>1.28</version>
</dependency>
```

## 文件结构

```
test-output/
├── package-cache/              # 下载缓存目录
│   ├── cache-index.json        # 缓存索引
│   └── <url-hash>.apk/ipa      # 缓存的安装包
│
└── install-records.json        # 安装记录
```

### cache-index.json 结构

```json
{
  "<url-md5>": {
    "url": "https://example.com/app.apk",
    "filePath": "package-cache/abc123.apk",
    "downloadedAt": 1712345678000
  }
}
```

### install-records.json 结构

```json
{
  "<deviceId>:<packageName>": {
    "hash": "<file-md5>",
    "installedAt": 1712345678000
  }
}
```

## 核心组件设计

### 1. DownloadCache

负责下载缓存管理。

**位置**: `org.cloud.sonic.agent.tools.file.DownloadCache`

**职责**:
- 基于 URL 的 MD5 作为缓存 key
- 缓存有效期 7 天
- 读取/写入时自动清理过期缓存

**主要方法**:
```java
public class DownloadCache {
    private static final long CACHE_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000L; // 7天

    /**
     * 带缓存的下载
     * @param url 下载地址
     * @return 本地文件
     */
    public static File downloadWithCache(String url) throws IOException;

    /**
     * 清理过期缓存
     */
    public static void cleanExpiredCache();
}
```

### 2. PackageParser

负责解析安装包信息。

**位置**: `org.cloud.sonic.agent.tools.file.PackageParser`

**职责**:
- 解析 APK 文件（使用 apk-parser 库）
- 解析 IPA 文件（使用 dd-plist 库 + Java ZIP API）

**主要方法**:
```java
public class PackageParser {

    /**
     * 解析安装包信息
     * @param file 安装包文件
     * @return 包信息（packageName/bundleId）
     */
    public static PackageInfo parse(File file) throws IOException;
}

public class PackageInfo {
    private String packageName;  // Android: packageName, iOS: bundleId
    private String versionName;
    private String versionCode;
}
```

**APK 解析实现**:
```java
try (ApkFile apkFile = new ApkFile(file)) {
    ApkMeta meta = apkFile.getApkMeta();
    return new PackageInfo(
        meta.getPackageName(),
        meta.getVersionName(),
        String.valueOf(meta.getVersionCode())
    );
}
```

**IPA 解析实现**:
```java
try (ZipFile zipFile = new ZipFile(file)) {
    // 找到 Payload/xxx.app/Info.plist
    ZipEntry plistEntry = findInfoPlist(zipFile);
    NSDictionary plist = (NSDictionary) PropertyListParser.parse(
        zipFile.getInputStream(plistEntry)
    );
    return new PackageInfo(
        plist.get("CFBundleIdentifier").toString(),
        plist.get("CFBundleShortVersionString").toString(),
        plist.get("CFBundleVersion").toString()
    );
}
```

### 3. InstallRecords

负责安装记录管理。

**位置**: `org.cloud.sonic.agent.tools.file.InstallRecords`

**职责**:
- 记录每个设备上安装的包的文件 hash
- 记录有效期 7 天
- 读取/写入时自动清理过期记录

**主要方法**:
```java
public class InstallRecords {
    private static final long RECORD_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000L; // 7天

    /**
     * 检查是否已安装相同的包
     * @param deviceId 设备ID
     * @param packageName 包名
     * @param fileHash 文件MD5
     * @return true 表示已安装相同的包
     */
    public static boolean isInstalled(String deviceId, String packageName, String fileHash);

    /**
     * 记录安装信息
     */
    public static void recordInstall(String deviceId, String packageName, String fileHash);

    /**
     * 清理过期记录
     */
    public static void cleanExpiredRecords();
}
```

### 4. PackageManager

对外统一接口。

**位置**: `org.cloud.sonic.agent.tools.file.PackageManager`

**主要方法**:
```java
public class PackageManager {

    /**
     * 带缓存的下载
     */
    public static File downloadWithCache(String url) throws IOException {
        return DownloadCache.downloadWithCache(url);
    }

    /**
     * Android 智能安装
     * @return InstallResult: INSTALLED / SKIPPED / FAILED
     */
    public static InstallResult installIfNeeded(
            IDevice iDevice,
            File apkFile,
            boolean force) throws Exception;

    /**
     * iOS 智能安装
     */
    public static InstallResult installIfNeeded(
            String udId,
            File ipaFile,
            boolean force) throws Exception;
}

public enum InstallResult {
    INSTALLED,  // 已安装
    SKIPPED,    // 跳过（已存在相同版本）
    FAILED      // 安装失败
}
```

## 核心流程

### 下载流程

```
downloadWithCache(url)
    │
    ▼
计算 URL 的 MD5 作为缓存 key
    │
    ▼
读取 cache-index.json
    │
    ▼
┌─────────────────────────────────────┐
│ 缓存存在且未过期?                    │
└─────────────────────────────────────┘
    │                    │
   是                   否
    │                    │
    ▼                    ▼
返回缓存文件         下载文件到 package-cache/
                         │
                         ▼
                    更新 cache-index.json
                         │
                         ▼
                    清理过期缓存
                         │
                         ▼
                    返回下载的文件
```

### 安装流程

```
installIfNeeded(device, file, force)
    │
    ▼
解析安装包 → 获取 packageName/bundleId
    │
    ▼
计算文件 MD5
    │
    ▼
读取 install-records.json
    │
    ▼
┌─────────────────────────────────────┐
│ force=true?                         │
└─────────────────────────────────────┘
    │                    │
   是                   否
    │                    │
    │                    ▼
    │          ┌─────────────────────────────────────┐
    │          │ 记录存在且 hash 相同且未过期?        │
    │          └─────────────────────────────────────┘
    │                    │                    │
    │                   是                   否
    │                    │                    │
    │                    ▼                    │
    │              返回 SKIPPED               │
    │                                         │
    └─────────────────┬───────────────────────┘
                      │
                      ▼
               执行实际安装
                      │
                      ▼
              ┌───────────────┐
              │ 安装成功?      │
              └───────────────┘
                 │         │
                是        否
                 │         │
                 ▼         ▼
        更新 install-records.json
        清理过期记录
                 │
                 ▼
           返回 INSTALLED    返回 FAILED
```

## 调用方式变化

### 原调用方式

```java
// Android
File localFile = DownloadTool.download(msg.getString("apk"));
AndroidDeviceBridgeTool.install(iDevice, localFile.getAbsolutePath());

// iOS
File localFile = DownloadTool.download(msg.getString("ipa"));
SibTool.install(udId, localFile.getAbsolutePath());
```

### 新调用方式

```java
// Android
File localFile = PackageManager.downloadWithCache(msg.getString("apk"));
InstallResult result = PackageManager.installIfNeeded(iDevice, localFile, false);
if (result == InstallResult.SKIPPED) {
    log.info("相同版本已安装，跳过");
}

// iOS
File localFile = PackageManager.downloadWithCache(msg.getString("ipa"));
InstallResult result = PackageManager.installIfNeeded(udId, localFile, false);
if (result == InstallResult.SKIPPED) {
    log.info("相同版本已安装，跳过");
}
```

## 需要修改的文件

1. **pom.xml** - 添加 apk-parser 和 dd-plist 依赖
2. **新增文件**:
   - `org.cloud.sonic.agent.tools.file.DownloadCache`
   - `org.cloud.sonic.agent.tools.file.PackageParser`
   - `org.cloud.sonic.agent.tools.file.PackageInfo`
   - `org.cloud.sonic.agent.tools.file.InstallRecords`
   - `org.cloud.sonic.agent.tools.file.PackageManager`
   - `org.cloud.sonic.agent.tools.file.InstallResult`
3. **修改调用方**:
   - `AndroidWSServer.java` - 安装 APK 的地方
   - `IOSWSServer.java` - 安装 IPA 的地方
   - `AndroidStepHandler.java` - 测试步骤中安装 APK
   - `IOSStepHandler.java` - 测试步骤中安装 IPA

## 并发与线程安全

### JSON 文件访问

由于多个测试线程可能同时访问 cache-index.json 和 install-records.json，需要保证线程安全：

```java
public class DownloadCache {
    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public static File downloadWithCache(String url) throws IOException {
        lock.writeLock().lock();
        try {
            // 读写 cache-index.json
        } finally {
            lock.writeLock().unlock();
        }
    }
}
```

- 使用 `ReentrantReadWriteLock` 保护 JSON 文件读写
- 读操作使用读锁，写操作使用写锁
- 同样的机制应用于 InstallRecords

## 错误处理

### 损坏的缓存文件

如果 cache-index.json 或 install-records.json 损坏（无效 JSON）：

1. 捕获 JSON 解析异常
2. 记录警告日志
3. 删除损坏的文件
4. 创建新的空索引文件
5. 继续正常操作（视为缓存未命中）

```java
private static JSONObject loadCacheIndex() {
    try {
        String content = Files.readString(CACHE_INDEX_PATH);
        return JSON.parseObject(content);
    } catch (Exception e) {
        log.warn("缓存索引文件损坏，将重新创建: {}", e.getMessage());
        try {
            Files.deleteIfExists(CACHE_INDEX_PATH);
        } catch (IOException ignored) {}
        return new JSONObject();
    }
}
```

### 缓存文件完整性验证

返回缓存文件前，验证文件存在且可读：

```java
File cachedFile = new File(entry.getString("filePath"));
if (!cachedFile.exists() || !cachedFile.canRead() || cachedFile.length() == 0) {
    // 文件损坏或丢失，删除缓存记录，重新下载
    removeCacheEntry(urlHash);
    return downloadAndCache(url);
}
```

### IPA 解析错误处理

当 IPA 文件中找不到 Info.plist 时：

```java
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
```

### InstallResult.FAILED 处理

```java
public class InstallResult {
    private final Status status;
    private final String errorMessage;  // FAILED 时的错误信息
    private final Exception exception;   // 原始异常（可选）

    public enum Status {
        INSTALLED, SKIPPED, FAILED
    }
}
```

调用方可以通过 `result.getErrorMessage()` 获取失败原因。

## 磁盘空间管理

### 缓存大小限制

除了 7 天过期策略，增加最大缓存大小限制：

```java
public class DownloadCache {
    private static final long MAX_CACHE_SIZE_BYTES = 5L * 1024 * 1024 * 1024; // 5GB

    /**
     * 清理缓存，优先清理：
     * 1. 已过期的缓存（超过7天）
     * 2. 如果仍超过限制，按 LRU 清理最久未使用的缓存
     */
    public static void cleanCache() {
        // 先清理过期缓存
        cleanExpiredCache();

        // 检查总大小
        long totalSize = calculateCacheSize();
        if (totalSize > MAX_CACHE_SIZE_BYTES) {
            // 按最后访问时间排序，删除最旧的直到低于限制
            cleanByLRU(totalSize - MAX_CACHE_SIZE_BYTES);
        }
    }
}
```

### 清理时机

- **异步清理**：缓存清理在后台线程执行，不阻塞主操作
- **触发时机**：每次成功下载新文件后触发异步清理

```java
public static File downloadWithCache(String url) throws IOException {
    // ... 下载逻辑 ...

    // 异步清理，不阻塞返回
    CompletableFuture.runAsync(DownloadCache::cleanCache);

    return downloadedFile;
}
```

## 旧文件迁移

现有的 `test-output/download-*.apk` 文件处理策略：

1. **不自动迁移**：这些是临时文件，不纳入新缓存系统
2. **不自动删除**：避免误删用户可能需要的文件
3. **文档说明**：在部署文档中建议用户可以手动清理旧的 download-* 文件

## WebSocket 协议变化

### force 参数传递

在 WebSocket 消息中增加可选的 `forceInstall` 字段：

**Android 安装消息**：
```json
{
  "msg": "install",
  "apk": "https://example.com/app.apk",
  "forceInstall": true  // 可选，默认 false
}
```

**iOS 安装消息**：
```json
{
  "msg": "install",
  "ipa": "https://example.com/app.ipa",
  "forceInstall": true  // 可选，默认 false
}
```

**处理逻辑**：
```java
boolean force = msg.getBooleanValue("forceInstall", false);
InstallResult result = PackageManager.installIfNeeded(iDevice, localFile, force);
```

## 可变 URL 处理

对于指向可变内容的 URL（如 `/latest.apk`）：

1. **默认行为**：基于 URL 缓存，可能返回旧版本
2. **解决方案**：调用方使用 `forceInstall: true` 强制重新下载和安装
3. **建议**：服务端 URL 应包含版本号或 hash（如 `/app-v1.2.3.apk` 或 `/app-abc123.apk`）

## 兼容性考虑

1. **保留原有 DownloadTool**: 不修改原有 `DownloadTool.download()` 方法，新增 `PackageManager` 作为上层封装
2. **渐进式迁移**: 调用方可以逐步迁移到新接口
3. **force 参数**: 提供强制重装选项，应对特殊场景
4. **WebSocket 协议向后兼容**: `forceInstall` 是可选字段，不影响现有客户端

## 测试要点

1. 下载缓存命中/未命中场景
2. 缓存过期自动清理
3. 安装记录命中/未命中场景
4. force=true 强制重装
5. APK/IPA 解析正确性
6. **并发下载/安装场景** - 多线程同时下载同一 URL
7. **异常处理** - 网络错误、文件损坏、无效安装包
8. **磁盘空间限制** - 缓存超过 5GB 时的 LRU 清理
9. **损坏文件恢复** - cache-index.json 损坏时的自动恢复
10. **缓存文件完整性** - 缓存文件丢失或损坏时的处理
