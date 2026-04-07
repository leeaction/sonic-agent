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
