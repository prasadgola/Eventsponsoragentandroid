package com.example.eventsponsorassistant

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
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
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


// --- Retrofit setup for networking ---

interface ApiService {
    @POST("run")
    suspend fun sendMessage(@Body request: ApiRequest): Response<ApiResponse>
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
    val author: Author
)

// --- ViewModel to hold our app's state and logic ---
class ChatViewModel : ViewModel() {
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

    // --- INTENTS (User Actions) ---
    fun onTextInputChanged(newText: String) {
        textInput = newText
    }

    fun toggleTheme() {
        isDarkTheme = !isDarkTheme
    }

    // NEW: Function to refresh the chat
    fun refreshChat() {
        _messages.clear()
        showWelcomeScreen = true
        textInput = ""
        isLoading = false
    }

    // --- SIMPLIFIED: sendMessage now only calls the /run endpoint ---
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

        isLoading = true
        viewModelScope.launch {
            try {
                val request = ApiRequest(
                    new_message = NewMessage(parts = listOf(MessagePart(text = messageText)))
                )

                // The backend proxy handles session creation, so we only need to call /run
                val response = RetrofitClient.instance.sendMessage(request)

                if (response.isSuccessful) {
                    handleSuccessfulResponse(response.body())
                } else {
                    // This handles non-404 errors like 500, 403, etc.
                    handleApiError("API Error: ${response.code()} - ${response.message()}")
                }

            } catch (e: Exception) {
                // Handle network-level errors (no internet, DNS issues, etc.)
                e.printStackTrace()
                handleApiError("Network Error: Could not connect to the server. Please check your connection and the URL.")
            } finally {
                isLoading = false
            }
        }
    }

    private fun handleSuccessfulResponse(responseBody: ApiResponse?) {
        val assistantMessage = responseBody?.firstOrNull()?.content?.parts?.firstOrNull()?.text
        if (assistantMessage != null) {
            _messages.add(ChatMessage(text = assistantMessage, author = Author.ASSISTANT))
        } else {
            _messages.add(ChatMessage(text = "Sorry, I received an empty response from the server.", author = Author.ASSISTANT))
        }
    }

    private fun handleApiError(errorMessage: String) {
        _messages.add(ChatMessage(text = errorMessage, author = Author.ASSISTANT))
    }

    fun sendSuggestion(suggestionText: String) {
        onTextInputChanged(suggestionText)
        sendMessage(fromSuggestion = true)
    }
}

// --- App Theme Colors (matching your CSS) ---
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    background = Color(0xFF212121),
    surface = Color(0xFF303134),
    onBackground = Color(0xFFE8EAED),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF3C4043), // For borders and dividers
    secondaryContainer = Color(0xFF2D2D30), // User message bubble
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1A73E8),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF8F9FA),
    onBackground = Color(0xFF202124),
    onSurface = Color(0xFF202124),
    surfaceVariant = Color(0xFFDADCE0), // For borders and dividers
    secondaryContainer = Color(0xFFF1F3F4), // User message bubble
)

// --- Main Activity ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel = remember { ChatViewModel() }
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
        topBar = { AppHeader(
            isDarkTheme = viewModel.isDarkTheme,
            onThemeToggle = { viewModel.toggleTheme() },
            onLogoClick = { viewModel.refreshChat() } // Pass refresh action
        ) },
        bottomBar = { MessageInputArea(
            value = viewModel.textInput,
            onValueChange = { viewModel.onTextInputChanged(it) },
            onSendClick = { viewModel.sendMessage() }
        ) }
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
fun AppHeader(isDarkTheme: Boolean, onThemeToggle: () -> Unit, onLogoClick: () -> Unit) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onLogoClick), // Make logo clickable
                    contentAlignment = Alignment.Center
                ) {
                    Text("ES", color = MaterialTheme.colorScheme.background, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(16.dp))
                Text("Event Sponsor Assistant", fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground)
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
            // UI FIX: Added IntrinsicSize.Min to make cards in a row the same height
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowItems.forEach { (title, prompt) ->
                    SuggestionCard(
                        title = title,
                        description = prompt,
                        onClick = { onSuggestionClick(prompt) },
                        // UI FIX: Added fillMaxHeight to make card expand to row height
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
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    if (value.isEmpty()) {
                        Text("Ask me about event sponsorship...", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                    innerTextField()
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

// Helper for Preview
private val ColorScheme.isLight get() = this.background == LightColorScheme.background

@Preview(showBackground = true, name = "Light Mode Preview")
@Composable
fun LightPreview() {
    MaterialTheme(colorScheme = LightColorScheme) {
        Surface(color = MaterialTheme.colorScheme.background) {
            val previewViewModel = ChatViewModel()
            EventSponsorApp(previewViewModel)
        }
    }
}

@Preview(showBackground = true, name = "Dark Mode Preview")
@Composable
fun DarkPreview() {
    MaterialTheme(colorScheme = DarkColorScheme) {
        Surface(color = MaterialTheme.colorScheme.background) {
            val previewViewModel = ChatViewModel()
            EventSponsorApp(previewViewModel)
        }
    }
}

