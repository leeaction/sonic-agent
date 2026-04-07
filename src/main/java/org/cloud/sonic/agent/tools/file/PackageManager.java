/*
 *   sonic-agent  Agent of Sonic Cloud Real Machine Platform.
 *   Copyright (C) 2022 SonicCloudOrg
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU Affero General Public License as published
 *   by the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU Affero General Public License for more details.
 *
 *   You should have received a copy of the GNU Affero General Public License
 *   along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
        PackageInfo packageInfo;
        String fileHash;

        // 解析 APK
        try {
            packageInfo = PackageParser.parse(apkFile);
        } catch (IOException e) {
            log.error("APK 解析失败，删除损坏文件: {}", e.getMessage());
            DownloadCache.removeCachedFile(apkFile);
            return InstallResult.failed("APK 解析失败（文件可能损坏）: " + e.getMessage(), e);
        }

        // 获取文件 MD5
        try {
            fileHash = DownloadCache.getCachedFileMd5(apkFile);
        } catch (IOException e) {
            log.error("计算文件 MD5 失败: {}", e.getMessage());
            return InstallResult.failed("计算文件 MD5 失败: " + e.getMessage(), e);
        }

        try {
            String packageName = packageInfo.getPackageName();
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
        PackageInfo packageInfo;
        String fileHash;

        // 解析 IPA
        try {
            packageInfo = PackageParser.parse(ipaFile);
        } catch (IOException e) {
            log.error("IPA 解析失败，删除损坏文件: {}", e.getMessage());
            DownloadCache.removeCachedFile(ipaFile);
            return InstallResult.failed("IPA 解析失败（文件可能损坏）: " + e.getMessage(), e);
        }

        // 获取文件 MD5
        try {
            fileHash = DownloadCache.getCachedFileMd5(ipaFile);
        } catch (IOException e) {
            log.error("计算文件 MD5 失败: {}", e.getMessage());
            return InstallResult.failed("计算文件 MD5 失败: " + e.getMessage(), e);
        }

        try {
            String bundleId = packageInfo.getPackageName();

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
        } catch (Exception e) {
            log.error("安装异常: {}", e.getMessage());
            return InstallResult.failed("安装异常: " + e.getMessage(), e);
        }
    }
}
