package com.example.eventsponsorassistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.UUID

// --- Data classes for API communication (matching JSON structure) ---

data class MessagePart(val text: String)
data class NewMessage(val role: String = "user", val parts: List<MessagePart>)
data class ApiRequest(
    val app_name: String = "chat_with_human",
    val user_id: String = "demo_user",
    val session_id: String = "default_session",
    val new_message: NewMessage
)

data class ApiContent(val parts: List<MessagePart>)
data class ApiEvent(val content: ApiContent)
typealias ApiResponse = List<ApiEvent>

// Session creation request
data class SessionState(val state: Map<String, Any> = emptyMap())

// --- Retrofit setup for networking ---

interface ApiService {
    @POST("run")
    suspend fun sendMessage(@Body request: ApiRequest): Response<ApiResponse>

    @POST("apps/{app_name}/users/{user_id}/sessions/{session_id}")
    suspend fun createSession(
        @Path("app_name") appName: String,
        @Path("user_id") userId: String,
        @Path("session_id") sessionId: String,
        @Body sessionState: SessionState
    ): Response<Any>
}

object RetrofitClient {
    private const val BASE_URL = "https://adk-backend-service-766291037876.us-central1.run.app/"

    val instance: ApiService by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        retrofit.create(ApiService::class.java)
    }
}

// --- Data Classes to represent our chat messages ---
enum class Author { USER, ASSISTANT }
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val author: Author,
    val isOffline: Boolean = false // Track if message was sent offline
)

// --- ViewModel to hold our app's state and logic ---
class ChatViewModel(private val context: Context) : ViewModel() {
    // --- STATE ---
    private val _messages = mutableStateListOf<ChatMessage>()
    val messages: List<ChatMessage> = _messages

    var textInput by mutableStateOf("")
        private set

    var isLoading by mutableStateOf(false)
        private set

    var showWelcomeScreen by mutableStateOf(true)
        private set

    var isDarkTheme by mutableStateOf(true)
        private set

    var nanoAvailability by mutableStateOf<NanoAvailability>(NanoAvailability.NotSupported)
        private set

    var showOfflineIndicator by mutableStateOf(false)
        private set

    var downloadProgress by mutableStateOf(0f)
        private set

    private val geminiNanoManager = GeminiNanoManager(context)

    init {
        // Check Nano availability on init
        viewModelScope.launch {
            checkNanoAvailability()
        }
    }

    // --- INTENTS (User Actions) ---
    fun onTextInputChanged(newText: String) {
        textInput = newText
    }

    fun toggleTheme() {
        isDarkTheme = !isDarkTheme
    }

    fun refreshChat() {
        _messages.clear()
        showWelcomeScreen = true
        textInput = ""
        isLoading = false
    }

