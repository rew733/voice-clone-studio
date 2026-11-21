package com.ramzi.voiceclone

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var pendingPermissionRequest: PermissionRequest? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val kikiVoiceUrl = "https://kikivoice.ai/"
    private val localStudioUrl = "file:///android_asset/index.html"

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val request = pendingPermissionRequest
        pendingPermissionRequest = null
        if (request != null) {
            if (granted) {
                request.grant(request.resources)
            } else {
                request.deny()
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "يلزم منح صلاحية الميكروفون لتسجيل العينات الصوتية",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private val fileChooserIntentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult

        val data = result.data
        val uris: Array<Uri>? = when {
            result.resultCode != RESULT_OK || data == null -> null
            data.clipData != null -> {
                val clip = data.clipData!!
                Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
            }
            data.data != null -> arrayOf(data.data!!)
            else -> null
        }
        callback.onReceiveValue(uris)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F17"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#131A26"))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val btnKiki = createNavButton("معالجة في الموقع", "#10B981") {
            webView.loadUrl(kikiVoiceUrl)
        }

        val btnLocal = createNavButton("اختيار الأصوات", "#3B82F6") {
            webView.loadUrl(localStudioUrl)
        }

        val btnReload = createNavButton("تحديث", "#334155") {
            webView.reload()
        }

        topBar.addView(btnKiki)
        topBar.addView(btnLocal)
        topBar.addView(btnReload)

        webView = WebView(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        rootLayout.addView(topBar)
        rootLayout.addView(webView)
        setContentView(rootLayout)

        configureWebView()
        setupBackNavigation()

        webView.loadUrl(kikiVoiceUrl)
    }

    private fun createNavButton(title: String, hexColor: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setBackgroundColor(Color.parseColor(hexColor))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            val params = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
            layoutParams = params
            setOnClickListener { onClick() }
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun launchSafeFileChooser(acceptTypes: Array<String>?) {
        val candidateMime = acceptTypes
            ?.map { it.trim() }
            ?.firstOrNull { it.contains("/") && !it.startsWith(".") }
            ?: "*/*"

        val baseIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = candidateMime
        }

        val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }

        val pm = packageManager
        val chosenIntent = when {
            baseIntent.resolveActivity(pm) != null -> Intent.createChooser(baseIntent, "اختر ملفاً صوتياً")
            fallbackIntent.resolveActivity(pm) != null -> Intent.createChooser(fallbackIntent, "اختر ملفاً")
            else -> null
        }

        if (chosenIntent != null) {
            fileChooserIntentLauncher.launch(chosenIntent)
        } else {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
            Toast.makeText(
                this,
                "لم يتم العثور على تطبيق لإدارة الملفات على الجهاز",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.userAgentString = settings.userAgentString.replace("; wv", "")

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectBlobDownloadInterceptor()
            }
        }

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        })

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                return try {
                    launchSafeFileChooser(fileChooserParams?.acceptTypes)
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "تعذر فتح مستعرض الملفات: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (!wantsAudio) {
                    request.grant(request.resources)
                    return
                }

                val hasMicPermission = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                if (hasMicPermission) {
                    request.grant(request.resources)
                } else {
                    pendingPermissionRequest = request
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                return true
            }
        }
    }

    private fun injectBlobDownloadInterceptor() {
        val js = """
            (function() {
                if (window.__blobHookInstalled) return;
                window.__blobHookInstalled = true;
                const origClick = HTMLAnchorElement.prototype.click;
                HTMLAnchorElement.prototype.click = function() {
                    if (this.href && this.href.startsWith('blob:')) {
                        const fileName = this.download || ('kikivoice_' + Date.now() + '.mp3');
                        fetch(this.href)
                            .then(r => r.blob())
                            .then(blob => {
                                const reader = new FileReader();
                                reader.onloadend = () => {
                                    if (window.AndroidBridge && window.AndroidBridge.saveAudioFile) {
                                        window.AndroidBridge.saveAudioFile(reader.result, fileName);
                                    }
                                };
                                reader.readAsDataURL(blob);
                            });
                        return;
                    }
                    return origClick.apply(this, arguments);
                };
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun handleDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String
    ) {
        try {
            if (url.startsWith("data:")) {
                val fileName = "kikivoice_${System.currentTimeMillis()}.mp3"
                AndroidBridge().saveAudioFile(url, fileName)
                return
            }

            if (url.startsWith("blob:")) {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    .ifBlank { "kikivoice_${System.currentTimeMillis()}.mp3" }
                val fetchJs = """
                    fetch('$url')
                        .then(r => r.blob())
                        .then(blob => {
                            const reader = new FileReader();
                            reader.onloadend = () => {
                                window.AndroidBridge.saveAudioFile(reader.result, '$fileName');
                            };
                            reader.readAsDataURL(blob);
                        });
                """.trimIndent()
                webView.evaluateJavascript(fetchJs, null)
                return
            }

            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType.ifBlank { "audio/mpeg" })
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrEmpty()) {
                    addRequestHeader("cookie", cookies)
                }
                addRequestHeader("User-Agent", userAgent)
                setTitle(fileName)
                setDescription("جاري تحميل الملف الصوتي...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "بدأ تحميل الملف إلى التنزيلات: $fileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر تحميل الملف: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun saveAudioFile(base64Data: String, fileName: String) {
            try {
                val cleanBase64 = if (base64Data.contains(",")) {
                    base64Data.substringAfter(",")
                } else {
                    base64Data
                }
                val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)

                val mime = when {
                    fileName.endsWith(".wav", true) -> "audio/wav"
                    fileName.endsWith(".ogg", true) -> "audio/ogg"
                    else -> "audio/mpeg"
                }

                val resolver = applicationContext.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                }

                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI
                }

                val itemUri = resolver.insert(collection, contentValues)
                if (itemUri != null) {
                    resolver.openOutputStream(itemUri)?.use { outputStream ->
                        outputStream.write(bytes)
                        outputStream.flush()
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentValues.clear()
                        contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(itemUri, contentValues, null, null)
                    }
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "تم حفظ الملف في مجلد التنزيلات: $fileName",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "تعذر حفظ الملف: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        @JavascriptInterface
        fun openCloudSite() {
            runOnUiThread {
                webView.loadUrl(kikiVoiceUrl)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        webView.destroy()
        super.onDestroy()
    }
}
