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

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
            if (content == null || content.isBlank()) {
                return new JSONObject();
            }
            JSONObject result = JSON.parseObject(content);
            return result != null ? result : new JSONObject();
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
