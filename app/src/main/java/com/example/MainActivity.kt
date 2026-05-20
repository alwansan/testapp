package com.example

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.navigationBars
                ) { innerPadding ->
                    B_UltraApp(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun B_UltraApp(modifier: Modifier = Modifier) {
    val appContext = LocalContext.current.applicationContext
    var isServerReady by remember { mutableStateOf(false) }
    var loadingStep by remember { mutableStateOf("جاري بدء النظام...") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    // Intercept back presses to navigate WebView history if possible
    BackHandler(enabled = isServerReady && webViewInstance?.canGoBack() == true) {
        webViewInstance?.goBack()
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                loadingStep = "جاري تحضير محرك البايثون (Python Core)..."
                delay(200)

                // Start Chaquopy Python interpreter if not started
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(appContext))
                }

                loadingStep = "جاري تحديد مسار التخزين وتجهيز المجلدات..."
                delay(200)

                val py = Python.getInstance()
                val osModule = py.getModule("os")
                val environ = osModule["environ"]!!

                // Set secure local storage directory where the app has full write permissions
                // Use Environment.DIRECTORY_DOWNLOADS for user convenience
                val baseDownloadDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: appContext.filesDir

                val saveDir = File(baseDownloadDir, "B-Ultra")
                if (!saveDir.exists()) {
                    saveDir.mkdirs()
                }

                environ.put("B_ULTRA_SAVE_DIR", saveDir.absolutePath)

                loadingStep = "جاري تشغيل خادم Flask المحلي..."
                delay(200)

                // Run Python Flask server in its own persistent background thread to prevent GUI block
                Thread {
                    try {
                        val mainModule = py.getModule("main")
                        mainModule.callAttr("start_server")
                    } catch (e: Exception) {
                        Log.e("B-Ultra", "Flask server crashed/stopped", e)
                    }
                }.start()

                loadingStep = "جاري فحص استجابة الخادم المحلي (Port 8000)..."
                
                // Poll local port 8000 using OkHttp
                val client = OkHttpClient.Builder()
                    .connectTimeout(1, TimeUnit.SECONDS)
                    .readTimeout(1, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder().url("http://127.0.0.1:8000/info").build()

                var attempts = 0
                val maxAttempts = 35
                var succeeded = false

                while (attempts < maxAttempts) {
                    try {
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                succeeded = true
                                break
                            }
                        }
                    } catch (e: Exception) {
                        // Server not fully booted yet, ignore and wait
                    }
                    delay(800)
                    attempts++
                    loadingStep = "جاري فحص استجابة الخادم المحلي (محاولة $attempts من $maxAttempts)..."
                }

                if (succeeded) {
                    loadingStep = "اكتمل التشغيل بنجاح! جاري تحميل الواجهة..."
                    delay(300)
                    isServerReady = true
                } else {
                    errorMessage = "فشل الاتصال بالخادم المحلي المدمج بعد $maxAttempts محاولة. يرجى التحقق من توفر مساحة كافية على الجهاز وحاول مجدداً."
                }
            } catch (e: Exception) {
                errorMessage = "خطأ في تهيئة النظام: ${e.message}\n\n${Log.getStackTraceString(e)}"
                Log.e("B-Ultra", "Initialization Error", e)
            }
        }
    }

    Box(modifier = modifier.background(Color(0xFF080B12))) {
        // WebView visible only when server is confirmed running
        AnimatedVisibility(
            visible = isServerReady,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            MainWebView(
                url = "http://127.0.0.1:8000",
                onWebViewCreated = { webViewInstance = it },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Loading and Bootstrapping State
        AnimatedVisibility(
            visible = !isServerReady && errorMessage == null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Large styled logo circle
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .background(
                            color = Color(0xFF0D1018),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .padding(2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "B",
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF63B3ED)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "B-Ultra v14",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFDDE6F0)
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "محمّل الوسائط المتكامل سريع وخفيف",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF48556A)
                )

                Spacer(modifier = Modifier.height(48.dp))

                CircularProgressIndicator(
                    color = Color(0xFF63B3ED),
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(48.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = loadingStep,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFDDE6F0),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }

        // Error State
        AnimatedVisibility(
            visible = errorMessage != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "⚠️",
                    style = MaterialTheme.typography.displayLarge
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "حدث خطأ أثناء التشغيل",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFC8181)
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF12151F)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = errorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = Color(0xFFDDE6F0),
                        modifier = Modifier.padding(16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = {
                        errorMessage = null
                        isServerReady = false
                        // It will trigger the LaunchEffect block again to retry because loadingState reset
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF63B3ED)),
                    shape = RoundedCornerShape(50.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "🔄 إعادة المحاولة",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF080B12)
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MainWebView(
    url: String,
    onWebViewCreated: (WebView) -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    allowFileAccess = true
                }
                webViewClient = WebViewClient()
                // Set hardware acceleration
                setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
                onWebViewCreated(this)
                loadUrl(url)
            }
        },
        modifier = modifier
    )
}
