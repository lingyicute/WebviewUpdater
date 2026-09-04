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
import java.net.SocketTimeoutException;
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
    /** 连接建立的最长等待时间：超过则取消下载并提示使用 VPN */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_REDIRECTS = 5;
    private static final long PROGRESS_INTERVAL_MS = 400;

    private ActivityMainBinding b;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean connectTimedOut = new AtomicBoolean(false);
    private volatile boolean connected = false;
    private volatile HttpURLConnection activeConnection;
    private boolean busy = false;

    private long currentVersionCode = -1;
    private long latestVersionCode = -1;
    @Nullable private String latestApkUrl;
    @Nullable private File downloadedApk;

    private final ActivityResultLauncher<Intent> installPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (downloadedApk != null && getPackageManager().canRequestPackageInstalls()) {
                    installApk(downloadedApk);
                }
            });

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Material You 动态取色（Android 12+ 自动生效，低版本回退到主题色）
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);

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
                confirmDownload();
            }
        });
        b.btnCancel.setOnClickListener(v -> cancelDownload());
        b.btnHome.setOnClickListener(v -> openUrl(getString(R.string.url_home)));
        b.btnGithub.setOnClickListener(v -> openUrl(getString(R.string.url_github)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 安装完成后返回，刷新当前 WebView 信息
        loadCurrentWebViewInfo();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelled.set(true);
        ui.removeCallbacksAndMessages(null);
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ current WebView

    private void loadCurrentWebViewInfo() {
        PackageInfo pi = null;
        try {
            pi = WebView.getCurrentWebViewPackage();
        } catch (Exception ignored) {
        }

        if (pi == null) {
            currentVersionCode = -1;
            b.tvCurrentName.setText(R.string.webview_not_found);
            b.tvCurrentPkg.setText("");
            b.tvCurrentVersion.setText("");
            return;
        }

        currentVersionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? pi.getLongVersionCode()
                : pi.versionCode;

        CharSequence label = pi.applicationInfo != null
                ? getPackageManager().getApplicationLabel(pi.applicationInfo)
                : pi.packageName;
        b.tvCurrentName.setText(label);
        b.tvCurrentPkg.setText(pi.packageName);
        b.tvCurrentVersion.setText(getString(R.string.version_format,
                pi.versionName == null ? "?" : pi.versionName, currentVersionCode));
    }

    /** Android 主版本号，如 "14" / "8.1.0" -> 8 */
    private static int androidMajorVersion() {
        String release = Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE;
        Matcher m = Pattern.compile("^(\\d+)").matcher(release);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return Build.VERSION.SDK_INT;
    }

    // ------------------------------------------------------------------ check update

    private void checkUpdate() {
        if (busy) return;
        setBusy(true);
        b.tvStatus.setText(R.string.status_checking);
        b.btnDownload.setVisibility(View.GONE);
        downloadedApk = null;

        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(BASE_URL + androidMajorVersion());
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", userAgent());
                conn.setRequestProperty("Cache-Control", "no-cache");

                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IOException(getString(R.string.error_http, code));
                }

                String body = readAll(conn.getInputStream()).trim();
                // 期望格式: "versionCode数字[空格]apk下载链接"
                String[] parts = body.split("\\s+", 2);
                if (parts.length < 2) {
                    throw new IOException(getString(R.string.error_bad_response, abbreviate(body)));
                }
                final long ver;
                try {
                    ver = Long.parseLong(parts[0].trim());
                } catch (NumberFormatException e) {
                    throw new IOException(getString(R.string.error_bad_response, abbreviate(body)));
                }
                final String apkUrl = parts[1].trim();
                if (!apkUrl.startsWith("http")) {
                    throw new IOException(getString(R.string.error_bad_response, abbreviate(body)));
                }

                ui.post(() -> {
                    setBusy(false);
                    onLatestFetched(ver, apkUrl);
                });
            } catch (Exception e) {
                final Exception err = e;
                ui.post(() -> {
                    setBusy(false);
                    if (isConnectFailure(err)) {
                        b.tvStatus.setText(getString(R.string.status_error, describe(err)));
                        showVpnDialog();
                    } else {
                        showError(err);
                    }
                });
            } finally {
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
        } else if (currentVersionCode == ver) {
            b.tvLatestHint.setText(R.string.latest_is_same);
            b.tvStatus.setText(R.string.status_up_to_date);
            b.btnDownload.setVisibility(View.GONE);
        } else {
            b.tvLatestHint.setText(R.string.latest_is_newer_local);
            b.tvStatus.setText(R.string.status_up_to_date);
            b.btnDownload.setVisibility(View.GONE);
        }
    }

    private void confirmDownload() {
        if (latestApkUrl == null) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_update_title)
                .setMessage(getString(R.string.dialog_update_message, latestVersionCode,
                        Math.max(currentVersionCode, 0)))
                .setPositiveButton(R.string.btn_download, (d, w) -> startDownload())
                .setNegativeButton(R.string.later, null)
                .show();
    }

    // ------------------------------------------------------------------ download

    private void startDownload() {
        if (busy || latestApkUrl == null) return;
        final String urlStr = latestApkUrl;
        final long targetCode = latestVersionCode;

        cancelled.set(false);
        connectTimedOut.set(false);
        connected = false;
        setBusy(true);

        // 进度卡片初始为不确定态（此时卡片不可见，允许切换）
        b.progress.setIndeterminate(true);
        b.tvProgress.setText(R.string.connecting);
        b.tvSpeed.setText("");
        b.cardProgress.setVisibility(View.VISIBLE);
        b.tvStatus.setText(R.string.status_downloading);
        b.btnDownload.setVisibility(View.GONE);

        // 10 秒连接看门狗：连接尚未建立则取消并提示使用 VPN
        final Runnable watchdog = () -> {
            if (!connected && !cancelled.get()) {
                connectTimedOut.set(true);
                abortConnection();
                hideProgressCard();
                setBusy(false);
                b.tvStatus.setText(R.string.status_cancelled);
                showVpnDialog();
            }
        };
        ui.postDelayed(watchdog, CONNECT_TIMEOUT_MS);

        executor.execute(() -> {
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) dir = new File(getFilesDir(), "Download");
            if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            final File out = new File(dir, "webview_" + targetCode + ".apk");
            final File tmp = new File(dir, "webview_" + targetCode + ".apk.part");

            HttpURLConnection conn = null;
            try {
                conn = openWithRedirects(urlStr);
                ui.removeCallbacks(watchdog);

                final long total = conn.getContentLengthLong();
                long downloaded = 0;
                long lastTick = SystemClock.elapsedRealtime();
                long lastBytes = 0;
                double speed = 0;

                try (InputStream in = new BufferedInputStream(conn.getInputStream(), 64 * 1024);
                     FileOutputStream fos = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (cancelled.get()) throw new InterruptedIOException("cancelled");
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
                            ui.post(() -> updateProgress(d, total, s));
                        }
                    }
                    fos.flush();
                }

                if (cancelled.get()) throw new InterruptedIOException("cancelled");
                if (out.exists()) //noinspection ResultOfMethodCallIgnored
                    out.delete();
                if (!tmp.renameTo(out)) throw new IOException("无法保存文件");

                final long finalDownloaded = downloaded;
                ui.post(() -> {
                    updateProgress(finalDownloaded, total > 0 ? total : finalDownloaded, 0);
                    onDownloadFinished(out);
                });
            } catch (Exception e) {
                ui.removeCallbacks(watchdog);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                final boolean wasCancelled = cancelled.get();
                final boolean timedOut = connectTimedOut.get();
                final boolean wasConnected = connected;
                final Exception err = e;
                ui.post(() -> {
                    if (timedOut) return; // 看门狗已处理 UI 与弹窗
                    hideProgressCard();
                    setBusy(false);
                    if (wasCancelled) {
                        b.tvStatus.setText(R.string.status_cancelled);
                        b.btnDownload.setVisibility(View.VISIBLE);
                    } else if (!wasConnected && isConnectFailure(err)) {
                        b.tvStatus.setText(R.string.status_cancelled);
                        b.btnDownload.setVisibility(View.VISIBLE);
                        showVpnDialog();
                    } else {
                        b.btnDownload.setVisibility(View.VISIBLE);
                        showError(err);
                    }
                });
            } finally {
                activeConnection = null;
                if (conn != null) conn.disconnect();
            }
        });
    }

    /** 手动处理重定向（含跨协议），并在首次连接建立后标记 connected */
    private HttpURLConnection openWithRedirects(String urlStr) throws IOException {
        String current = urlStr;
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            if (cancelled.get()) throw new InterruptedIOException("cancelled");
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", userAgent());
            conn.setRequestProperty("Accept", "application/vnd.android.package-archive, */*");
            activeConnection = conn;
            conn.connect();
            connected = true;

            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null) throw new IOException("重定向缺少 Location");
                current = new URL(new URL(current), location).toString();
                continue;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect();
                throw new IOException(getString(R.string.error_http, code));
            }
            return conn;
        }
        throw new IOException("重定向次数过多");
    }

    private void cancelDownload() {
        cancelled.set(true);
        abortConnection();
    }

    private void abortConnection() {
        final HttpURLConnection c = activeConnection;
        if (c != null) {
            new Thread(c::disconnect, "abort-conn").start();
        }
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
        b.tvSpeed.setText(getString(R.string.speed_format, formatBytes((long) bytesPerSec)));
    }

    private void onDownloadFinished(File apk) {
        downloadedApk = apk;
        setBusy(false);
        b.tvStatus.setText(R.string.status_download_done);
        b.btnDownload.setText(R.string.btn_install);
        b.btnDownload.setVisibility(View.VISIBLE);
        ui.postDelayed(this::hideProgressCard, 600);
        installApk(apk);
    }

    private void hideProgressCard() {
        b.cardProgress.setVisibility(View.GONE);
        b.tvSpeed.setText("");
        b.tvProgress.setText("");
    }

    // ------------------------------------------------------------------ install

    private void installApk(@NonNull File apk) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_install_perm_title)
                    .setMessage(R.string.dialog_install_perm_message)
                    .setPositiveButton(R.string.go_settings, (d, w) -> {
                        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName()));
                        installPermissionLauncher.launch(i);
                    })
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show();
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

    private void showVpnDialog() {
        if (isFinishing() || isDestroyed()) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_timeout_title)
                .setMessage(R.string.dialog_timeout_message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void showError(Exception e) {
        String msg = describe(e);
        b.tvStatus.setText(getString(R.string.status_error, msg));
        if (isFinishing() || isDestroyed()) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_error_title)
                .setMessage(msg)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void setBusy(boolean value) {
        busy = value;
        b.btnCheck.setEnabled(!value);
        b.btnDownload.setEnabled(!value);
    }

    private static boolean isConnectFailure(Exception e) {
        return e instanceof SocketTimeoutException
                || e instanceof UnknownHostException
                || e instanceof ConnectException
                || e instanceof SSLException;
    }

    private String userAgent() {
        return "WebViewUpdater/1.0 (Android " + Build.VERSION.RELEASE + "; " + Build.MODEL + ")";
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) m = e.getClass().getSimpleName();
        return m;
    }

    private static String abbreviate(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
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

    // 未使用但保留以便扩展提示
    @SuppressWarnings("unused")
    private void toast(String msg) {
        Snackbar.make(b.getRoot(), msg, Snackbar.LENGTH_SHORT).show();
    }
}
