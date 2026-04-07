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
