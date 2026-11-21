package app.purechat;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * 应用唯一界面：一个全屏 WebView 承载本地前端。
 *
 * <p>界面、会话、Markdown 渲染都在 assets/web 中实现；模型调用与本地存储在原生侧通过
 * {@link Bridge} 暴露给前端，避免跨域限制与 WebView 存储不稳定的问题。
 */
public class MainActivity extends Activity {

    /** 前端页面入口（assets 内）。 */
    private static final String ENTRY_URL = "file:///android_asset/web/index.html";

    /** 承载前端的 WebView。 */
    private WebView webView;

    /** 前端与原生通信的桥接对象。 */
    private Bridge bridge;

    /** 状态栏高度（dp），页面加载完成后重新注入。 */
    private int safeTopPx;

    /** 导航栏高度（dp），页面加载完成后重新注入。 */
    private int safeBottomPx;

    /** 输入法高度（dp）；键盘收起时为 0。 */
    private int keyboardPx;

    /** 页面是否已加载完成，未完成时的注入会被丢弃，由 onPageFinished 补发。 */
    private boolean pageReady;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applySystemBars();
        webView = createWebView();
        setContentView(webView);
        bridge = new Bridge(this, webView);
        webView.addJavascriptInterface(bridge, "PureChatNative");
        // 仅调试包开放 WebView 远程调试，正式包保持关闭。
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        applyInsets();
        webView.loadUrl(ENTRY_URL);
    }

    /**
     * 把安全区与键盘高度注入页面：Android WebView 不支持 env(safe-area-inset-*)，
     * 前端改用 --safe-top / --safe-bottom / --keyboard-height 三个变量参与布局。
     *
     * <p>窗口设置了 {@code setDecorFitsSystemWindows(false)}，系统不会为输入法缩小窗口，
     * 因此必须自己读取 IME 高度交给页面，否则键盘会盖住输入框。</p>
     */
    private void applyInsets() {
        final View root = webView;
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                int top;
                int bottom;
                int ime = 0;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    top = bars.top;
                    bottom = bars.bottom;
                    ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                // 30 及以上系统可在旧版 API 上取到 IME 高度；更低版本交给前端用 visualViewport 兜底。
                if (ime <= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ime = 0;
                }
                float density = getResources().getDisplayMetrics().density;
                safeTopPx = Math.round(top / density);
                safeBottomPx = Math.round(bottom / density);
                keyboardPx = Math.round(ime / density);
                pushSafeArea();
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    /** 把当前安全区数值写入页面 CSS 变量；页面未就绪时直接跳过。 */
    private void pushSafeArea() {
        if (!pageReady || webView == null) {
            return;
        }
        String css = "document.documentElement.style.setProperty('--safe-top','" + safeTopPx + "px');"
                + "document.documentElement.style.setProperty('--safe-bottom','" + safeBottomPx
                + "px');"
                + "document.documentElement.style.setProperty('--keyboard-height','" + keyboardPx
                + "px');";
        webView.evaluateJavascript(css, null);
    }

    /** 让内容延伸到状态栏与导航栏下方，图标颜色跟随界面主题。 */
    private void applySystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    /** 按主题切换状态栏图标明暗：dark 为 true 表示界面为深色。 */
    void setDarkChrome(boolean dark) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(dark ? 0 : mask, mask);
            }
        } else {
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            int mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
            flags = dark ? (flags & ~mask) : (flags | mask);
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    /** 建立 WebView：禁用缩放与滚动条，开启 DOM 存储，允许 file:// 加载同目录资源。 */
    private WebView createWebView() {
        WebView view = new WebView(this);
        view.setBackgroundColor(Color.TRANSPARENT);
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);
        view.setVerticalScrollBarEnabled(false);
        view.setHorizontalScrollBarEnabled(false);

        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        // 前端为本地静态资源，禁止混合内容与第三方定位请求。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(false);
        }
        view.setWebChromeClient(new android.webkit.WebChromeClient());
        view.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public void onPageFinished(WebView web, String url) {
                pageReady = true;
                pushSafeArea();
            }
        });
        return view;
    }

    /** 录音权限的申请结果交回 Bridge：授权后自动重新开始录音，拒绝则回传失败提示。 */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (!AsrRecorder.onRequestPermissionsResult(requestCode, permissions, grantResults)) {
            return;
        }
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (bridge != null) {
            bridge.onAsrPermissionResult(granted);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applySystemBars();
    }

    /** 把文件选择器的结果转交给桥接对象（文件输入功能）。 */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (bridge != null) {
            bridge.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onBackPressed() {
        // 界面内的返回（关闭抽屉、设置面板）由前端处理，这里只询问前端是否需要吞掉。
        if (webView != null) {
            webView.evaluateJavascript(
                    "(function(){try{return window.__onAndroidBack&&window.__onAndroidBack()?'1':'0'}catch(e){return '0'}})()",
                    new android.webkit.ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            if (!"\"1\"".equals(value)) {
                                finish();
                            }
                        }
                    });
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) {
            bridge.shutdown();
            bridge = null;
        }
        if (webView != null) {
            webView.removeJavascriptInterface("PureChatNative");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
