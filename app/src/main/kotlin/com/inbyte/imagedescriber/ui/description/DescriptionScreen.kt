package com.inbyte.imagedescriber.ui.description

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.inbyte.imagedescriber.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DescriptionScreen(
    selectedImageUri: String? = null,
    onGenerateFairyTale: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: DescriptionViewModel = hiltViewModel(),
) {
    if (selectedImageUri != null) {
        viewModel.setImageAndDescribe(selectedImageUri)
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_photo),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Samodyva", style = MaterialTheme.typography.titleLarge)
                        Text("Description", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            windowInsets = WindowInsets(0, 0, 0, 0),
        )

        DescriptionBody(
            viewModel = viewModel,
            onGenerateFairyTale = onGenerateFairyTale,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

/** Shared body content (image, describe button, classification, description) reused by both
 *  the full Description tab and the bottom sheet shown after tapping an image in the Images tab. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DescriptionBody(
    viewModel: DescriptionViewModel,
    onGenerateFairyTale: (String) -> Unit = {},
    showDescriptionCard: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val isDescribing = uiState.isGenerating || uiState.loadingMessage != null
    var revealDescription by remember(uiState.imageUri) { mutableStateOf(false) }
    val showDescription = showDescriptionCard || revealDescription

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
            // ── Image area ────────────────────────────────────────────────────
            Box(modifier = Modifier.fillMaxWidth()) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (uiState.imageUri != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(uiState.imageUri)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Tap an image in the Images tab",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    }
                }
                if (!showDescription && isDescribing) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(16.dp),
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFF3B4A22),
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 3.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Exploring the drawing...",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF3B4A22),
                            )
                        }
                    }
                }
                if (!showDescriptionCard) {
                    IconButton(
                        onClick = { revealDescription = !revealDescription },
                        enabled = uiState.description.isNotBlank() && !isDescribing,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f), CircleShape),
                    ) {
                        Icon(
                            imageVector = if (showDescription) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (showDescription) "Hide description" else "Show description",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // ── Button ───────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    uiState.isGenerating -> Button(
                        onClick = { viewModel.stopGeneration() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Stop, contentDescription = null)
                        Text(" Stop", modifier = Modifier.padding(start = 4.dp))
                    }
                    else -> Button(
                        onClick = { viewModel.describe() },
                        enabled = uiState.imageUri != null && uiState.isModelReady,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                        Text(
                            text = if (showDescriptionCard) " Describe" else " Explore again",
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }

            // ── Classification results ────────────────────────────────────────
            if (uiState.categories.isNotEmpty()) {
                OutlinedCard(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "What is seen in the drawing...",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            uiState.categories.forEach { category ->
                                AssistChip(
                                    onClick = { viewModel.onCategoryInputChange(category) },
                                    label = { Text(category) },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = uiState.categoryInput,
                            onValueChange = { viewModel.onCategoryInputChange(it) },
                            label = { Text("Category") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { onGenerateFairyTale(uiState.categoryInput) },
                            enabled = uiState.categoryInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.AutoFixHigh, contentDescription = null)
                            Text(" Generate Fairy Tale", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }

            // ── Description area ──────────────────────────────────────────────
            if (showDescription) {
            OutlinedCard(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    if (uiState.description.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            IconButton(onClick = {
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, uiState.description)
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, "Share description"))
                            }) {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Outlined.Share,
                                    contentDescription = "Share description",
                                )
                            }
                        }
                    }
                    when {
                        uiState.loadingMessage != null -> {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(Modifier.height(8.dp))
                                    Text(uiState.loadingMessage!!, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        uiState.isGenerating -> {
                            Column {
                                if (uiState.description.isNotBlank()) {
                                    Text(
                                        text = uiState.description,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Spacer(Modifier.height(12.dp))
                                }
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                        uiState.errorMessage != null -> Text(
                            text = uiState.errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        uiState.description.isNotBlank() -> Text(
                            text = uiState.description,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        else -> Text(
                            text = "Tap an image to generate a description…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            }
        }
    }
