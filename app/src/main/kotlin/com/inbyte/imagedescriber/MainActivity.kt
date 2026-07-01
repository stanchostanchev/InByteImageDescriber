package com.inbyte.imagedescriber

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.inbyte.imagedescriber.ui.chat.ChatScreen
import com.inbyte.imagedescriber.ui.chat.ChatViewModel
import com.inbyte.imagedescriber.ui.description.DescribeBottomSheet
import com.inbyte.imagedescriber.ui.description.DescriptionScreen
import com.inbyte.imagedescriber.ui.description.DescriptionViewModel
import com.inbyte.imagedescriber.ui.images.ImagesScreen
import com.inbyte.imagedescriber.ui.images.ImagesViewModel
import com.inbyte.imagedescriber.ui.theme.InByteTheme
import dagger.hilt.android.AndroidEntryPoint

private const val ROUTE_IMAGES      = "images"
private const val ROUTE_DESCRIPTION = "description"
private const val ROUTE_STORY       = "story"

@OptIn(ExperimentalMaterial3Api::class)
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            InByteTheme {
                var showSplash by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    delay(5000)
                    showSplash = false
                }

                if (showSplash) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF6D764F)),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.splash_image),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .align(Alignment.Center),
                        )
                    }
                } else {
                MainApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainApp() {
                val navController = rememberNavController()
                val currentEntry by navController.currentBackStackEntryAsState()
                val currentRoute = currentEntry?.destination?.route

                // Shared ViewModels scoped to this activity
                val imagesViewModel: ImagesViewModel = hiltViewModel()
                val chatViewModel: ChatViewModel = hiltViewModel()
                val descriptionViewModel: DescriptionViewModel = hiltViewModel()

                val selectedUri by imagesViewModel.selectedImageUri.collectAsState()
                var showDescribeSheet by remember { mutableStateOf(false) }
                val describeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

                Scaffold(
                    floatingActionButton = {
                        if (currentRoute == ROUTE_IMAGES && selectedUri != null && !showDescribeSheet) {
                            FloatingActionButton(onClick = { showDescribeSheet = true }) {
                                Icon(Icons.Outlined.AutoAwesome, contentDescription = "Describe last selected image")
                            }
                        }
                    },
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = currentRoute == ROUTE_IMAGES,
                                onClick = {
                                    navController.navigate(ROUTE_IMAGES) {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                                label = { Text("Drawings") },
                            )
                            NavigationBarItem(
                                selected = currentRoute == ROUTE_STORY,
                                onClick = {
                                    navController.navigate(ROUTE_STORY) {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(Icons.Outlined.AutoStories, contentDescription = null) },
                                label = { Text("Fairy Tale") },
                            )
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_IMAGES,
                        modifier = Modifier.padding(innerPadding),
                    ) {
                        composable(ROUTE_IMAGES) {
                            ImagesScreen(
                                viewModel = imagesViewModel,
                                onImageClick = { uri ->
                                    imagesViewModel.selectImage(uri)
                                    showDescribeSheet = true
                                },
                            )
                        }
                        composable(ROUTE_DESCRIPTION) {
                            DescriptionScreen(
                                selectedImageUri = selectedUri,
                                viewModel = descriptionViewModel,
                                onGenerateFairyTale = { category ->
                                    chatViewModel.sendStory(category, selectedUri)
                                    navController.navigate(ROUTE_STORY) {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                            )
                        }
                        composable(ROUTE_STORY) { ChatScreen(viewModel = chatViewModel) }
                    }
                }

                if (showDescribeSheet) {
                    DescribeBottomSheet(
                        selectedImageUri = selectedUri,
                        onDismiss = { showDescribeSheet = false },
                        onGenerateFairyTale = { category ->
                            showDescribeSheet = false
                            chatViewModel.sendStory(category, selectedUri)
                            navController.navigate(ROUTE_STORY) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        viewModel = descriptionViewModel,
                        sheetState = describeSheetState,
                    )
                }
}
