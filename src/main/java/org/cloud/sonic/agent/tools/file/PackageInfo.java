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
