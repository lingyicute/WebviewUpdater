package com.webviewupdater.app;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.webkit.WebView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.webviewupdater.app.databinding.ActivityMainBinding;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;

public class MainActivity extends AppCompatActivity {

    private static final String BASE_URL = "https://webview-ver.92li.uk/";
    /** 连接建立的最长等待时间：超过则取消操作并提示切换网络（仍不行再建议 VPN） */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_REDIRECTS = 5;
    private static final long PROGRESS_INTERVAL_MS = 400;

    private static final String KEY_LATEST_VER = "latest_version_code";
    private static final String KEY_LATEST_URL = "latest_apk_url";
    private static final String KEY_DOWNLOADED_APK = "downloaded_apk_path";

    private static final class RequestTask {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicBoolean connectTimedOut = new AtomicBoolean(false);
        volatile boolean connected = false;
        @Nullable volatile HttpURLConnection activeConnection;
        @Nullable Runnable watchdog;

        void cancel() {
            cancelled.set(true);
            abortConnection();
        }

        void abortConnection() {
            final HttpURLConnection c = activeConnection;
            if (c != null) {
                new Thread(c::disconnect, "abort-conn").start();
            }
        }
    }

    private ActivityMainBinding b;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable hideProgressRunnable = this::hideProgressCard;

    @Nullable private RequestTask activeTask;
    @Nullable private AlertDialog activeDialog;
    private boolean busy = false;

    private long currentVersionCode = -1;
    private long latestVersionCode = -1;
    @Nullable private String latestApkUrl;
    @Nullable private File downloadedApk;

