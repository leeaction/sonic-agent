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

## 兼容性考虑

1. **保留原有 DownloadTool**: 不修改原有 `DownloadTool.download()` 方法，新增 `PackageManager` 作为上层封装
2. **渐进式迁移**: 调用方可以逐步迁移到新接口
3. **force 参数**: 提供强制重装选项，应对特殊场景

## 测试要点

1. 下载缓存命中/未命中场景
2. 缓存过期自动清理
3. 安装记录命中/未命中场景
4. force=true 强制重装
5. APK/IPA 解析正确性
6. 并发下载/安装场景
7. 异常处理（网络错误、文件损坏等）
