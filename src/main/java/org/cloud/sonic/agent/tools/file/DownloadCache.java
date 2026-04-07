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