    private final ActivityResultLauncher<Intent> installPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                loadCurrentWebViewInfo();
                if (downloadedApk != null && downloadedApk.exists()
                        && (latestVersionCode <= 0 || currentVersionCode < 0 || currentVersionCode < latestVersionCode)
                        && getPackageManager().canRequestPackageInstalls()) {
                    installApk(downloadedApk);
                }
            });

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Material You 动态取色（Android 12+ 自动生效，低版本回退到主题色）
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            latestVersionCode = savedInstanceState.getLong(KEY_LATEST_VER, -1);
            latestApkUrl = savedInstanceState.getString(KEY_LATEST_URL);
            String apkPath = savedInstanceState.getString(KEY_DOWNLOADED_APK);
            if (apkPath != null) {
                File f = new File(apkPath);
                if (isValidApk(f)) {
                    downloadedApk = f;
                }
            }
        }

        b = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        setSupportActionBar(b.toolbar);

        b.tvDevice.setText(getString(R.string.device_info, Build.VERSION.RELEASE, Build.VERSION.SDK_INT));
        loadCurrentWebViewInfo();

        b.btnCheck.setOnClickListener(v -> checkUpdate());
        b.btnDownload.setOnClickListener(v -> {
            if (downloadedApk != null && downloadedApk.exists()) {
                installApk(downloadedApk);
            } else {
                downloadedApk = null;
                b.btnDownload.setText(R.string.btn_download);
                confirmDownload();
            }
        });
        b.btnCancel.setOnClickListener(v -> cancelDownload());
        b.btnHome.setOnClickListener(v -> openUrl(getString(R.string.url_home)));
        b.btnGithub.setOnClickListener(v -> openUrl(getString(R.string.url_github)));
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(KEY_LATEST_VER, latestVersionCode);
        outState.putString(KEY_LATEST_URL, latestApkUrl);
        if (downloadedApk != null && downloadedApk.exists()) {
            outState.putString(KEY_DOWNLOADED_APK, downloadedApk.getAbsolutePath());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 安装完成后返回，刷新当前 WebView 信息与更新状态
        loadCurrentWebViewInfo();
        refreshUpdateStateAfterResume();
    }

    @Override
    protected void onDestroy() {
        dismissActiveDialog();
        super.onDestroy();
        cancelActiveTask();
        ui.removeCallbacksAndMessages(null);
        executor.shutdownNow();
    }

    @NonNull
    private RequestTask startNewTask() {
        cancelActiveTask();
        RequestTask task = new RequestTask();
        activeTask = task;
        return task;
    }

    private void cancelActiveTask() {
        RequestTask task = activeTask;
        activeTask = null;
        if (task != null) {
            if (task.watchdog != null) {
                ui.removeCallbacks(task.watchdog);
            }
            task.cancel();
        }
    }

    // ------------------------------------------------------------------ current WebView

    private void loadCurrentWebViewInfo() {
        PackageInfo pi = null;
        try {
            pi = WebView.getCurrentWebViewPackage();
        } catch (Throwable ignored) {
        }

        if (pi == null) {
            currentVersionCode = -1;
            b.tvCurrentName.setText(R.string.webview_not_found);
            b.tvCurrentPkg.setText("");
            b.tvCurrentPkg.setVisibility(View.GONE);
            b.tvCurrentVersion.setText("");
            b.tvCurrentVersion.setVisibility(View.GONE);
            return;
        }

        currentVersionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? pi.getLongVersionCode()
                : pi.versionCode;

        CharSequence label = pi.applicationInfo != null
                ? getPackageManager().getApplicationLabel(pi.applicationInfo)
                : pi.packageName;
        if (label == null || label.length() == 0) {
            label = pi.packageName;
        }
        b.tvCurrentName.setText(label);
        b.tvCurrentPkg.setText(pi.packageName);
        b.tvCurrentPkg.setVisibility(View.VISIBLE);
        b.tvCurrentVersion.setText(getString(R.string.version_format,
                pi.versionName == null ? "?" : pi.versionName, currentVersionCode));
        b.tvCurrentVersion.setVisibility(View.VISIBLE);
    }

    /**
     * 从系统安装器返回或重建 Activity 后同步界面状态：
     * 若本机 WebView 已升级到 >= latestVersionCode，刷新提示文案、隐藏安装按钮并清理安装包；
     * 若仍待升级，同步卡片、按钮与状态文案。
     */
    private void refreshUpdateStateAfterResume() {
        if (busy) return;
        if (downloadedApk != null && !downloadedApk.exists()) {
            downloadedApk = null;
            if (b.btnDownload.getVisibility() == View.VISIBLE) {
                b.btnDownload.setText(R.string.btn_download);
            }
        }
        if (latestVersionCode <= 0) return;

        b.cardLatest.setVisibility(View.VISIBLE);
        b.tvLatestCode.setText(getString(R.string.latest_code_format, latestVersionCode));

        if (currentVersionCode >= 0 && currentVersionCode >= latestVersionCode) {
            b.tvLatestHint.setText(currentVersionCode == latestVersionCode
                    ? R.string.latest_is_same
                    : R.string.latest_is_newer_local);
            b.tvStatus.setText(R.string.status_up_to_date);
            b.btnDownload.setVisibility(View.GONE);
            if (downloadedApk != null) {
                //noinspection ResultOfMethodCallIgnored
                downloadedApk.delete();
                downloadedApk = null;
            }
            executor.execute(this::cleanupDownloadDirs);
        } else {
            b.tvLatestHint.setText(R.string.latest_is_newer);
            if (downloadedApk != null && downloadedApk.exists()) {
                b.btnDownload.setText(R.string.btn_install);
                b.btnDownload.setVisibility(View.VISIBLE);
                b.tvStatus.setText(getString(R.string.status_update_available, latestVersionCode));
            } else if (latestApkUrl != null) {
                b.btnDownload.setText(R.string.btn_download);
                b.btnDownload.setVisibility(View.VISIBLE);
                CharSequence curStatus = b.tvStatus.getText();
                if (curStatus.equals(getString(R.string.status_idle))
                        || curStatus.equals(getString(R.string.status_download_done))) {
                    b.tvStatus.setText(getString(R.string.status_update_available, latestVersionCode));
                }
            } else {
                b.btnDownload.setVisibility(View.GONE);
            }
        }
    }

    /** Android 主版本号，如 "14" / "8.1.0" -> 8；预览版等非数字前缀按 SDK_INT 映射 */
    private static int androidMajorVersion() {
        String release = Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE.trim();
        Matcher m = Pattern.compile("^(\\d+)").matcher(release);
        if (m.find()) {
            try {
                int major = Integer.parseInt(m.group(1));
                if (major > 0) return major;
            } catch (NumberFormatException ignored) {
            }
        }
        int sdk = Build.VERSION.SDK_INT;
        if (sdk >= 33) return sdk - 20; // API 33->13, 34->14, 35->15, 36->16
        if (sdk >= 31) return 12;       // API 31/32->12
        if (sdk >= 28) return sdk - 19; // API 28->9, 29->10, 30->11
        return 8;                       // API 26/27->8
    }

    // ------------------------------------------------------------------ check update

    private void checkUpdate() {
        if (busy) return;
        final RequestTask task = startNewTask();
        setBusy(true);

        ui.removeCallbacks(hideProgressRunnable);
        hideProgressCard();
        b.tvStatus.setText(R.string.status_checking);
        b.btnDownload.setVisibility(View.GONE);
        // 清掉上一次检查的结果：否则这次检查失败（超时 / 格式错误）后，
        // 「服务器最新版本」卡片仍停在上一次的 versionCode 和「发现新版本」提示上，
        // 残留的 latestApkUrl 也会让「下载并安装」指向已经过期的地址。
        b.cardLatest.setVisibility(View.GONE);
        latestVersionCode = -1;
        latestApkUrl = null;
        downloadedApk = null;

        // 10 秒连接看门狗：避免 DNS 解析、多 IP 轮询或 TLS 握手超时过长导致界面长时间卡在「正在检查更新…」
        final Runnable watchdog = () -> {
            if (!task.connected && !task.cancelled.get() && activeTask == task) {
                task.connectTimedOut.set(true);
                task.cancel();
                activeTask = null;
                setBusy(false);
                b.tvStatus.setText(getString(R.string.status_error, getString(R.string.dialog_network_title)));
                showNetworkHelpDialog();
            }
        };
        task.watchdog = watchdog;
        ui.postDelayed(watchdog, CONNECT_TIMEOUT_MS);

        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = openWithRedirects(BASE_URL + androidMajorVersion(), null, task);
                ui.removeCallbacks(watchdog);

                String body = readAll(conn.getInputStream()).trim();
                if (body.startsWith("\uFEFF")) {
                    body = body.substring(1).trim();
                }
                // 期望格式: "versionCode数字[空格]apk下载链接"
                String[] parts = body.split("\\s+");
                if (parts.length != 2) {
                    throw new IOException(getString(R.string.error_bad_response, abbreviate(body)));
                }
                final long ver;
                try {
                    ver = Long.parseLong(parts[0].trim());
                } catch (NumberFormatException e) {
                    throw new IOException(getString(R.string.error_bad_response, abbreviate(body)));
                }
                if (ver <= 0) {
                    throw new IOException(getString(R.string.error_bad_response, abbreviate(body)));
                }
                final String apkUrl = parts[1].trim();
                // 92li.uk 已 HSTS preload：下载地址必须是 https，明文（或畸形 scheme）直接拒绝
                if (!isHttpsUrl(apkUrl)) {
                    throw new IOException(getString(R.string.error_not_https, abbreviate(apkUrl)));
                }

                ui.post(() -> {
                    if (isFinishing() || isDestroyed() || task.cancelled.get() || activeTask != task) return;
                    activeTask = null;
                    setBusy(false);
                    onLatestFetched(ver, apkUrl);
                });
            } catch (Exception e) {
                ui.removeCallbacks(watchdog);
                final boolean timedOut = task.connectTimedOut.get();
                final boolean wasCancelled = task.cancelled.get();
                final boolean wasConnected = task.connected;
                final Exception err = e;
                ui.post(() -> {
                    if (timedOut || wasCancelled || isFinishing() || isDestroyed() || activeTask != task) return;
                    activeTask = null;
                    setBusy(false);
                    if (!wasConnected && isConnectFailure(err)) {
                        b.tvStatus.setText(getString(R.string.status_error, describe(err)));
                        showNetworkHelpDialog();
                    } else {
                        showError(err);
                    }
                });
            } finally {
                task.activeConnection = null;
                if (conn != null) conn.disconnect();
            }
        });
    }

    private void onLatestFetched(long ver, String apkUrl) {
        latestVersionCode = ver;
        latestApkUrl = apkUrl;

        b.cardLatest.setVisibility(View.VISIBLE);
        b.tvLatestCode.setText(getString(R.string.latest_code_format, ver));

        if (currentVersionCode < 0 || currentVersionCode < ver) {
            b.tvLatestHint.setText(R.string.latest_is_newer);
            b.tvStatus.setText(getString(R.string.status_update_available, ver));
            b.btnDownload.setText(R.string.btn_download);
            b.btnDownload.setVisibility(View.VISIBLE);
            confirmDownload();
        } else {
            b.tvLatestHint.setText(currentVersionCode == ver
                    ? R.string.latest_is_same
                    : R.string.latest_is_newer_local);
            b.tvStatus.setText(R.string.status_up_to_date);
            b.btnDownload.setVisibility(View.GONE);
            executor.execute(this::cleanupDownloadDirs);
        }
    }

    private void confirmDownload() {
        if (latestApkUrl == null || isFinishing() || isDestroyed()) return;
        showDialog(new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_update_title)
                .setMessage(getString(R.string.dialog_update_message, latestVersionCode,
                        Math.max(currentVersionCode, 0)))
                .setPositiveButton(R.string.btn_download, (d, w) -> startDownload())
                .setNegativeButton(R.string.later, null));
    }

    // ------------------------------------------------------------------ download

    private void startDownload() {
        if (busy || latestApkUrl == null) return;
        final String urlStr = latestApkUrl;
        final long targetCode = latestVersionCode;

        final RequestTask task = startNewTask();
        setBusy(true);

        // 先移除可能尚未执行的延迟隐藏任务并确保卡片处于 GONE，
        // 避免 LinearProgressIndicator 在可见态下调用 setIndeterminate(true) 抛出 IllegalStateException
        ui.removeCallbacks(hideProgressRunnable);
        b.cardProgress.setVisibility(View.GONE);
        b.progress.setProgressCompat(0, false);
        b.progress.setIndeterminate(true);
        b.tvProgress.setText(R.string.connecting);
        b.tvSpeed.setText("");
        b.btnCancel.setEnabled(true);
        b.cardProgress.setVisibility(View.VISIBLE);
        b.tvStatus.setText(R.string.status_downloading);
        b.btnDownload.setVisibility(View.GONE);

        // 10 秒连接看门狗：连接尚未建立则取消并提示切换网络
        final Runnable watchdog = () -> {
            if (!task.connected && !task.cancelled.get() && activeTask == task) {
                task.connectTimedOut.set(true);
                task.cancel();
                activeTask = null;
                hideProgressCard();
                setBusy(false);
                b.tvStatus.setText(R.string.status_cancelled);
                b.btnDownload.setVisibility(View.VISIBLE);
                showNetworkHelpDialog();
            }
        };
        task.watchdog = watchdog;
        ui.postDelayed(watchdog, CONNECT_TIMEOUT_MS);

        executor.execute(() -> {
            if (task.cancelled.get()) return;
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) dir = new File(getFilesDir(), "Download");
            if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            cleanupOldApks(dir);
            final File out = new File(dir, "webview_" + targetCode + ".apk");
            final File tmp = new File(dir, "webview_" + targetCode + "_"
                    + SystemClock.elapsedRealtimeNanos() + ".apk.part");

            HttpURLConnection conn = null;
            try {
                conn = openWithRedirects(urlStr, "application/vnd.android.package-archive, */*", task);
                ui.removeCallbacks(watchdog);

                final long total = conn.getContentLengthLong();
                ui.post(() -> {
                    if (isFinishing() || isDestroyed() || task.cancelled.get() || activeTask != task) return;
                    updateProgress(0, total, 0);
                });
                long downloaded = 0;
                long lastTick = SystemClock.elapsedRealtime();
                long lastBytes = 0;
                double speed = 0;

                try (InputStream in = new BufferedInputStream(conn.getInputStream(), 64 * 1024);
                     FileOutputStream fos = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (task.cancelled.get()) throw new InterruptedIOException("cancelled");
                        fos.write(buf, 0, n);
                        downloaded += n;

                        long now = SystemClock.elapsedRealtime();
                        long dt = now - lastTick;
                        if (dt >= PROGRESS_INTERVAL_MS) {
                            speed = (downloaded - lastBytes) * 1000.0 / dt;
                            lastTick = now;
                            lastBytes = downloaded;
                            final long d = downloaded;
                            final double s = speed;
                            ui.post(() -> {
                                if (isFinishing() || isDestroyed() || task.cancelled.get() || activeTask != task) return;
                                updateProgress(d, total, s);
                            });
                        }
                    }
                    fos.flush();
                }

                if (task.cancelled.get()) throw new InterruptedIOException("cancelled");
                if (downloaded <= 0 || (total > 0 && downloaded != total) || !isValidApk(tmp)) {
                    throw new IOException(getString(R.string.error_download_incomplete));
                }
                if (out.exists()) //noinspection ResultOfMethodCallIgnored
                    out.delete();
                if (!tmp.renameTo(out)) throw new IOException(getString(R.string.error_save_file));

                final long finalDownloaded = downloaded;
                ui.post(() -> {
                    if (isFinishing() || isDestroyed() || task.cancelled.get() || activeTask != task) return;
                    activeTask = null;
                    updateProgress(finalDownloaded, total > 0 ? total : finalDownloaded, 0);
                    onDownloadFinished(out);
                });
            } catch (Exception e) {
                ui.removeCallbacks(watchdog);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                final boolean wasCancelled = task.cancelled.get();
                final boolean timedOut = task.connectTimedOut.get();
                final boolean wasConnected = task.connected;
                final Exception err = e;
                ui.post(() -> {
                    if (timedOut || isFinishing() || isDestroyed() || activeTask != task) return; // 看门狗或主动取消已处理 UI
                    activeTask = null;
                    hideProgressCard();
                    setBusy(false);
                    if (wasCancelled) {
                        b.tvStatus.setText(R.string.status_cancelled);
                        b.btnDownload.setVisibility(View.VISIBLE);
                    } else if (!wasConnected && isConnectFailure(err)) {
                        b.tvStatus.setText(R.string.status_cancelled);
                        b.btnDownload.setVisibility(View.VISIBLE);
                        showNetworkHelpDialog();
                    } else {
                        b.btnDownload.setVisibility(View.VISIBLE);
                        showError(err);
                    }
                });
            } finally {
                task.activeConnection = null;
                if (conn != null) conn.disconnect();
            }
        });
    }

    private void cleanupDownloadDirs() {
        File extDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (extDir != null && extDir.exists()) {
            cleanupOldApks(extDir);
        }
        File intDir = new File(getFilesDir(), "Download");
        if (intDir.exists()) {
            cleanupOldApks(intDir);
        }
    }

    /** 清理下载目录中残留的旧版 APK 与临时分片，避免占用数百 MB 存储空间 */
    private static void cleanupOldApks(@NonNull File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (f.isFile() && name.startsWith("webview_")
                    && (name.endsWith(".apk") || name.endsWith(".part"))) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /** 校验文件是否为可解析的 APK 归档，防止截断流或错误网关 HTML 被当作安装包 */
    private boolean isValidApk(@Nullable File apk) {
        if (apk == null || !apk.isFile() || apk.length() <= 0) {
            return false;
        }
        try {
            PackageInfo info = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            return info != null && info.packageName != null && !info.packageName.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 手动处理重定向，并在最终目标连接建立且返回 200 后标记 connected。
     * 只允许 https：即使平台已禁止明文，这里也显式拒绝 30x 到明文地址，避免降级。
     */
    private HttpURLConnection openWithRedirects(
            @NonNull String urlStr, @Nullable String accept, @NonNull RequestTask task) throws IOException {
        String current = urlStr;
        if (!isHttpsUrl(current)) {
            throw new IOException(getString(R.string.error_not_https, abbreviate(current)));
        }
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            if (task.cancelled.get()) throw new InterruptedIOException("cancelled");
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            boolean keepOpen = false;
            try {
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", userAgent());
                conn.setRequestProperty("Cache-Control", "no-cache");
                if (accept != null) {
                    conn.setRequestProperty("Accept", accept);
                    conn.setRequestProperty("Accept-Encoding", "identity");
                }
                task.activeConnection = conn;
                if (task.cancelled.get()) {
                    throw new InterruptedIOException("cancelled");
                }
                conn.connect();

                int code = conn.getResponseCode();
                if (task.cancelled.get()) {
                    throw new InterruptedIOException("cancelled");
                }
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = conn.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) {
                        throw new IOException(getString(R.string.error_redirect_no_location));
                    }
                    String next = new URL(new URL(current), location.trim()).toString();
                    if (!isHttpsUrl(next)) {
                        throw new IOException(getString(R.string.error_not_https, abbreviate(next)));
                    }
                    current = next;
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IOException(getString(R.string.error_http, code));
                }
                task.connected = true;
                keepOpen = true;
                return conn;
            } finally {
                if (!keepOpen) {
                    conn.disconnect();
                }
            }
        }
        throw new IOException(getString(R.string.error_too_many_redirects));
    }

    private void cancelDownload() {
        if (activeTask == null) return;
        cancelActiveTask();
        hideProgressCard();
        setBusy(false);
        b.tvStatus.setText(R.string.status_cancelled);
        b.btnDownload.setVisibility(View.VISIBLE);
    }

    private void updateProgress(long downloaded, long total, double bytesPerSec) {
        if (total > 0) {
            if (b.progress.isIndeterminate()) b.progress.setIndeterminate(false);
            int pct = (int) Math.min(100, downloaded * 100 / total);
            b.progress.setProgressCompat(pct, true);
            b.tvProgress.setText(getString(R.string.progress_format,
                    formatBytes(downloaded), formatBytes(total), pct));
        } else {
            b.tvProgress.setText(getString(R.string.progress_unknown, formatBytes(downloaded)));
        }
        b.tvSpeed.setText(bytesPerSec > 0
                ? getString(R.string.speed_format, formatBytes((long) bytesPerSec))
                : "");
    }

    private void onDownloadFinished(File apk) {
        downloadedApk = apk;
        setBusy(false);
        b.btnCancel.setEnabled(false);
        b.tvStatus.setText(R.string.status_download_done);
        b.btnDownload.setText(R.string.btn_install);
        b.btnDownload.setVisibility(View.VISIBLE);
        ui.postDelayed(hideProgressRunnable, 600);
        installApk(apk);
    }

    private void hideProgressCard() {
        b.cardProgress.setVisibility(View.GONE);
        b.tvSpeed.setText("");
        b.tvProgress.setText("");
    }

    // ------------------------------------------------------------------ install

    private void installApk(@NonNull File apk) {
        if (isFinishing() || isDestroyed()) return;
        if (!apk.isFile() || apk.length() <= 0) {
            downloadedApk = null;
            b.btnDownload.setText(R.string.btn_download);
            confirmDownload();
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            showDialog(new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_install_perm_title)
                    .setMessage(R.string.dialog_install_perm_message)
                    .setPositiveButton(R.string.go_settings, (d, w) -> {
                        try {
                            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName()));
                            installPermissionLauncher.launch(i);
                        } catch (Exception e1) {
                            try {
                                installPermissionLauncher.launch(
                                        new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
                            } catch (Exception e2) {
                                showError(e2);
                            }
                        }
                    })
                    .setNegativeButton(R.string.btn_cancel, null));
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
            intent.putExtra(Intent.EXTRA_RETURN_RESULT, false);
            startActivity(intent);
        } catch (Exception e) {
            showError(e);
        }
    }

    // ------------------------------------------------------------------ 署名区：外链

    /** 用系统浏览器打开链接；没有可处理的应用时给出提示 */
    private void openUrl(@NonNull String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(intent);
        } catch (Exception e) {
            Snackbar.make(b.getRoot(), R.string.no_browser, Snackbar.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ dialogs / ui helpers

    private void showDialog(@NonNull MaterialAlertDialogBuilder builder) {
        if (isFinishing() || isDestroyed()) return;
        dismissActiveDialog();
        activeDialog = builder.show();
    }

    private void dismissActiveDialog() {
        if (activeDialog != null) {
            try {
                if (activeDialog.isShowing()) {
                    activeDialog.dismiss();
                }
            } catch (Exception ignored) {
            }
            activeDialog = null;
        }
    }

    /** 连不上服务器时的引导 */
    private void showNetworkHelpDialog() {
        showDialog(new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_network_title)
                .setMessage(R.string.dialog_network_message)
                .setPositiveButton(R.string.ok, null));
    }

    private void showError(Exception e) {
        String msg = describe(e);
        b.tvStatus.setText(getString(R.string.status_error, msg));
        showDialog(new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_error_title)
                .setMessage(msg)
                .setPositiveButton(R.string.ok, null));
    }

    private void setBusy(boolean value) {
        busy = value;
        b.btnCheck.setEnabled(!value);
        b.btnDownload.setEnabled(!value);
    }

    /** 只接受合法且包含非空主机名的 https:// 地址；拒绝 http://、空白字符或畸形地址 */
    private static boolean isHttpsUrl(@NonNull String url) {
        if (!url.regionMatches(true, 0, "https://", 0, "https://".length())) {
            return false;
        }
        for (int i = 0; i < url.length(); i++) {
            if (Character.isWhitespace(url.charAt(i))) {
                return false;
            }
        }
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || host.isEmpty()) {
                return false;
            }
            new URL(url);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isConnectFailure(@Nullable Throwable e) {
        while (e != null) {
            if (e instanceof SocketTimeoutException
                    || e instanceof UnknownHostException
                    || e instanceof ConnectException
                    || e instanceof SocketException
                    || e instanceof SSLException) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }

    private String userAgent() {
        return "WebViewUpdater/1.0 (Android " + Build.VERSION.RELEASE + "; " + Build.MODEL + ")";
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) m = e.getClass().getSimpleName();
        return m;
    }

    private static String abbreviate(@NonNull String s) {
        if (s.length() <= 80) return s;
        int end = Character.isHighSurrogate(s.charAt(79)) ? 79 : 80;
        return s.substring(0, end) + "…";
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[1024];
            int n;
            while ((n = r.read(buf)) != -1) {
                sb.append(buf, 0, n);
                if (sb.length() > 64 * 1024) break;
            }
        }
        return sb.toString();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) bytes = 0;
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }
}
