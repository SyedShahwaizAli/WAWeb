package com.shahwaiz.waweb;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final String WA_URL = "https://web.whatsapp.com/";
    private static final int FILE_CHOOSER_CODE = 1001;

    private static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";

    // Keyboard apne aap na khule: jab tak user khud message box ko touch na kare,
    // page ko text box par focus karne se roka jata hai.
    private static final String NO_AUTOFOCUS_JS =
            "(function(){"
                    + "function ed(t){return t&&t.closest&&t.closest('input,textarea,[contenteditable=\"true\"],[role=\"textbox\"]');}"
                    + "if(!window.__waGuard){window.__waGuard=true;window.__waLast=0;"
                    + "['touchstart','mousedown','click'].forEach(function(n){"
                    + "document.addEventListener(n,function(e){if(ed(e.target)){window.__waLast=Date.now();}},true);});"
                    + "var orig=HTMLElement.prototype.focus;"
                    + "HTMLElement.prototype.focus=function(){"
                    + "if(ed(this)&&Date.now()-window.__waLast>2000){return;}"
                    + "return orig.apply(this,arguments);};}"
                    + "var a=document.activeElement;"
                    + "if(a&&ed(a)&&Date.now()-window.__waLast>2000){a.blur();}"
                    + "})();";

    private static final int ZOOM_MIN = 90;
    private static final int ZOOM_MAX = 250;
    private static final int ZOOM_STEP = 20;
    private static final int ZOOM_DEFAULT = 130;

    private WebView webView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> filePathCallback;
    private SharedPreferences prefs;
    private final Handler handler = new Handler();
    private long lastBackPress = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        prefs = getSharedPreferences("waweb", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        FrameLayout webFrame = new FrameLayout(this);
        webView = new WebView(this);
        webFrame.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        webFrame.addView(progressBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));

        root.addView(webFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(buildButtonBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        setupWebView();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            loadWhatsApp();
        }
    }

    // ---------- Neeche bade buttons ----------
    private View buildButtonBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.parseColor("#E8F5E9"));
        bar.setPadding(dp(2), dp(2), dp(2), dp(2));

        bar.addView(makeButton("Refresh", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadWhatsApp();
            }
        }));
        bar.addView(makeButton("A -", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeZoom(-ZOOM_STEP);
            }
        }));
        bar.addView(makeButton("A +", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeZoom(ZOOM_STEP);
            }
        }));
        bar.addView(makeButton("View", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleLayout();
            }
        }));
        return bar;
    }

    private Button makeButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor("#128C7E"));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void changeZoom(int delta) {
        int z = prefs.getInt("textZoom", ZOOM_DEFAULT) + delta;
        if (z < ZOOM_MIN) z = ZOOM_MIN;
        if (z > ZOOM_MAX) z = ZOOM_MAX;
        prefs.edit().putInt("textZoom", z).apply();
        webView.getSettings().setTextZoom(z);
        Toast.makeText(this, "Text size: " + z + "%", Toast.LENGTH_SHORT).show();
    }

    // "Big view" = phone ki screen ke hisaab se bara layout (default)
    // "Full view" = poora desktop layout (chota, zoom kar sakte ho)
    private void toggleLayout() {
        boolean wide = !prefs.getBoolean("wide", false);
        prefs.edit().putBoolean("wide", wide).apply();
        applyLayoutMode();
        Toast.makeText(this, wide ? "Full view" : "Big view", Toast.LENGTH_SHORT).show();
        webView.reload();
    }

    private void applyLayoutMode() {
        boolean wide = prefs.getBoolean("wide", false);
        WebSettings s = webView.getSettings();
        s.setUseWideViewPort(wide);
        s.setLoadWithOverviewMode(wide);
    }

    // ---------- WebView ----------
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(DESKTOP_UA);

        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(prefs.getInt("textZoom", ZOOM_DEFAULT));
        applyLayoutMode();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(webView, true);
        }

        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
                view.evaluateJavascript(NO_AUTOFOCUS_JS, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
                view.evaluateJavascript(NO_AUTOFOCUS_JS, null);
                hideKeyboardLater(600);
                hideKeyboardLater(2500);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl().toString());
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description,
                                        String failingUrl) {
                if (WA_URL.equals(failingUrl) || failingUrl == null) {
                    String html = "<html><body style='font-size:28px;text-align:center;"
                            + "padding:40px;font-family:sans-serif'>"
                            + "<h2>Internet nahi hai</h2>"
                            + "<p>Internet on karo, phir neeche <b>Refresh</b> dabao.</p>"
                            + "</body></html>";
                    view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress == 20 || newProgress == 60) {
                    view.evaluateJavascript(NO_AUTOFOCUS_JS, null);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_CODE);
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this,
                            "File picker nahi mila", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        request.grant(request.getResources());
                    }
                });
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                try {
                    DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                    r.setMimeType(mimeType);
                    r.addRequestHeader("User-Agent", userAgent);
                    r.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    String name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType);
                    r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                    ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
                    Toast.makeText(MainActivity.this, "Downloading: " + name, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Download fail", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void hideKeyboardLater(long delayMs) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                InputMethodManager imm =
                        (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null && webView != null) {
                    imm.hideSoftInputFromWindow(webView.getWindowToken(), 0);
                }
            }
        }, delayMs);
    }

    private boolean handleUrl(String url) {
        Uri uri = Uri.parse(url);
        String host = uri.getHost() == null ? "" : uri.getHost();
        if (host.endsWith("whatsapp.com") || host.endsWith("whatsapp.net")
                || host.endsWith("facebook.com") || host.endsWith("fbcdn.net")
                || url.startsWith("blob:") || url.startsWith("data:")) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
        }
        return true;
    }

    private void loadWhatsApp() {
        if (!isOnline()) {
            Toast.makeText(this, "Internet on karo", Toast.LENGTH_LONG).show();
        }
        webView.loadUrl(WA_URL);
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_CODE && filePathCallback != null) {
            Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // Back dabane se pehle chat se wapas, aakhir mein 2 dafa dabane par band
    // Pehle WhatsApp ke andar ka "back" (chat se list) dabate hain; agar wo na mile
    // to hi app band karne ka sawal aata hai.
    private static final String PAGE_BACK_JS =
            "(function(){"
                    + "var m=document.querySelector('#main');if(!m){return 'no';}"
                    + "var b=m.querySelector('[data-icon^=\"back\"],[data-icon=\"x-viewer\"]');"
                    + "if(!b){return 'no';}"
                    + "var c=(b.closest&&b.closest('button,[role=\"button\"]'))||b;"
                    + "c.click();return 'ok';"
                    + "})();";

    @Override
    public void onBackPressed() {
        webView.evaluateJavascript(PAGE_BACK_JS, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (value != null && value.contains("ok")) {
                    return; // WhatsApp ne khud chat band kar di
                }
                exitOnDoubleBack();
            }
        });
    }

    private void exitOnDoubleBack() {
        long now = System.currentTimeMillis();
        if (now - lastBackPress < 2000) {
            finish();
        } else {
            lastBackPress = now;
            Toast.makeText(this, "Band karne ke liye dobara Back dabao",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
