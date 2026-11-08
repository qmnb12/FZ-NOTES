package com.notes.chat;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared bounded retry rules for scheduled backup work. */
final class BackupRetryPolicy {
    private static final int MAX_RETRIES = 4;
    private static final Pattern HTTP_STATUS = Pattern.compile("(?i)\\bHTTP\\s+(\\d{3})\\b");

    private BackupRetryPolicy() { }

    static boolean shouldRetry(int runAttemptCount, Throwable failure) {
        return runAttemptCount < MAX_RETRIES && isTransientFailure(failure);
    }

    private static boolean isTransientFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage() == null ? "" : current.getMessage();
            Matcher statusMatch = HTTP_STATUS.matcher(message);
            if (statusMatch.find()) {
                try {
                    int status = Integer.parseInt(statusMatch.group(1));
                    return status == 408 || status == 425 || status == 429 || status >= 500;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }

            String lower = message.toLowerCase(java.util.Locale.ROOT);
            // Do not classify ordinary connectivity wording as a permanent setup error.
            if (lower.contains("network is not available")
                    || lower.contains("network unavailable")
                    || lower.contains("network is unreachable")
                    || lower.contains("connection reset")
                    || lower.contains("connection refused")
                    || lower.contains("timed out")) {
                return true;
            }
            if (lower.contains("permission denied") || lower.contains("access denied")
                    || lower.contains("no space left") || lower.contains("enospc")
                    || lower.contains("disk full") || lower.contains("read-only file system")
                    || lower.contains("not configured")
                    || lower.contains("invalid") || lower.contains("unauthorized")
                    || lower.contains("forbidden") || lower.contains("password")
                    || lower.contains("backup key")) {
                return false;
            }

            if (current instanceof FileNotFoundException) return false;
            if (current instanceof UnknownHostException
                    || current instanceof ConnectException
                    || current instanceof NoRouteToHostException
                    || current instanceof SocketTimeoutException
                    || current instanceof SocketException) {
                return true;
            }
            if (current instanceof IOException) return true;
        }
        return false;
    }
}
