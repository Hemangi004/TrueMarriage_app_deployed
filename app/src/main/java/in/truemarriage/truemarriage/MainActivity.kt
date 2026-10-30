package `in`.truemarriage.truemarriage

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import `in`.truemarriage.truemarriage.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null

            if (callback != null) {
                val intentData: Intent? = if (result.resultCode == RESULT_OK) result.data else null
                val results: Array<Uri>? = when {
                    intentData?.data != null -> arrayOf(intentData.data!!)
                    intentData?.clipData != null -> {
                        val clip = intentData.clipData!!
                        Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                    }
                    intentData?.dataString != null -> arrayOf(Uri.parse(intentData.dataString))
                    else -> null
                }
                callback.onReceiveValue(results)
            }
        }

    companion object {
        private const val TARGET_URL = "https://mtruemarriage.duckdns.org"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Make system status bar white with dark icons to match mobile header reference
        window.statusBarColor = Color.WHITE
        WindowCompat.getInsetsController(window, binding.root).isAppearanceLightStatusBars = true

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupWebView()
        setupSwipeToRefresh()
        setupBackNavigation()

        binding.btnRetry.setOnClickListener {
            loadWebsite()
        }

        loadWebsite()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webSettings = binding.webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.useWideViewPort = true
        webSettings.loadWithOverviewMode = true
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true
        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // Prevent WebView from enforcing an 8px minimum font size which breaks scaled thumbnail card text (e.g. 5.7px)
        webSettings.minimumFontSize = 1
        webSettings.minimumLogicalFontSize = 1

        // Enforce 100% text zoom to prevent system font scaling from breaking/overlapping template text
        webSettings.textZoom = 100
        webSettings.defaultTextEncodingName = "utf-8"

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        val rawUserAgent = webSettings.userAgentString
        val cleanUserAgent = rawUserAgent
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
        webSettings.userAgentString = cleanUserAgent

        webSettings.javaScriptCanOpenWindowsAutomatically = true
        webSettings.setSupportMultipleWindows(true)

        webSettings.setSupportZoom(true)
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.visibility = View.VISIBLE
                injectWebFixes(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
                binding.swipeRefreshLayout.isRefreshing = false
                injectWebFixes(view)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    showOfflineView()
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleCustomSchemeOrExternalUrl(view, url)
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress == 100) {
                    binding.progressBar.visibility = View.GONE
                    injectWebFixes(view)
                } else {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = newProgress
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                val popupDialog = Dialog(this@MainActivity, android.R.style.Theme_Material_Light_NoActionBar)
                val newWebView = WebView(this@MainActivity).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(true)
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.userAgentString = cleanUserAgent
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(newWebView, true)

                popupDialog.setContentView(newWebView)
                popupDialog.setOnDismissListener {
                    try {
                        newWebView.stopLoading()
                        newWebView.destroy()
                    } catch (_: Exception) {}
                }
                popupDialog.show()

                newWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        subView: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        val lower = url.lowercase()

                        // Handle UPI / payment intents from Razorpay popup
                        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                            return handleCustomSchemeOrExternalUrl(subView, url)
                        }

                        // If popup redirects back to the main site after OAuth, load in main WebView and close popup
                        if (lower.startsWith(TARGET_URL) || lower.contains("duckdns.org")) {
                            binding.webView.loadUrl(url)
                            try {
                                popupDialog.dismiss()
                            } catch (_: Exception) {}
                            return true
                        }

                        // Keep Razorpay / bank 3DS / Netbanking / OAuth pages inside the popup WebView
                        return false
                    }
                }

                newWebView.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) {
                        super.onCloseWindow(window)
                        try {
                            popupDialog.dismiss()
                        } catch (_: Exception) {}
                    }
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = newWebView
                resultMsg?.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val acceptTypes = fileChooserParams?.acceptTypes ?: emptyArray()
                val isImageMode = acceptTypes.any { it.contains("image", ignoreCase = true) }

                val contentIntent = (fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT)).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    if (isImageMode) {
                        type = "image/*"
                    }
                    if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }

                val chooserIntent = Intent.createChooser(contentIntent, fileChooserParams?.title ?: "Select Profile Photo")

                try {
                    fileChooserLauncher.launch(chooserIntent)
                } catch (_: Exception) {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    return false
                }
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }
        }
    }

    /**
     * Handles UPI / Android `intent://` deep links, custom payment schemes, and internal vs external HTTP(S) URLs.
     */
    private fun handleCustomSchemeOrExternalUrl(view: WebView?, url: String): Boolean {
        val lowerUrl = url.lowercase()

        // 1. Handle Android intent:// URIs (used by Razorpay & UPI apps)
        if (lowerUrl.startsWith("intent://")) {
            return try {
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                if (intent != null) {
                    val packageManager = packageManager
                    val info = packageManager.resolveActivity(intent, 0)
                    if (info != null) {
                        startActivity(intent)
                    } else {
                        val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                        if (!fallbackUrl.isNullOrEmpty()) {
                            view?.loadUrl(fallbackUrl)
                        }
                    }
                }
                true
            } catch (e: Exception) {
                e.printStackTrace()
                true
            }
        }

        // 2. Handle UPI and native app schemes (upi://, tez://, phonepe://, paytmmp://, gpay://, whatsapp://, tel:, mailto:)
        if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
            return try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                true
            }
        }

        // 3. Allow internal, OAuth, and Razorpay checkout URLs inside WebView
        return if (isInternalOrAuthUrl(url)) {
            false
        } else {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            true
        }
    }

    private fun isInternalOrAuthUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()
        return lowerUrl.startsWith(TARGET_URL) ||
                lowerUrl.contains("duckdns.org") ||
                lowerUrl.contains("razorpay.com") ||
                lowerUrl.contains("accounts.google.com") ||
                lowerUrl.contains("accounts.youtube.com") ||
                lowerUrl.contains("ssl.gstatic.com") ||
                lowerUrl.contains("google.com/o/oauth2") ||
                lowerUrl.contains("google.com/gsi") ||
                lowerUrl.contains("facebook.com") ||
                lowerUrl.contains("appleid.apple.com")
    }

    private fun setupSwipeToRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            if (isNetworkAvailable()) {
                binding.webView.reload()
            } else {
                binding.swipeRefreshLayout.isRefreshing = false
                showOfflineView()
            }
        }

        binding.webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            binding.swipeRefreshLayout.isEnabled = (scrollY == 0)
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.webView.canGoBack()) {
                        binding.webView.goBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    private fun injectWebFixes(view: WebView?) {
        val css = """
            * {
                -webkit-text-size-adjust: 100% !important;
                text-size-adjust: 100% !important;
            }
            .notranslate div[style*="font-size"] {
                max-width: 100%;
            }
        """.trimIndent().replace("\n", " ")

        val js = """
            (function() {
                // 1. Inject CSS fixes
                var styleId = 'tm-font-fix-style';
                if (!document.getElementById(styleId)) {
                    var style = document.createElement('style');
                    style.id = styleId;
                    style.innerHTML = '$css';
                    (document.head || document.documentElement).appendChild(style);
                }

                // 2. Install file input value reset on click
                if (!window.__tm_file_reset_installed) {
                    window.__tm_file_reset_installed = true;
                    document.addEventListener('click', function(e) {
                        var el = e.target;
                        while (el && el !== document.body) {
                            if (el.tagName === 'INPUT' && el.type === 'file') {
                                el.value = '';
                                break;
                            }
                            var input = el.querySelector ? el.querySelector('input[type="file"]') : null;
                            if (input) {
                                input.value = '';
                                break;
                            }
                            el = el.parentElement;
                        }
                    }, true);
                }

                // 3. Canvas auto-compression shim (prevents huge Base64 photo strings from blowing up localStorage quota)
                if (!window.__tm_canvas_shim_installed) {
                    window.__tm_canvas_shim_installed = true;

                    var origToDataURL = HTMLCanvasElement.prototype.toDataURL;
                    HTMLCanvasElement.prototype.toDataURL = function(type, quality) {
                        if (this.width > 800 || this.height > 800) {
                            try {
                                var maxDim = 800;
                                var w = this.width;
                                var h = this.height;
                                if (w > h) {
                                    h = Math.round((h * maxDim) / w);
                                    w = maxDim;
                                } else {
                                    w = Math.round((w * maxDim) / h);
                                    h = maxDim;
                                }
                                var tempCanvas = document.createElement('canvas');
                                tempCanvas.width = w;
                                tempCanvas.height = h;
                                var ctx = tempCanvas.getContext('2d');
                                ctx.drawImage(this, 0, 0, w, h);
                                return origToDataURL.call(tempCanvas, 'image/jpeg', 0.75);
                            } catch(e) {
                                return origToDataURL.call(this, 'image/jpeg', 0.75);
                            }
                        }
                        return origToDataURL.call(this, 'image/jpeg', quality || 0.75);
                    };

                    var origToBlob = HTMLCanvasElement.prototype.toBlob;
                    HTMLCanvasElement.prototype.toBlob = function(callback, type, quality) {
                        if (this.width > 800 || this.height > 800) {
                            try {
                                var maxDim = 800;
                                var w = this.width;
                                var h = this.height;
                                if (w > h) {
                                    h = Math.round((h * maxDim) / w);
                                    w = maxDim;
                                } else {
                                    w = Math.round((w * maxDim) / h);
                                    h = maxDim;
                                }
                                var tempCanvas = document.createElement('canvas');
                                tempCanvas.width = w;
                                tempCanvas.height = h;
                                var ctx = tempCanvas.getContext('2d');
                                ctx.drawImage(this, 0, 0, w, h);
                                return origToBlob.call(tempCanvas, callback, 'image/jpeg', 0.75);
                            } catch(e) {
                                return origToBlob.call(this, callback, 'image/jpeg', 0.75);
                            }
                        }
                        return origToBlob.call(this, callback, type || 'image/jpeg', quality || 0.75);
                    };
                }

                // 4. Storage QuotaExceededError fix shim
                if (!window.__tm_storage_quota_fix_installed) {
                    window.__tm_storage_quota_fix_installed = true;
                    window.__tm_storage_memory_fallback = window.__tm_storage_memory_fallback || {};

                    var originalSetItem = Storage.prototype.setItem;
                    var originalGetItem = Storage.prototype.getItem;
                    var originalRemoveItem = Storage.prototype.removeItem;

                    Storage.prototype.getItem = function(key) {
                        if (this === window.localStorage && window.__tm_storage_memory_fallback[key] !== undefined) {
                            return window.__tm_storage_memory_fallback[key];
                        }
                        return originalGetItem.call(this, key);
                    };

                    Storage.prototype.removeItem = function(key) {
                        if (this === window.localStorage) {
                            delete window.__tm_storage_memory_fallback[key];
                        }
                        return originalRemoveItem.call(this, key);
                    };

                    Storage.prototype.setItem = function(key, value) {
                        try {
                            originalSetItem.call(this, key, value);
                            if (this === window.localStorage) {
                                delete window.__tm_storage_memory_fallback[key];
                            }
                        } catch (e) {
                            if (e && (e.name === 'QuotaExceededError' || e.name === 'NS_ERROR_DOM_QUOTA_REACHED' || e.code === 22 || e.code === 1014)) {
                                try {
                                    for (var i = this.length - 1; i >= 0; i--) {
                                        var k = this.key(i);
                                        if (k && k !== key && (k.startsWith('googtrans') || k.startsWith('_goog') || k.startsWith('temp_') || k.startsWith('cache_'))) {
                                            this.removeItem(k);
                                        }
                                    }
                                    originalSetItem.call(this, key, value);
                                    if (this === window.localStorage) {
                                        delete window.__tm_storage_memory_fallback[key];
                                    }
                                    return;
                                } catch (e2) {
                                    if (this === window.localStorage) {
                                        window.__tm_storage_memory_fallback[key] = value;
                                    } else {
                                        throw e2;
                                    }
                                }
                            } else {
                                throw e;
                            }
                        }
                    };
                }

                // 5. Header normalization: remove close cross (X), put single Back Arrow (<-) on top-left corner
                function fixHeaderAndBackButtons() {
                    var titles = Array.from(document.querySelectorAll('h1, h2, h3, h4, span, p, div')).filter(function(el) {
                        if (el.children.length > 0) return false;
                        var txt = (el.textContent || '').trim();
                        return txt === 'Profile' || txt === 'Notifications' || txt === 'Edit Your Biodata Profile' || txt === 'Crop Profile Photo';
                    });

                    titles.forEach(function(titleEl) {
                        var header = titleEl.parentElement;
                        while (header && header !== document.body) {
                            var display = window.getComputedStyle(header).display;
                            if (display === 'flex' || header.classList.contains('flex')) {
                                break;
                            }
                            header = header.parentElement;
                        }

                        if (!header || header === document.body) return;

                        header.style.display = 'flex';
                        header.style.alignItems = 'center';

                        var btns = Array.from(header.querySelectorAll('button, [role="button"], a'));
                        var backBtns = [];
                        var closeBtns = [];

                        btns.forEach(function(btn) {
                            var text = (btn.textContent || '').trim().toLowerCase();
                            var aria = (btn.getAttribute('aria-label') || '').toLowerCase();
                            var title = (btn.getAttribute('title') || '').toLowerCase();
                            var html = btn.innerHTML || '';

                            var isClose = text === 'close' ||
                                          aria.includes('close') || title.includes('close') ||
                                          html.includes('M6 18L18 6') || html.includes('m6 18l18 6') ||
                                          html.includes('M18 6L6 18') || html.includes('m18 6l6 18');

                            var isBack = aria.includes('back') || title.includes('back') || text.includes('back') ||
                                         html.includes('M10 19l-7-7') || html.includes('m10 19l-7-7') ||
                                         html.includes('M15 19l-7-7') || html.includes('m15 19l-7-7') ||
                                         btn.__tm_is_back_btn;

                            if (isClose) closeBtns.push(btn);
                            if (isBack) backBtns.push(btn);
                        });

                        var primaryBack = null;
                        if (backBtns.length > 0) {
                            primaryBack = backBtns[0];
                            for (var i = 1; i < backBtns.length; i++) {
                                backBtns[i].remove();
                            }
                            closeBtns.forEach(function(cBtn) { cBtn.remove(); });
                        } else if (closeBtns.length > 0) {
                            primaryBack = closeBtns[0];
                            for (var j = 1; j < closeBtns.length; j++) {
                                closeBtns[j].remove();
                            }
                        }

                        if (primaryBack) {
                            primaryBack.__tm_is_back_btn = true;
                            primaryBack.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" style="width:24px;height:24px;display:block;" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.2"><path stroke-linecap="round" stroke-linejoin="round" d="M10 19l-7-7m0 0l7-7m-7 7h18" /></svg>';
                            primaryBack.setAttribute('aria-label', 'Go back');
                            primaryBack.setAttribute('title', 'Go back');

                            if (header.firstChild !== primaryBack) {
                                header.insertBefore(primaryBack, header.firstChild);
                            }
                            primaryBack.style.marginRight = '0px';
                            primaryBack.style.marginLeft = '0px';
                            primaryBack.style.alignSelf = 'center';

                            var btnWidth = primaryBack.offsetWidth || 36;
                            var spacer = header.querySelector('.__tm_header_spacer');
                            if (!spacer) {
                                spacer = document.createElement('div');
                                spacer.className = '__tm_header_spacer';
                                header.appendChild(spacer);
                            }
                            spacer.style.width = btnWidth + 'px';
                            spacer.style.minWidth = btnWidth + 'px';
                            spacer.style.height = '1px';
                            spacer.style.visibility = 'hidden';
                        }

                        // Center the title text in the exact middle of the header
                        titleEl.style.display = 'block';
                        titleEl.style.textAlign = 'center';
                        titleEl.style.flex = '1';
                        titleEl.style.margin = '0 auto';
                        titleEl.style.alignSelf = 'center';
                        titleEl.style.width = '100%';
                    });
                }

                if (!window.__tm_back_arrow_observer_installed) {
                    window.__tm_back_arrow_observer_installed = true;
                    fixHeaderAndBackButtons();
                    setInterval(fixHeaderAndBackButtons, 500);
                } else {
                    fixHeaderAndBackButtons();
                }
            })();
        """.trimIndent()

        view?.evaluateJavascript(js, null)
    }

    private fun loadWebsite() {
        if (isNetworkAvailable()) {
            binding.offlineLayout.visibility = View.GONE
            binding.webView.visibility = View.VISIBLE
            binding.webView.loadUrl(TARGET_URL)
        } else {
            showOfflineView()
        }
    }

    private fun showOfflineView() {
        binding.webView.visibility = View.GONE
        binding.offlineLayout.visibility = View.VISIBLE
        binding.progressBar.visibility = View.GONE
        binding.swipeRefreshLayout.isRefreshing = false
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork =
            connectivityManager.getNetworkCapabilities(network) ?: return false
        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }
}
