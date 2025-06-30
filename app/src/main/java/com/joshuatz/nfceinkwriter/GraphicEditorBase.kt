package com.joshuatz.nfceinkwriter

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.viewModels
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

abstract class GraphicEditorBase: AppCompatActivity() {
    @get:LayoutRes
    abstract val layoutId: Int
    @get:IdRes
    abstract val flashButtonId: Int
    @get:IdRes
    abstract val webViewId: Int
    abstract val webViewUrl: String
    protected var mWebView: WebView? = null

    // Initialize the ViewModel
    private val viewModel: MainViewModel by viewModels()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        clearAppCache()
        
        setContentView(this.layoutId)

        val webView: WebView = findViewById(this.webViewId)
        this.mWebView = webView

        // Setup asset loader to handle local asset paths
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        // Override WebView client
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                onWebViewPageStarted()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                onWebViewPageFinished()
            }
        }

        // WebView - Enable JS
        webView.settings.javaScriptEnabled = true

        // WebView - set Chrome client
        webView.webChromeClient = WebChromeClient()


        webView.loadUrl(this.webViewUrl)

        val flashButton: Button = findViewById(this.flashButtonId)
        flashButton.setOnClickListener {
            lifecycleScope.launch {
                getAndFlashGraphic()
            }
        }
        
        observeViewModel()

        val preferences = Preferences(this)
        val savedUrl = preferences.getPreferences()?.getString(PrefKeys.BaseUrl, "")
        if (!savedUrl.isNullOrEmpty()) {
            viewModel.setBaseUrl(savedUrl)
            viewModel.fetchAndExtractStations()
        } else {
            Toast.makeText(this, "No URL is set. Please go to settings in the main app.", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun observeViewModel() {
        val spinnerStations: Spinner = findViewById(R.id.spinner_stations)

        viewModel.stationList.observe(this, Observer { stations ->
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, stations)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerStations.adapter = adapter
        })

        viewModel.selectedStationText.observe(this, Observer { content ->
            // Escape any backticks in the content itself, then use template literals (` `) in javascript
            // to preserve the newline characters from the file.
            val safeContent = content.replace("`", "\\`")
            mWebView?.evaluateJavascript("setTextContent(`${safeContent}`);", null)
        })

        viewModel.errorMessage.observe(this, Observer { message ->
            if (message.isNotEmpty()) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        })

        spinnerStations.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedStation = parent?.getItemAtPosition(position).toString()
                viewModel.downloadFileContent(selectedStation)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun clearAppCache() {
        try {
            val dir: File = cacheDir
            if (dir.deleteRecursively()) {
                Log.i("Cache", "App cache cleared successfully.")
            } else {
                Log.e("Cache", "Failed to clear app cache.")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun getAndFlashGraphic() {
        val mContext = this
        val imageBytes = this.getBitmapFromWebView(this.mWebView!!)
        // Decode binary to bitmap
        @Suppress("UNUSED_VARIABLE")
        val bitmap: Bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        // Save bitmap to file
        withContext(Dispatchers.IO) {
            openFileOutput(GeneratedImageFilename, Context.MODE_PRIVATE).use { fileOutStream ->
                fileOutStream.write(imageBytes)
                fileOutStream.close()
                val navIntent = Intent(mContext, NfcFlasher::class.java)
                val bundle = Bundle()
                bundle.putString(IntentKeys.GeneratedImgPath, GeneratedImageFilename)
                navIntent.putExtras(bundle)
                startActivity(navIntent)
            }
        }
    }

    protected fun updateCanvasSize() {
        val preferences = Preferences(this)
        val pixelSize = ScreenSizesInPixels[preferences.getScreenSize()]
        this.mWebView?.evaluateJavascript("setDisplaySize(${pixelSize!!.first}, ${pixelSize.second});", null)
    }
    
    open fun onWebViewPageFinished() {}
    open fun onWebViewPageStarted() {}

    open suspend fun getBitmapFromWebView(webView: WebView): ByteArray {
        webView.evaluateJavascript(
            "getImgSerializedFromCanvas(undefined, undefined, (output) => window.imgStr = output);",
            null
        )
        delay(1000L)

        return suspendCoroutine<ByteArray> { continuation ->
            webView.evaluateJavascript("window.imgStr;") { bitmapStr ->
                // The result from JS is a base64 string with a prefix "data:image/png;base64,"
                // We need to remove the prefix before decoding.
                val pureBase64Encoded = bitmapStr.substring(bitmapStr.indexOf(",") + 1)
                val imageBytes = Base64.decode(pureBase64Encoded, Base64.DEFAULT)
                continuation.resume(imageBytes)
            }
        }
    }
}