    private fun isOnline(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun checkNanoAvailability() {
        nanoAvailability = geminiNanoManager.checkAvailability()
        updateOfflineIndicator()
    }

    private fun updateOfflineIndicator() {
        val offline = !isOnline()
        showOfflineIndicator = offline
    }

    fun downloadNanoModel() {
        viewModelScope.launch {
            nanoAvailability = NanoAvailability.Downloading
            downloadProgress = 0f

            val success = geminiNanoManager.downloadModel { progress ->
                downloadProgress = progress
            }

            if (success) {
                nanoAvailability = NanoAvailability.Available
                _messages.add(
                    ChatMessage(
                        text = "✅ Offline AI model downloaded! You can now chat offline.",
                        author = Author.ASSISTANT
                    )
                )
            } else {
                nanoAvailability = NanoAvailability.DownloadRequired
                _messages.add(
                    ChatMessage(
                        text = "❌ Failed to download offline AI model. Please try again.",
                        author = Author.ASSISTANT
                    )
                )
            }
            downloadProgress = 0f
        }
    }

    fun sendMessage(fromSuggestion: Boolean = false) {
        val messageText = textInput.trim()
        if (messageText.isEmpty() || isLoading) return

        if (showWelcomeScreen) {
            showWelcomeScreen = false
        }

        _messages.add(ChatMessage(text = messageText, author = Author.USER))

        if (!fromSuggestion) {
            textInput = ""
        }

        // Check if offline and Nano is available
        val offline = !isOnline()
        updateOfflineIndicator()

        if (offline && nanoAvailability == NanoAvailability.Available) {
            // Use Gemini Nano
            sendNanoMessage(messageText)
        } else if (offline) {
            // Offline and Nano not available
            _messages.add(
                ChatMessage(
                    text = "You are offline. Please connect to the internet or download the offline AI model.",
                    author = Author.ASSISTANT
                )
            )
        } else {
            // Online - use backend
            sendBackendMessage(messageText)
        }
    }

    private fun sendNanoMessage(messageText: String) {
        isLoading = true
        viewModelScope.launch {
            try {
                val response = geminiNanoManager.generateResponse(messageText)
                _messages.add(
                    ChatMessage(
                        text = response,
                        author = Author.ASSISTANT,
                        isOffline = true
                    )
                )
            } catch (e: Exception) {
                _messages.add(
                    ChatMessage(
                        text = "Sorry, I encountered an error: ${e.message}",
                        author = Author.ASSISTANT
                    )
                )
            } finally {
                isLoading = false
            }
        }
    }

    private fun sendBackendMessage(messageText: String) {
        isLoading = true
        viewModelScope.launch {
            try {
                val request = ApiRequest(
                    new_message = NewMessage(parts = listOf(MessagePart(text = messageText)))
                )

                val response = RetrofitClient.instance.sendMessage(request)

                if (response.isSuccessful) {
                    handleSuccessfulResponse(response.body())
                } else if (response.code() == 404) {
                    val sessionCreated = createSession(
                        request.app_name,
                        request.user_id,
                        request.session_id
                    )

                    if (sessionCreated) {
                        val retryResponse = RetrofitClient.instance.sendMessage(request)

                        if (retryResponse.isSuccessful) {
                            handleSuccessfulResponse(retryResponse.body())
                        } else {
                            handleApiError("API Error after retry: ${retryResponse.code()} - ${retryResponse.message()}")
                        }
                    } else {
                        handleApiError("Failed to create session. Please try again.")
                    }
                } else {
                    handleApiError("API Error: ${response.code()} - ${response.message()}")
                }

            } catch (e: Exception) {
                e.printStackTrace()
                handleApiError("Network Error: Could not connect to the server. Please check your connection.")
            } finally {
                isLoading = false
            }
        }
    }

    private suspend fun createSession(appName: String, userId: String, sessionId: String): Boolean {
        return try {
            val sessionState = SessionState(state = emptyMap())
            val response = RetrofitClient.instance.createSession(
                appName = appName,
                userId = userId,
                sessionId = sessionId,
                sessionState = sessionState
            )

            response.isSuccessful
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun handleSuccessfulResponse(responseBody: ApiResponse?) {
        val textParts = mutableListOf<String>()

        responseBody?.forEach { event ->
            event.content.parts.forEach { part ->
                if (!part.text.isNullOrEmpty()) {
                    textParts.add(part.text)
                }
            }
        }

        val assistantMessage = if (textParts.isNotEmpty()) {
            textParts.joinToString("\n\n")
        } else {
            "Sorry, I received an empty response from the server."
        }

        _messages.add(ChatMessage(text = assistantMessage, author = Author.ASSISTANT))
    }

    private fun handleApiError(errorMessage: String) {
        _messages.add(ChatMessage(text = errorMessage, author = Author.ASSISTANT))
    }

    fun sendSuggestion(suggestionText: String) {
        onTextInputChanged(suggestionText)
        sendMessage(fromSuggestion = true)
    }

    override fun onCleared() {
        super.onCleared()
        geminiNanoManager.cleanup()
    }
}

// --- App Theme Colors ---
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    background = Color(0xFF212121),
    surface = Color(0xFF303134),
    onBackground = Color(0xFFE8EAED),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF3C4043),
    secondaryContainer = Color(0xFF2D2D30),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1A73E8),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF8F9FA),
    onBackground = Color(0xFF202124),
    onSurface = Color(0xFF202124),
    surfaceVariant = Color(0xFFDADCE0),
    secondaryContainer = Color(0xFFF1F3F4),
)

// --- Main Activity ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel = remember { ChatViewModel(applicationContext) }
            val isDarkTheme = viewModel.isDarkTheme

            MaterialTheme(
                colorScheme = if (isDarkTheme) DarkColorScheme else LightColorScheme
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    EventSponsorApp(viewModel)
                }
            }
        }
    }
}

// --- Main App Composable ---
@Composable
fun EventSponsorApp(viewModel: ChatViewModel) {
    Scaffold(
        topBar = {
            AppHeader(
                isDarkTheme = viewModel.isDarkTheme,
                onThemeToggle = { viewModel.toggleTheme() },
                onLogoClick = { viewModel.refreshChat() },
                nanoAvailability = viewModel.nanoAvailability,
                showOfflineIndicator = viewModel.showOfflineIndicator,
                downloadProgress = viewModel.downloadProgress,
                onDownloadClick = { viewModel.downloadNanoModel() }
            )
        },
        bottomBar = {
            MessageInputArea(
                value = viewModel.textInput,
                onValueChange = { viewModel.onTextInputChanged(it) },
                onSendClick = { viewModel.sendMessage() }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            WelcomeScreen(
                visible = viewModel.showWelcomeScreen,
                onSuggestionClick = { viewModel.sendSuggestion(it) }
            )
            ChatScreen(
                visible = !viewModel.showWelcomeScreen,
                messages = viewModel.messages,
                isLoading = viewModel.isLoading
            )
        }
    }
}

// --- UI Components ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppHeader(
    isDarkTheme: Boolean,
    onThemeToggle: () -> Unit,
    onLogoClick: () -> Unit,
    nanoAvailability: NanoAvailability,
    showOfflineIndicator: Boolean,
    downloadProgress: Float,
    onDownloadClick: () -> Unit
) {
    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onLogoClick),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "ES",
                        color = MaterialTheme.colorScheme.background,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    "Event Sponsor",
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )

                // Offline/Nano Status Indicator
                if (showOfflineIndicator) {
                    OfflineStatusBadge(
                        nanoAvailability = nanoAvailability,
                        downloadProgress = downloadProgress,
                        onDownloadClick = onDownloadClick
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onThemeToggle) {
                Icon(
                    imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = "Toggle Theme",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        )
    )
}

