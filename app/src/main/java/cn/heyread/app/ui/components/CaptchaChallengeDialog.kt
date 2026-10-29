package cn.heyread.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.heyread.app.data.remote.CaptchaCallbackResult
import cn.heyread.app.data.remote.isAllowedCaptchaUrl
import cn.heyread.app.data.remote.parseCaptchaCallback
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun CaptchaChallengeDialog(
    appId: String,
    attemptId: Long,
    onCallback: (Long, CaptchaCallbackResult) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f).testTag("captcha-dialog"),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp).testTag("captcha-dialog-close"),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭验证")
                    }
                    Text("小黑盒人机验证", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "请按提示完成人机验证，完成后将继续读取当前内容。",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                CaptchaWebContent(appId, attemptId, onCallback, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CaptchaWebContent(
    appId: String,
    attemptId: Long,
    onCallback: (Long, CaptchaCallbackResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val loadFailed = remember(attemptId) { mutableStateOf(false) }
    val isLoading = remember(attemptId) { mutableStateOf(true) }
    val bridge =
        remember(attemptId) {
            CaptchaBridge(
                attemptId,
                onCallback,
                notifyReady = { isLoading.value = false },
                notifyFailure = { loadFailed.value = true },
            )
        }
    val webView =
        remember(attemptId) { createCaptchaWebView(context, bridge) { bridge.reportFailure() } }
    DisposableEffect(webView, bridge) {
        onDispose {
            bridge.close()
            webView.stopLoading()
            webView.removeJavascriptInterface("HeyReadCaptcha")
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }
    }
    LaunchedEffect(webView, appId, attemptId) {
        if (!appId.matches(Regex("[0-9]{1,20}"))) {
            loadFailed.value = true
            return@LaunchedEffect
        }
        loadFailed.value = false
        isLoading.value = true
        webView.loadDataWithBaseURL(
            "https://www.xiaoheihe.cn/",
            captchaShell(appId),
            "text/html",
            "UTF-8",
            null,
        )
    }
    Column(modifier = modifier.fillMaxWidth().testTag("captcha-web-content")) {
        if (loadFailed.value) {
            Text(
                "验证码页面未能加载，请关闭后重试，或返回主页使用官方页面。",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!loadFailed.value && !isLoading.value) {
            Text(
                "请按页面提示完成验证。",
                modifier =
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("captcha-sdk-ready"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 240.dp),
        )
        if (!loadFailed.value && isLoading.value) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp))
                Text(
                    " 正在加载官方验证页面",
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private class CaptchaBridge(
    private val attemptId: Long,
    private val callback: (Long, CaptchaCallbackResult) -> Unit,
    private val notifyReady: () -> Unit,
    private val notifyFailure: () -> Unit,
) {
    private val active = AtomicBoolean(true)
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onResult(rawJson: String?) {
        if (!active.get() || rawJson == null || rawJson.length > 16 * 1024) return
        val parsed = parseCaptchaCallback(rawJson)
        mainHandler.post { if (active.get()) callback(attemptId, parsed) }
    }

    @JavascriptInterface
    fun onReady() {
        if (active.get()) mainHandler.post { if (active.get()) notifyReady() }
    }

    @JavascriptInterface
    fun onFailure() {
        reportFailure()
    }

    fun reportFailure() {
        if (active.compareAndSet(true, false)) {
            mainHandler.post {
                notifyFailure()
                callback(attemptId, CaptchaCallbackResult.Failed("验证码页面加载失败，请关闭后重试。"))
            }
        }
    }

    fun close() {
        active.set(false)
    }
}

private fun createCaptchaWebView(
    context: Context,
    bridge: CaptchaBridge,
    onLoadFailure: () -> Unit,
): WebView =
    WebView(context).apply {
        WebView.setWebContentsDebuggingEnabled(false)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        CookieManager.getInstance().setAcceptCookie(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        addJavascriptInterface(bridge, "HeyReadCaptcha")
        webChromeClient =
            object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) = request.deny()

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?,
                ): Boolean = false

                override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean =
                    true

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: android.webkit.GeolocationPermissions.Callback?,
                ) {
                    callback?.invoke(origin, false, false)
                }
            }
        webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean =
                    request == null ||
                        request.isForMainFrame ||
                        request.url.toString().let(::isAllowedCaptchaUrl).not()

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return blockedResponse()
                    return if (isAllowedCaptchaUrl(url)) null else blockedResponse()
                }

                @Suppress("DEPRECATION")
                override fun shouldInterceptRequest(
                    view: WebView?,
                    url: String?,
                ): WebResourceResponse? =
                    if (url != null && isAllowedCaptchaUrl(url)) null else blockedResponse()

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?,
                ) {
                    handler?.cancel()
                    onLoadFailure()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) onLoadFailure()
                }

                @Suppress("DEPRECATION")
                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?,
                ) {
                    onLoadFailure()
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    if (url != "https://www.xiaoheihe.cn/") {
                        view?.stopLoading()
                        onLoadFailure()
                    }
                }
            }
    }

private fun blockedResponse() =
    WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

private fun captchaShell(appId: String): String {
    require(appId.matches(Regex("[0-9]{1,20}")))
    return """
    <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' https://turing.captcha.qcloud.com https://ca.turing.captcha.qcloud.com https://turing.captcha.gtimg.com https://global.turing.captcha.gtimg.com https://rce.tencentrio.com https://cloudcache.tencentcs.com; connect-src https://turing.captcha.qcloud.com https://ca.turing.captcha.qcloud.com https://turing.captcha.gtimg.com https://global.turing.captcha.gtimg.com https://rce.tencentrio.com https://cloudcache.tencentcs.com; img-src data: https://turing.captcha.qcloud.com https://ca.turing.captcha.qcloud.com https://turing.captcha.gtimg.com https://global.turing.captcha.gtimg.com https://rce.tencentrio.com https://cloudcache.tencentcs.com; frame-src https://turing.captcha.qcloud.com https://ca.turing.captcha.qcloud.com https://turing.captcha.gtimg.com https://global.turing.captcha.gtimg.com https://rce.tencentrio.com https://cloudcache.tencentcs.com; style-src 'unsafe-inline' https://turing.captcha.qcloud.com https://ca.turing.captcha.qcloud.com https://turing.captcha.gtimg.com https://global.turing.captcha.gtimg.com https://rce.tencentrio.com https://cloudcache.tencentcs.com">
    </head><body><div id="status">正在加载官方验证组件…</div><script>
    (function(){
      var timer=setTimeout(fail,30000),failed=false;
      function fail(){if(failed)return;failed=true;clearTimeout(timer);document.getElementById('status').textContent='验证组件加载失败，请关闭后重试。';try{window.HeyReadCaptcha.onFailure();}catch(e){}}
      var sdk=document.createElement('script');
      sdk.src='https://turing.captcha.qcloud.com/TCaptcha.js';
      sdk.onload=function(){try{
        var captcha=new TencentCaptcha('$appId',function(result){
          try{window.HeyReadCaptcha.onResult(JSON.stringify(result));}catch(e){fail();}
        },{ready:function(){
          if(failed)return;clearTimeout(timer);document.getElementById('status').textContent='';window.HeyReadCaptcha.onReady();
        }});
        captcha.show();
      }catch(e){fail();}};
      sdk.onerror=fail;document.head.appendChild(sdk);
    })();
    </script></body></html>
    """
        .trimIndent()
}
