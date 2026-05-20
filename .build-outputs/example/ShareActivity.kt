package com.example

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Extract shared URL from incoming Intent
        var sharedUrl = ""
        val action = intent?.action
        val type = intent?.type
        if (Intent.ACTION_SEND == action && type != null) {
            if ("text/plain" == type) {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                sharedUrl = extractUrl(sharedText)
            }
        }

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets.navigationBars
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        FloatingShareView(
                            initialUrl = sharedUrl,
                            onDismiss = { finish() }
                        )
                    }
                }
            }
        }
    }

    private fun extractUrl(text: String): String {
        val regex = "(https?://[^\\s]+)".toRegex()
        val match = regex.find(text)
        return match?.value ?: ""
    }
}

// Format representation for UI Display
data class UIFormat(
    val id: String,
    val res: Int,
    val ext: String,
    val fps: Int,
    val dash: Boolean,
    val size: Long
)

// Particle state class
class AnimatedParticle(
    val initialX: Float,
    val initialY: Float,
    val size: Float,
    val speed: Float,
    val color: Color
)

@Composable
fun FloatingShareView(
    initialUrl: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()

    var urlInput by remember { mutableStateOf(initialUrl) }
    var isServerBooting by remember { mutableStateOf(true) }
    var serverStatusMsg by remember { mutableStateOf("جاري التحضير...") }
    var isAnalyzing by remember { mutableStateOf(false) }

    // UI Phases: "setup", "input", "selection", "downloading", "finished", "error"
    var phase by remember { mutableStateOf("setup") }
    
    var videoTitle by remember { mutableStateOf("") }
    var videoThumbUrl by remember { mutableStateOf("") }
    var videoDurationStr by remember { mutableStateOf("") }
    var formatsList by remember { mutableStateOf<List<UIFormat>>(emptyList()) }
    var selectedFormatId by remember { mutableStateOf<String?>("best") }
    var downloadMode by remember { mutableStateOf("video") } // "video" / "audio"

    // Progress reporting
    var downloadPercent by remember { mutableStateOf(0f) }
    var downloadSpeed by remember { mutableStateOf("") }
    var downloadEta by remember { mutableStateOf("") }
    var downloadStep by remember { mutableStateOf("") }
    var downloadFileName by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    // Background particle simulation helper
    val particles = remember {
        List(18) {
            AnimatedParticle(
                initialX = (0..100).random() / 100f,
                initialY = (0..100).random() / 100f,
                size = (4..12).random().toFloat(),
                speed = (1..3).random() / 1500f,
                color = when ((0..3).random()) {
                    0 -> Color(0xFF63B3ED) // Neon Blue
                    1 -> Color(0xFF68D391) // Bright Green
                    2 -> Color(0xFF90CDF4) // Arctic Blue
                    else -> Color(0xFFB7791F) // Subtle Gold
                }
            )
        }
    }

    // Animation progress values
    val infiniteTransition = rememberInfiniteTransition(label = "particles")
    val tickerY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "animY"
    )

    // Glowing changing border color
    val borderGradientColor1 by infiniteTransition.animateColor(
        initialValue = Color(0xFF63B3ED),
        targetValue = Color(0xFF68D391),
        animationSpec = infiniteRepeatable(
            animation = tween(3500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bc1"
    )
    val borderGradientColor2 by infiniteTransition.animateColor(
        initialValue = Color(0xFF90CDF4),
        targetValue = Color(0xFFD69E2E),
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bc2"
    )

    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    // ── STEP 1: Boot/Connect to Python Flask Backend Server
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                serverStatusMsg = "جاري تهيئة كيرنل بايثون..."
                delay(100)

                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(context))
                }

                serverStatusMsg = "تحضير مسار البيانات المحمي..."
                delay(100)

                val py = Python.getInstance()
                val osModule = py.getModule("os")
                val environ = osModule["environ"]!!

                val baseDownloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: context.filesDir

                val saveDir = File(baseDownloadDir, "B-Ultra")
                if (!saveDir.exists()) {
                    saveDir.mkdirs()
                }

                environ.put("B_ULTRA_SAVE_DIR", saveDir.absolutePath)

                serverStatusMsg = "بدء تشغيل خادم ميكرو الخادم..."
                // Try starting server path (if it isn't running)
                Thread {
                    try {
                        val mainModule = py.getModule("main")
                        mainModule.callAttr("start_server")
                    } catch (e: Exception) {
                        Log.w("B-Ultra-Share", "Flask server start attempted (might already be running)")
                    }
                }.start()

                serverStatusMsg = "محاولة الاتصال بالمنصة المدمجة..."
                
                // Connection polling
                val checkRequest = Request.Builder().url("http://127.0.0.1:8000/info").build()
                var connectedSocket = false
                var tries = 0
                while (tries < 25) {
                    try {
                        client.newCall(checkRequest).execute().use { response ->
                            if (response.isSuccessful) {
                                connectedSocket = true
                                break
                            }
                        }
                    } catch (e: Exception) {
                        // ignore and continue
                    }
                    delay(500)
                    tries++
                    serverStatusMsg = "الاتصال بالنواة (محاولة $tries)..."
                }

                if (connectedSocket) {
                    isServerBooting = false
                    phase = "input"
                    
                    // If shared content exists, immediately query analysis!
                    if (urlInput.isNotEmpty()) {
                        analyzeLink(urlInput, client, { isAnalyzing = it }, { p, results ->
                            phase = p
                            if (results != null) {
                                videoTitle = results.optString("title", "فيديو")
                                videoThumbUrl = results.optString("thumb", "")
                                val durSec = results.optInt("duration", 0)
                                videoDurationStr = formatDuration(durSec)
                                
                                val formatsArray = results.optJSONArray("formats")
                                val formatsParsed = mutableListOf<UIFormat>()
                                if (formatsArray != null) {
                                    for (i in 0 until formatsArray.length()) {
                                        val fObj = formatsArray.optJSONObject(i)
                                        if (fObj != null) {
                                            formatsParsed.add(
                                                UIFormat(
                                                    id = fObj.optString("id", "best"),
                                                    res = fObj.optInt("res", 0),
                                                    ext = fObj.optString("ext", "mp4"),
                                                    fps = fObj.optInt("fps", 30),
                                                    dash = fObj.optBoolean("dash", false),
                                                    size = fObj.optLong("size", 0L)
                                                )
                                            )
                                        }
                                    }
                                }
                                formatsList = formatsParsed
                            }
                        }, { err ->
                            phase = "error"
                            errorMessage = err
                        })
                    } else {
                        // Auto-check Clipboard as convenient shortcut for user
                        withContext(Dispatchers.Main) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            if (clipboard.hasPrimaryClip() && clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true) {
                                val item = clipboard.primaryClip?.getItemAt(0)
                                val text = item?.text?.toString() ?: ""
                                if (text.startsWith("http") && text.length > 10) {
                                    urlInput = text
                                }
                            }
                        }
                    }
                } else {
                    phase = "error"
                    errorMessage = "فشل بدء محرك التحميل المدمج. يرجى التحقق من توفر ذاكرة النظام."
                }
            } catch (e: Exception) {
                phase = "error"
                errorMessage = "خطأ تهيئة: ${e.message}"
            }
        }
    }

    // Modal Background Dim Tap Dismiss
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() }
            .background(Color(0xE005080E)), // Luxurious deep dynamic translucent navy
        contentAlignment = Alignment.Center
    ) {
        // Intercept card body touches so clicking the pop-up panel itself does not dismiss
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {} // Click blocking
                .border(
                    width = 2.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(borderGradientColor1, borderGradientColor2)
                    ),
                    shape = RoundedCornerShape(28.dp)
                ),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F131E)) // Dynamic high-end pitch navy screen
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                // Background Animated Cosmos Particles (drawn dynamically in Compose Canvas)
                Canvas(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(28.dp))
                ) {
                    drawIntoCanvas { canvas ->
                        particles.forEach { p ->
                            // calculate floating offset
                            val currentYFraction = (p.initialY - tickerY * p.speed) % 1.0f
                            val actualY = if (currentYFraction < 0) currentYFraction + 1f else currentYFraction
                            val xPos = p.initialX * size.width
                            val yPos = actualY * size.height

                            drawCircle(
                                color = p.color.copy(alpha = 0.22f),
                                radius = p.size,
                                center = Offset(xPos, yPos)
                            )
                        }
                    }
                }

                // Foreground components
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header Area
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .background(Color(0xFF1E2435), CircleShape)
                                .size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color(0xFFA0AEC0),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        
                        // Active label depending on stage
                        Text(
                            text = when (phase) {
                                "setup" -> "بدء النظام..."
                                "input" -> "تحميل سريع خارجي"
                                "selection" -> "خيارات التحميل المتوفرة"
                                "downloading" -> "جاري تحميل الوسيط..."
                                "finished" -> "تم التنزيل بنجاح!"
                                "error" -> "تنبيه بالنظام"
                                else -> "B-Ultra Bubble"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFDDE6F0)
                            )
                        )

                        // Visual logo indicator
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    color = Color(0xFF171B2A),
                                    shape = RoundedCornerShape(10.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "B",
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF63B3ED),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // ───────── PHASES RENDERING ─────────

                    when (phase) {
                        "setup" -> {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    color = Color(0xFF63B3ED),
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(42.dp)
                                )
                                Spacer(modifier = Modifier.height(20.dp))
                                Text(
                                    text = serverStatusMsg,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFFCBD5E0),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        "input" -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "تم نسخ رابط التحميل تلقائياً أو يمكنك لصق الرابط من الحافظة للبدء فوراً:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF718096),
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )

                                uInputRow(
                                    url = urlInput,
                                    onUrlChange = { urlInput = it },
                                    onPaste = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        if (clipboard.hasPrimaryClip()) {
                                            val cliptext = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                            if (cliptext.isNotEmpty()) {
                                                urlInput = cliptext
                                            }
                                        }
                                    }
                                )

                                Spacer(modifier = Modifier.height(24.dp))

                                Button(
                                    onClick = {
                                        if (urlInput.trim().isNotEmpty()) {
                                            isAnalyzing = true
                                            analyzeLink(urlInput, client, { isAnalyzing = it }, { p, results ->
                                                phase = p
                                                if (results != null) {
                                                    videoTitle = results.optString("title", "فيديو")
                                                    videoThumbUrl = results.optString("thumb", "")
                                                    val durSec = results.optInt("duration", 0)
                                                    videoDurationStr = formatDuration(durSec)
                                                    
                                                    val formatsArray = results.optJSONArray("formats")
                                                    val formatsParsed = mutableListOf<UIFormat>()
                                                    if (formatsArray != null) {
                                                        for (i in 0 until formatsArray.length()) {
                                                            val fObj = formatsArray.optJSONObject(i)
                                                            if (fObj != null) {
                                                                formatsParsed.add(
                                                                    UIFormat(
                                                                        id = fObj.optString("id", "best"),
                                                                        res = fObj.optInt("res", 0),
                                                                        ext = fObj.optString("ext", "mp4"),
                                                                        fps = fObj.optInt("fps", 30),
                                                                        dash = fObj.optBoolean("dash", false),
                                                                        size = fObj.optLong("size", 0L)
                                                                    )
                                                                )
                                                            }
                                                        }
                                                    }
                                                    formatsList = formatsParsed
                                                }
                                            }, { err ->
                                                phase = "error"
                                                errorMessage = err
                                            })
                                        }
                                    },
                                    enabled = !isAnalyzing && urlInput.trim().isNotEmpty(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF63B3ED),
                                        disabledContainerColor = Color(0xFF48556A)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(vertical = 12.dp)
                                ) {
                                    if (isAnalyzing) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            CircularProgressIndicator(
                                                color = Color(0xFF0F131E),
                                                strokeWidth = 2.dp,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                text = "جاري الفحص واستخراج المسارات...",
                                                color = Color(0xFF0F131E),
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = "🔍 فحص وتحليل الرابط",
                                            color = Color(0xFF0F131E),
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }

                        "selection" -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                // Mini Video Card details
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF171B2A), RoundedCornerShape(16.dp))
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Custom image receiver
                                    val bitmap = rememberImageFromUrl(videoThumbUrl)
                                    Box(
                                        modifier = Modifier
                                            .size(72.dp, 48.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF0D1018)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (bitmap != null) {
                                            Image(
                                                bitmap = bitmap,
                                                contentDescription = "Thumbnail",
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Playing",
                                                tint = Color(0xFF63B3ED),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = videoTitle,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = Color(0xFFE2E8F0)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "المدة الزمنية: $videoDurationStr",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF718096)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Toggle Type selection (Video or MP3 Audio Only)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF171B2A), RoundedCornerShape(12.dp))
                                        .padding(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(if (downloadMode == "video") Color(0xFF1E2435) else Color.Transparent)
                                            .clickable { downloadMode = "video" }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "🎬 تحميل فيديو",
                                            color = if (downloadMode == "video") Color(0xFF63B3ED) else Color(0xFFA0AEC0),
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(if (downloadMode == "audio") Color(0xFF1E2435) else Color.Transparent)
                                            .clickable { downloadMode = "audio" }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "🎵 MP3 صوتي فقط",
                                            color = if (downloadMode == "audio") Color(0xFF68D391) else Color(0xFFA0AEC0),
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // High fidelity selector grid
                                if (downloadMode == "video") {
                                    Text(
                                        text = "الدقة وخيارات النقاء المتوفرة:",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                        color = Color(0xFF718096),
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )

                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(2),
                                        modifier = Modifier.heightIn(max = 140.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(formatsList) { format ->
                                            val isSelected = selectedFormatId == format.id
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (isSelected) Color(0xFF1E293B) else Color(0xFF171B2A))
                                                    .border(
                                                        width = 1.dp,
                                                        color = if (isSelected) Color(0xFF63B3ED) else Color.Transparent,
                                                        shape = RoundedCornerShape(10.dp)
                                                    )
                                                    .clickable { selectedFormatId = format.id }
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                contentAlignment = Alignment.CenterStart
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.PlayArrow,
                                                        contentDescription = "Format",
                                                        tint = if (isSelected) Color(0xFF63B3ED) else Color(0xFFA0AEC0),
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Column {
                                                        Text(
                                                            text = "${format.res}p (${format.ext.uppercase()})",
                                                            color = Color(0xFFE2E8F0),
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                        if (format.size > 0L) {
                                                            Text(
                                                                text = formatSize(format.size),
                                                                color = Color(0xFF68D391),
                                                                fontSize = 11.sp,
                                                                fontFamily = FontFamily.Monospace
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    // Audio description card
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF171B2A)),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Music",
                                                tint = Color(0xFF68D391),
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                text = "يقوم النظام باستخلاص المسار الصوتي وتحويل الصوت بدقة عالية لنسق MP3 (192kbps) بنقاء فائق.",
                                                color = Color(0xFFCBD5E0),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(20.dp))

                                // Trigger Download
                                Button(
                                    onClick = {
                                        phase = "downloading"
                                        val fId = if (downloadMode == "audio") "bestaudio/best" else (selectedFormatId ?: "best")
                                        triggerDownload(urlInput, fId, downloadMode, client, scope, { stateObj ->
                                            downloadPercent = stateObj.optDouble("percent", 0.0).toFloat()
                                            downloadSpeed = stateObj.optString("speed", "جارٍ التحميل...")
                                            downloadEta = stateObj.optString("eta", "--:--")
                                            downloadStep = stateObj.optString("step", "تنزيل الملف...")
                                            downloadFileName = stateObj.optString("filename", "")
                                            val currentPhase = stateObj.optString("phase", "downloading")
                                            
                                            if (currentPhase == "finished") {
                                                phase = "finished"
                                            } else if (currentPhase == "error") {
                                                phase = "error"
                                                errorMessage = stateObj.optString("error", "حدث عطل بروتوكولي أثناء النقل")
                                            }
                                        }, { err ->
                                            phase = "error"
                                            errorMessage = err
                                        })
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (downloadMode == "audio") Color(0xFF68D391) else Color(0xFF63B3ED)
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(vertical = 12.dp)
                                ) {
                                    Text(
                                        text = "⬇️ ابدأ التحميل الآن في الخلفية",
                                        color = Color(0xFF0F131E),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }

                        "downloading" -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = downloadStep,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = Color(0xFFE2E8F0)
                                    )
                                    Text(
                                        text = downloadSpeed,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = Color(0xFF68D391)
                                    )
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // Progress linear indicator with premium gradient flow
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF171B2A))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(downloadPercent / 100f)
                                            .background(
                                                Brush.linearGradient(
                                                    colors = listOf(Color(0xFF63B3ED), Color(0xFF68D391))
                                                )
                                            )
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${downloadPercent}%",
                                        color = Color(0xFF63B3ED),
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = "الوقت المتبقي: $downloadEta",
                                        color = Color(0xFFA0AEC0),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                Spacer(modifier = Modifier.height(24.dp))

                                Button(
                                    onClick = {
                                        cancelDownload(client, scope) {
                                            phase = "input"
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x1AFC8181)),
                                    border = BorderStroke(1.dp, Color(0x4DFC8181)),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "✕ إلغاء عملية التحميل وحذف المؤقت",
                                        color = Color(0xFFFC8181),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }

                        "finished" -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .background(Color(0x1A68D391), CircleShape)
                                        .border(2.dp, Color(0xFF68D391), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "✓",
                                        fontWeight = FontWeight.Black,
                                        color = Color(0xFF68D391),
                                        fontSize = 28.sp
                                    )
                                }

                                Spacer(modifier = Modifier.height(20.dp))

                                Text(
                                    text = "اكتمل التثبيت والتحميل بنجاح!",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFF68D391)
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    text = downloadFileName,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = Color(0xFFA0AEC0),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .background(Color(0xFF171B2A), RoundedCornerShape(10.dp))
                                        .padding(12.dp)
                                )

                                Spacer(modifier = Modifier.height(24.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            phase = "input"
                                            urlInput = ""
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF63B3ED)),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "تحميل جديد",
                                            color = Color(0xFF0F131E),
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }

                                    Button(
                                        onClick = onDismiss,
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2435)),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "إغلاق النافذة",
                                            color = Color(0xFFA0AEC0),
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }

                        "error" -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "⚠️",
                                    fontSize = 42.sp
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    text = "تحذير: عطل ببروتوكول النظام",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFC8181)
                                    )
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1215)),
                                    border = BorderStroke(1.dp, Color(0x3DFC8181)),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = errorMessage,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = Color(0xFFFC8181),
                                        modifier = Modifier.padding(14.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(24.dp))

                                Button(
                                    onClick = {
                                        phase = "input"
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF63B3ED)),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "🔄 العودة والتحقق مجدداً",
                                        color = Color(0xFF0F131E),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun uInputRow(
    url: String,
    onUrlChange: (String) -> Unit,
    onPaste: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            placeholder = { Text("لصق أو كتابة رابط الفيديو هنا...") },
            maxLines = 1,
            textStyle = LocalTextStyle.current.copy(
                fontSize = 13.sp,
                textAlign = TextAlign.Left,
                fontFamily = FontFamily.Monospace
            ),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF171B2A),
                unfocusedContainerColor = Color(0xFF121524),
                focusedBorderColor = Color(0xFF63B3ED),
                unfocusedBorderColor = Color(0x3DB3D8FF),
                focusedTextColor = Color(0xFFDDE6F0),
                unfocusedTextColor = Color(0xFFDDE6F0),
                focusedPlaceholderColor = Color(0xFF48556A),
                unfocusedPlaceholderColor = Color(0xFF48556A)
            ),
            modifier = Modifier.weight(1f)
        )

        Button(
            onClick = onPaste,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0x3D63B3ED)),
            border = BorderStroke(1.dp, Color(0x4D63B3ED)),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            modifier = Modifier.height(54.dp)
        ) {
            Text(
                text = "لصق",
                color = Color(0xFF63B3ED),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }
    }
}

