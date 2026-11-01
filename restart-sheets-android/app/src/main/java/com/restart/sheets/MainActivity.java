package com.restart.sheets;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RESTART Sheets: a thin native shell around Excel Online, OneDrive and Google Sheets.
 * Keeps the user signed in, supports file upload and download, and accepts shared links.
 */
public class MainActivity extends Activity {

    private static final String HOME = "file:///android_asset/home.html";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";
    private static final String PREFS = "restart_sheets";
    private static final String KEY_DESKTOP = "desktop";
    private static final int REQ_FILE = 1;

    private static final int M_HOME = 1;
    private static final int M_RELOAD = 2;
    private static final int M_DESKTOP = 3;
    private static final int M_COPY = 4;
    private static final int M_BROWSER = 5;

    private WebView web;
    private ProgressBar bar;
    private ValueCallback<Uri[]> filePathCallback;
    private String defaultUa;
    private boolean desktop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        root.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP));
        setContentView(root);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        desktop = prefs.getBoolean(KEY_DESKTOP, false);

        setupWebView();

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            String url = urlFromIntent(getIntent());
            web.loadUrl(url != null ? url : HOME);
        }
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        defaultUa = s.getUserAgentString();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        applyDesktopMode(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookie(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if (scheme == null || "http".equals(scheme) || "https".equals(scheme)
                        || "file".equals(scheme) || "about".equals(scheme)) {
                    return false;
                }
                openExternally(uri);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                bar.setProgress(0);
                bar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                bar.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                bar.setProgress(newProgress);
                bar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (title != null && !title.isEmpty()) {
                    setTitle(title);
                }
            }

            // Lets "Upload" buttons in OneDrive, Excel Online and Google Drive pick a file from the phone.
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        // Saves exported .xlsx / .csv files into the Downloads folder.
        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show();
                return;
            }
            try {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                if (mimeType != null) {
                    r.setMimeType(mimeType);
                }
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) {
                    r.addRequestHeader("Cookie", cookies);
                }
                r.addRequestHeader("User-Agent", userAgent);
                String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
                r.setTitle(name);
                r.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
                Toast.makeText(this, R.string.downloading, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Desktop mode makes Excel Online open the full editor instead of the mobile viewer. */
    private void applyDesktopMode(boolean reload) {
        WebSettings s = web.getSettings();
        s.setUserAgentString(desktop ? DESKTOP_UA : defaultUa);
        s.setLoadWithOverviewMode(desktop);
        if (reload) {
            web.reload();
        }
    }

    private String urlFromIntent(Intent intent) {
        if (intent == null) {
            return null;
        }
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            return intent.getDataString();
        }
        if (Intent.ACTION_SEND.equals(action)) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                Matcher m = Pattern.compile("https?://\\S+").matcher(text);
                if (m.find()) {
                    return m.group();
                }
            }
        }
        return null;
    }

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = urlFromIntent(intent);
        if (url != null) {
            web.loadUrl(url);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, M_HOME, 0, R.string.menu_home);
        menu.add(0, M_RELOAD, 1, R.string.menu_reload);
        menu.add(0, M_DESKTOP, 2, R.string.menu_desktop).setCheckable(true).setChecked(desktop);
        menu.add(0, M_COPY, 3, R.string.menu_copy);
        menu.add(0, M_BROWSER, 4, R.string.menu_browser);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case M_HOME:
                web.loadUrl(HOME);
                return true;
            case M_RELOAD:
                web.reload();
                return true;
            case M_DESKTOP:
                desktop = !desktop;
                item.setChecked(desktop);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(KEY_DESKTOP, desktop).apply();
                applyDesktopMode(true);
                return true;
            case M_COPY: {
                String url = web.getUrl();
                if (url != null && !url.startsWith("file:")) {
                    ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cb.setPrimaryClip(ClipData.newPlainText("url", url));
                    Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            case M_BROWSER: {
                String url = web.getUrl();
                if (url != null && !url.startsWith("file:")) {
                    openExternally(Uri.parse(url));
                }
                return true;
            }
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