@Composable
fun OfflineStatusBadge(
    nanoAvailability: NanoAvailability,
    downloadProgress: Float,
    onDownloadClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = when (nanoAvailability) {
            is NanoAvailability.Available -> Color(0xFF34a853)
            is NanoAvailability.Downloading -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.clickable(
            enabled = nanoAvailability is NanoAvailability.DownloadRequired
        ) {
            if (nanoAvailability is NanoAvailability.DownloadRequired) {
                onDownloadClick()
            }
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            when (nanoAvailability) {
                is NanoAvailability.Available -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.White
                    )
                    Text(
                        "Offline AI",
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }
                is NanoAvailability.Downloading -> {
                    CircularProgressIndicator(
                        progress = downloadProgress,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Text(
                        "${(downloadProgress * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }
                is NanoAvailability.DownloadRequired -> {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Download AI",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                else -> {
                    Icon(
                        Icons.Default.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Offline",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun WelcomeScreen(visible: Boolean, onSuggestionClick: (String) -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(500)) + slideInVertically(initialOffsetY = { -40 }),
        exit = fadeOut(animationSpec = tween(500))
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(16.dp)
        ) {
            val gradientColors = if(MaterialTheme.colorScheme.isLight) {
                listOf(Color(0xFF1A73E8), Color(0xFF4285F4), Color(0xFF8430CE))
            } else {
                listOf(Color(0xFF8AB4F8), Color(0xFFA8C7FA), Color(0xFFC58AF9))
            }

            Text(
                text = "Hello, there!",
                style = TextStyle(
                    brush = Brush.linearGradient(gradientColors),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Normal
                ),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "How can I help you with event sponsorship today?",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(48.dp))
            SuggestionGrid(onSuggestionClick)
        }
    }
}

@Composable
fun SuggestionGrid(onSuggestionClick: (String) -> Unit) {
    val suggestions = listOf(
        "Find Sponsors" to "Help me find potential sponsors for a tech conference",
        "Proposal Template" to "Create a sponsorship proposal template",
        "Package Ideas" to "What are effective sponsorship packages?",
        "Outreach Tips" to "Best practices for sponsor outreach"
    )

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(suggestions.chunked(2)) { rowItems ->
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowItems.forEach { (title, prompt) ->
                    SuggestionCard(
                        title = title,
                        description = prompt,
                        onClick = { onSuggestionClick(prompt) },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
fun SuggestionCard(title: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(24.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(description, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), lineHeight = 20.sp)
        }
    }
}

@Composable
fun ChatScreen(visible: Boolean, messages: List<ChatMessage>, isLoading: Boolean) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(500, delayMillis = 300)),
        exit = fadeOut(animationSpec = tween(500))
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(message)
            }
            if (isLoading) {
                item {
                    LoadingBubble()
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.author == Author.USER
    val horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    val bubbleShape = if (isUser) {
        RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isUser) {
            Avatar("ES")
            Spacer(Modifier.width(8.dp))
        }

        Column {
            Surface(
                color = bubbleColor,
                shape = bubbleShape,
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Show offline indicator for offline messages
            if (message.isOffline) {
                Row(
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Default.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Text(
                        "Offline AI",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }

        if (isUser) {
            Spacer(Modifier.width(8.dp))
            Avatar("You")
        }
    }
}

@Composable
fun LoadingBubble() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        Avatar("ES")
        Spacer(Modifier.width(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
        ) {
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Thinking", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(start = 8.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
fun Avatar(text: String) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = MaterialTheme.colorScheme.background, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun MessageInputArea(value: String, onValueChange: (String) -> Unit, onSendClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .padding(bottom = 8.dp)
                .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 12.dp, vertical = 16.dp)
                    .wrapContentHeight(Alignment.CenterVertically),
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (value.isEmpty()) {
                            Text(
                                "Ask me about event sponsorship...",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                fontWeight = FontWeight.Normal
                            )
                        }
                        innerTextField()
                    }
                }
            )
            val hasText = value.isNotBlank()
            val micAction = { /* TODO: Add voice input logic */ }
            val action = if(hasText) onSendClick else micAction

            val icon = if(hasText) Icons.Default.Send else Icons.Default.Mic
            val contentDescription = if(hasText) "Send Message" else "Use Voice"

            IconButton(
                onClick = action,
                enabled = hasText,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
            ) {
                Icon(icon, contentDescription = contentDescription)
            }
        }
    }
}

private val ColorScheme.isLight get() = this.background == LightColorScheme.background