// REST Network API helper calls
fun analyzeLink(
    url: String,
    client: OkHttpClient,
    onAnalyzingChange: (Boolean) -> Unit,
    onSuccess: (String, JSONObject?) -> Unit,
    onError: (String) -> Unit
) {
    onAnalyzingChange(true)
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val jsonBody = JSONObject().put("url", url).toString()
    val request = Request.Builder()
        .url("http://127.0.0.1:8000/analyze")
        .post(jsonBody.toRequestBody(mediaType))
        .build()

    Thread {
        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                val resObj = JSONObject(bodyStr)
                if (resObj.has("error")) {
                    onError(resObj.optString("error"))
                } else {
                    onSuccess("selection", resObj)
                }
            }
        } catch (e: Exception) {
            Log.e("B-Ultra", "API Analysis failure", e)
            onError("عطل في الاتصال بالشبكة أو بمحرك يوتيوب المخفي: ${e.message}")
        } finally {
            onAnalyzingChange(false)
        }
    }.start()
}

// REST trigger network download
fun triggerDownload(
    url: String,
    formatId: String,
    mode: String,
    client: OkHttpClient,
    scope: kotlinx.coroutines.CoroutineScope,
    onProgressUpdate: (JSONObject) -> Unit,
    onError: (String) -> Unit
) {
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val jsonBody = JSONObject()
        .put("url", url)
        .put("format_id", formatId)
        .put("mode", mode)
        .toString()

    val request = Request.Builder()
        .url("http://127.0.0.1:8000/download")
        .post(jsonBody.toRequestBody(mediaType))
        .build()

    Thread {
        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                val resObj = JSONObject(bodyStr)
                val status = resObj.optString("status")
                if (status == "started") {
                    // Poll progress real-time with coroutine scope
                    scope.launch {
                        while (true) {
                            delay(600)
                            try {
                                val progressReq = Request.Builder().url("http://127.0.0.1:8000/progress").build()
                                withContext(Dispatchers.IO) {
                                    client.newCall(progressReq).execute().use { pr ->
                                        val pStr = pr.body?.string() ?: ""
                                        val pObj = JSONObject(pStr)
                                        withContext(Dispatchers.Main) {
                                            onProgressUpdate(pObj)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("B-Ultra", "Progress poll issue", e)
                            }
                        }
                    }
                } else if (status == "busy") {
                    onError("النظام مشغول حالياً بعملية تحميل أخرى. يرجى الانتظار حتى تكتمل.")
                } else {
                    onError("الجهاز الخلفي رفض طلب التنزيل لسبب مجهول")
                }
            }
        } catch (e: Exception) {
            onError("فشل الاتصال بالشبكة لبدء التنزيل: ${e.message}")
        }
    }.start()
}

// REST cancellation trigger
fun cancelDownload(
    client: OkHttpClient,
    scope: kotlinx.coroutines.CoroutineScope,
    onCancelled: () -> Unit
) {
    val request = Request.Builder().url("http://127.0.0.1:8000/cancel").build()
    Thread {
        try {
            client.newCall(request).execute().use { _ -> }
        } catch (e: Exception) {
            Log.e("B-Ultra", "Failed executing cancellation", e)
        } finally {
            scope.launch {
                onCancelled()
            }
        }
    }.start()
}

// Duration string formatting helper
fun formatDuration(sec: Int): String {
    if (sec <= 0) return "مجهول"
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) {
        String.format("%d:%02d:%02d", h, m, s)
    } else {
        String.format("%02d:%02d", m, s)
    }
}

// Size formatting helper
fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    if (bytes >= 1_073_741_824L) {
        return String.format("%.2f GB", bytes / 1_073_741_824f)
    }
    if (bytes >= 1_048_576L) {
        return String.format("%.1f MB", bytes / 1_048_576f)
    }
    return String.format("%.0f KB", bytes / 1024f)
}

// Helper Composable to fetch bitmap from network asynchronously
@Composable
fun rememberImageFromUrl(url: String): ImageBitmap? {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        if (url.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null) {
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) {
                                bitmap = bmp.asImageBitmap()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("B-Ultra", "Failed decoding image bitmap", e)
            }
        }
    }
    return bitmap
}
