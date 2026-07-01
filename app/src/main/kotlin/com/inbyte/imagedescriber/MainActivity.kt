package com.inbyte.imagedescriber

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.inbyte.imagedescriber.ui.chat.ChatScreen
import com.inbyte.imagedescriber.ui.chat.ChatViewModel
import com.inbyte.imagedescriber.ui.description.DescriptionScreen
import com.inbyte.imagedescriber.ui.images.ImagesScreen
import com.inbyte.imagedescriber.ui.images.ImagesViewModel
import com.inbyte.imagedescriber.ui.theme.InByteTheme
import dagger.hilt.android.AndroidEntryPoint

private const val ROUTE_IMAGES      = "images"
private const val ROUTE_DESCRIPTION = "description"
private const val ROUTE_STORY       = "story"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            InByteTheme {
                val navController = rememberNavController()
                val currentEntry by navController.currentBackStackEntryAsState()
                val currentRoute = currentEntry?.destination?.route

                // Shared ViewModels scoped to this activity
                val imagesViewModel: ImagesViewModel = hiltViewModel()
                val chatViewModel: ChatViewModel = hiltViewModel()

                Scaffold(
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
                                label = { Text("Images") },
                            )
                            NavigationBarItem(
                                selected = currentRoute == ROUTE_DESCRIPTION,
                                onClick = {
                                    navController.navigate(ROUTE_DESCRIPTION) {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                                label = { Text("Description") },
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
                                label = { Text("Story") },
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
                                    navController.navigate(ROUTE_DESCRIPTION) {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                            )
                        }
                        composable(ROUTE_DESCRIPTION) {
                            val selectedUri by imagesViewModel.selectedImageUri.collectAsState()
                            DescriptionScreen(
                                selectedImageUri = selectedUri,
                                onGenerateFairyTale = { category ->
                                    chatViewModel.sendStory(category)
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
            }
        }
    }
}
