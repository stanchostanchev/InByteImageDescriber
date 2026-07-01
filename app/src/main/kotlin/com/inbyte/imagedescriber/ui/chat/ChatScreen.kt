package com.inbyte.imagedescriber.ui.chat

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.inbyte.imagedescriber.model.ChatMessage
import com.inbyte.imagedescriber.model.MessageRole

// Set to true to bring back the text/image/camera input bar at the bottom of the Story tab
private const val SHOW_CHAT_INPUT_BAR = false

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) listState.animateScrollToItem(uiState.messages.size - 1)
    }
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Samodyva", style = MaterialTheme.typography.titleLarge)
                        Text("Story", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier.statusBarsPadding(),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (uiState.messages.isEmpty() && uiState.loadingMessage == null) {
                        item {
                            Box(
                                modifier = Modifier.fillParentMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "Tap the photo icon to select an image, then tap Send to describe it.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(32.dp),
                                )
                            }
                        }
                    } else {
                        items(uiState.messages, key = { it.id }) { message ->
                            ChatBubble(
                                message = message,
                                onUserBubbleClick = { text -> viewModel.onInputTextChange(text) },
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = uiState.isGenerating && uiState.matchedCategory != null) {
                    uiState.matchedCategory?.let { category ->
                        Text(
                            text = "📖 Fairy tale · $category",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                        )
                    }
                }

                AnimatedVisibility(visible = uiState.pendingImageUri != null) {
                    uiState.pendingImageUri?.let { uri ->
                        PendingImagePreview(
                            uri = uri,
                            onClear = viewModel::clearPendingImage,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }

                if (SHOW_CHAT_INPUT_BAR) {
                    ChatInputBar(
                        text = uiState.inputText,
                        isGenerating = uiState.isGenerating,
                        isModelLoaded = uiState.isModelLoaded,
                        onTextChange = viewModel::onInputTextChange,
                        onImageSelect = viewModel::onImageSelected,
                        onSend = viewModel::sendMessage,
                        onStop = viewModel::stopGeneration,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                    )
                }
            }

            // Loading overlay while model initialises
            uiState.loadingMessage?.let { msg ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "SmolVLM-500M · on-device inference",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
    onUserBubbleClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == MessageRole.USER
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) { AssistantAvatar(); Spacer(Modifier.width(8.dp)) }
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            message.imageUri?.let { uri ->
                AsyncImage(
                    model = uri,
                    contentDescription = "Attached image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(180.dp).clip(RoundedCornerShape(12.dp)),
                )
                Spacer(Modifier.height(4.dp))
            }
            if (message.text.isNotEmpty() || message.isStreaming) {
                val context = LocalContext.current
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isUser)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    shape = RoundedCornerShape(
                        topStart = if (isUser) 16.dp else 4.dp,
                        topEnd = if (isUser) 4.dp else 16.dp,
                        bottomStart = 16.dp, bottomEnd = 16.dp,
                    ),
                    modifier = if (isUser && message.text.isNotEmpty())
                        Modifier.clickable { onUserBubbleClick(message.text) }
                    else Modifier,
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        if (!isUser && !message.isStreaming) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                IconButton(
                                    onClick = {
                                        val drawingUri = message.imageUri
                                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            if (drawingUri != null) {
                                                type = "image/*"
                                                putExtra(android.content.Intent.EXTRA_STREAM, drawingUri)
                                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            } else {
                                                type = "text/plain"
                                            }
                                            putExtra(android.content.Intent.EXTRA_TEXT, message.text)
                                        }
                                        context.startActivity(android.content.Intent.createChooser(intent, "Share fairy tale"))
                                    },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Share,
                                        contentDescription = "Share fairy tale",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = message.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isUser) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (message.isStreaming) { Spacer(Modifier.width(4.dp)); StreamingCursor() }
                        }
                    }
                }
            }
        }
        if (isUser) { Spacer(Modifier.width(8.dp)); UserAvatar() }
    }
}

@Composable
private fun StreamingCursor() {
    val alpha by rememberInfiniteTransition(label = "cursor").animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        modifier = Modifier
            .size(2.dp, 14.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)),
    )
}

@Composable
private fun AssistantAvatar() {
    Box(
        modifier = Modifier.size(32.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Samodyva",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 6.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun UserAvatar() {
    Box(
        modifier = Modifier.size(32.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text("U", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

@Composable
private fun PendingImagePreview(uri: Uri, onClear: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        AsyncImage(
            model = uri, contentDescription = "Pending image",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(80.dp).clip(RoundedCornerShape(8.dp)),
        )
        IconButton(
            onClick = onClear,
            modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                .clip(CircleShape).background(MaterialTheme.colorScheme.error),
        ) {
            Icon(Icons.Default.Close, "Remove image",
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ChatInputBar(
    text: String,
    isGenerating: Boolean,
    isModelLoaded: Boolean,
    onTextChange: (String) -> Unit,
    onImageSelect: (Uri) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(onImageSelect)
    }

    var cameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraUri?.let(onImageSelect)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val uri = createCameraUri(context)
            cameraUri = uri
            camera.launch(uri)
        }
    }

    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            enabled = isModelLoaded && !isGenerating,
        ) {
            Icon(
                Icons.Default.AddPhotoAlternate, "Pick image",
                tint = if (isModelLoaded) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.outline,
            )
        }

        IconButton(
            onClick = { cameraPermission.launch(Manifest.permission.CAMERA) },
            enabled = isModelLoaded && !isGenerating,
        ) {
            Icon(
                Icons.Default.CameraAlt, "Take photo",
                tint = if (isModelLoaded) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.outline,
            )
        }

        OutlinedTextField(
            value = text, onValueChange = onTextChange,
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            placeholder = { Text(if (isModelLoaded) "" else "Loading model…") },
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = { onTextChange(""); focusRequester.requestFocus() }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear text")
                    }
                }
            },
            enabled = isModelLoaded && !isGenerating,
            maxLines = 4,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, keyboardType = KeyboardType.Text),
            keyboardActions = KeyboardActions(onSend = { keyboard?.hide(); onSend() }),
        )

        Spacer(Modifier.width(8.dp))

        if (isGenerating) {
            IconButton(
                onClick = onStop,
                modifier = Modifier.size(48.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
            ) {
                Icon(
                    Icons.Default.Close, "Stop generation",
                    tint = MaterialTheme.colorScheme.onError,
                )
            }
        } else {
            IconButton(
                onClick = { keyboard?.hide(); onSend() },
                enabled = isModelLoaded,
                modifier = Modifier.size(48.dp).clip(CircleShape).background(
                    if (isModelLoaded) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Icon(
                    Icons.Default.Send, "Send",
                    tint = if (isModelLoaded) MaterialTheme.colorScheme.onPrimary
                           else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

private fun createCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").also { it.mkdirs() }
    val file = File.createTempFile("photo_", ".jpg", dir)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
