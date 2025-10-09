package com.y9.philter

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.y9.philter.ai.PhotoQueueManager
import com.y9.philter.ai.PhotoWithScore
import com.y9.philter.data.AppDatabase
import com.y9.philter.ui.screens.PhotoSwipeScreen
import com.y9.philter.ui.screens.TrashScreen
import com.y9.philter.ui.theme.FilterTheme
import kotlinx.coroutines.launch

// ---------------------
// Data Models
// ---------------------

data class Photo(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateAdded: Long
)

enum class Screen {
    ONBOARDING, SWIPE, TRASH
}

// ---------------------
// Main Activity
// ---------------------

class MainActivity : ComponentActivity() {
    private var photosQueue by mutableStateOf<List<PhotoWithScore>>(emptyList())
    private var photosToDelete by mutableStateOf<List<Photo>>(emptyList())
    private var currentScreen by mutableStateOf(Screen.ONBOARDING)
    private var isLoading by mutableStateOf(true)
    private var isProcessingSwipe by mutableStateOf(false)
    private var shouldShowOnboarding by mutableStateOf(true)

    // Track training progress
    private var trainingKeeps by mutableStateOf(0)
    private var trainingDeletes by mutableStateOf(0)

    private lateinit var deleteLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var photoQueueManager: PhotoQueueManager

    // Fetch photos from MediaStore
    private fun fetchPhotos(): List<Photo> {
        val photos = mutableListOf<Photo>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        // Only get images, not videos
        val selection = "${MediaStore.Images.Media.MIME_TYPE} LIKE ?"
        val selectionArgs = arrayOf("image/%")

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val dateAdded = cursor.getLong(dateColumn)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                photos.add(Photo(id, contentUri, name, dateAdded))
            }
        }

        Log.d("FetchPhotos", "Found ${photos.size} photos (images only)")
        return photos
    }

    // Delete photos in batch
    private fun deletePhotosInBatch() {
        if (photosToDelete.isEmpty()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uris = photosToDelete.map { it.uri }
            val photoIds = photosToDelete.map { it.id }
            val deleteRequest = MediaStore.createDeleteRequest(contentResolver, uris)

            try {
                deleteLauncher.launch(
                    IntentSenderRequest.Builder(deleteRequest.intentSender).build()
                )
                // Note: photoIds will be removed from database in the result handler
            } catch (e: IntentSender.SendIntentException) {
                Log.e("Delete", "Failed to launch delete request: ${e.message}")
            }
        } else {
            val photoIds = photosToDelete.map { it.id }
            photosToDelete.forEach { photo ->
                try {
                    contentResolver.delete(photo.uri, null, null)
                    Log.d("Delete", "Deleted: ${photo.displayName}")
                } catch (e: Exception) {
                    Log.e("Delete", "Failed to delete: ${e.message}")
                }
            }
            photosToDelete = emptyList()

            // Remove from database
            lifecycleScope.launch {
                photoQueueManager.removeDeletedPhotos(photoIds)
            }
        }
    }

    // Ask for storage/photo permission
    private fun requestPhotoPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        requestPermissionLauncher.launch(permission)
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.d("Permission", "Photo permission granted")
                lifecycleScope.launch {
                    val allPhotos = fetchPhotos()
                    Log.d("Permission", "Initializing queue with ${allPhotos.size} photos")
                    photoQueueManager.initialize(allPhotos)

                    // Restore trash list from database
                    val deleteDecisions = photoQueueManager.getDeletedPhotoIds()
                    photosToDelete = allPhotos.filter { it.id in deleteDecisions }
                    Log.d("Permission", "Restored ${photosToDelete.size} photos to trash")

                    photosQueue = photoQueueManager.getQueueSnapshot()
                    Log.d("Permission", "Queue snapshot has ${photosQueue.size} photos")

                    // Get initial training counts
                    val stats = photoQueueManager.getTrainingStats()
                    trainingKeeps = stats.first
                    trainingDeletes = stats.second

                    isLoading = false

                    // Check if first time
                    val prefs = getSharedPreferences("philter_prefs", Context.MODE_PRIVATE)
                    shouldShowOnboarding = prefs.getBoolean("first_time", true)
                    if (shouldShowOnboarding) {
                        currentScreen = Screen.ONBOARDING
                    } else {
                        currentScreen = Screen.SWIPE
                    }
                }
            } else {
                Log.d("Permission", "Photo permission denied")
                isLoading = false
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Setup DB + Queue Manager
        val db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "photos.db"
        ).build()
        photoQueueManager = PhotoQueueManager(db.photoDecisionDao(), applicationContext)

        deleteLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                Log.d("Delete", "Successfully deleted ${photosToDelete.size} photos")
                val photoIds = photosToDelete.map { it.id }
                photosToDelete = emptyList()
                currentScreen = Screen.SWIPE

                // Remove from database
                lifecycleScope.launch {
                    photoQueueManager.removeDeletedPhotos(photoIds)
                }
            } else {
                Log.d("Delete", "User cancelled deletion")
            }
        }

        requestPhotoPermission()

        setContent {
            FilterTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
                    when (currentScreen) {
                        Screen.ONBOARDING -> {
                            OnboardingScreen(
                                onGetStarted = {
                                    val prefs = getSharedPreferences("philter_prefs", Context.MODE_PRIVATE)
                                    prefs.edit().putBoolean("first_time", false).apply()
                                    currentScreen = Screen.SWIPE
                                }
                            )
                        }

                        Screen.SWIPE -> {
                            if (isLoading) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(color = Color.White)
                                        Spacer(Modifier.height(16.dp))
                                        Text(
                                            text = "philter is learning from your photos, just a moment...",
                                            color = Color.White,
                                            fontSize = 16.sp,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            } else if (photosQueue.isEmpty() || isProcessingSwipe) {
                                // Show loading during transitions
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(color = Color.White)
                                        Spacer(Modifier.height(16.dp))
                                        Text(
                                            text = "philter is getting photos for you, we'll be right back...",
                                            color = Color.White,
                                            fontSize = 16.sp,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            } else {
                                PhotoSwipeScreen(
                                    photos = photosQueue.map { it.photo },
                                    currentIndex = 0,
                                    trashCount = photosToDelete.size,
                                    onSwipeLeft = { photo ->
                                        // Check if this will complete training
                                        val willCompleteTraining = (trainingDeletes + 1 >= 20 && trainingKeeps >= 20)

                                        // Show loading if this might complete training or empty the queue
                                        if (photosQueue.size <= 3 || willCompleteTraining) {
                                            isProcessingSwipe = true
                                        }

                                        // Prevent duplicates in trash
                                        if (photosToDelete.none { it.id == photo.id }) {
                                            photosToDelete = photosToDelete + photo
                                        }
                                        lifecycleScope.launch {
                                            photoQueueManager.recordDecision(photo, "DELETE")

                                            // Update training counts
                                            val stats = photoQueueManager.getTrainingStats()
                                            trainingKeeps = stats.first
                                            trainingDeletes = stats.second

                                            val nextPhoto = photoQueueManager.nextPhoto()
                                            photosQueue = photoQueueManager.getQueueSnapshot()

                                            // Keep loading screen if queue is empty
                                            if (photosQueue.isEmpty()) {
                                                isProcessingSwipe = true
                                            } else {
                                                isProcessingSwipe = false
                                            }
                                        }
                                    },
                                    onSwipeRight = { photo ->
                                        // Check if this will complete training
                                        val willCompleteTraining = (trainingKeeps + 1 >= 20 && trainingDeletes >= 20)

                                        // Show loading if this might complete training or empty the queue
                                        if (photosQueue.size <= 3 || willCompleteTraining) {
                                            isProcessingSwipe = true
                                        }

                                        lifecycleScope.launch {
                                            photoQueueManager.recordDecision(photo, "KEEP")

                                            // Update training counts
                                            val stats = photoQueueManager.getTrainingStats()
                                            trainingKeeps = stats.first
                                            trainingDeletes = stats.second

                                            val nextPhoto = photoQueueManager.nextPhoto()
                                            photosQueue = photoQueueManager.getQueueSnapshot()

                                            // Keep loading screen if queue is empty
                                            if (photosQueue.isEmpty()) {
                                                isProcessingSwipe = true
                                            } else {
                                                isProcessingSwipe = false
                                            }
                                        }
                                    },
                                    onOpenTrash = { currentScreen = Screen.TRASH }
                                )
                            }
                        }

                        Screen.TRASH -> {
                            TrashScreen(
                                photosToDelete = photosToDelete,
                                onBack = { currentScreen = Screen.SWIPE },
                                onEmptyTrash = { deletePhotosInBatch() },
                                onRestorePhoto = { photo ->
                                    photosToDelete = photosToDelete.filter { it.id != photo.id }
                                    // Update database to mark as KEEP instead of DELETE
                                    lifecycleScope.launch {
                                        photoQueueManager.restorePhoto(photo)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        photoQueueManager.cleanup()
    }
}

@Composable
fun OnboardingScreen(onGetStarted: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = "Welcome to philter",
                color = Color.White,
                fontSize = 32.sp,
                style = MaterialTheme.typography.headlineLarge
            )

            Spacer(Modifier.height(16.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                OnboardingStep(
                    icon = "👈",
                    title = "Swipe Left",
                    description = "Delete photos you don't want. These photos, are placed in the trash and can all be deleted with a button, or restored with a click"
                )

                OnboardingStep(
                    icon = "👉",
                    title = "Swipe Right",
                    description = "Keep photos you love! The app keeps track of what photos you keep, so it never repeats."
                )

                OnboardingStep(
                    icon = "🤖",
                    title = "AI Learning",
                    description = "After 20 swipes left and right, philter learns what photos you're likely to delete and shows you those first. It also continues to learn as you go"
                )

                OnboardingStep(
                    icon = "🔒",
                    title = "Privacy",
                    description = "No log ins, no servers, no internet. The entire application including the AI runs on your device. We never see you're photos and frankly, we don't want to."
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onGetStarted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                )
            ) {
                Text(
                    text = "Start philtering",
                    fontSize = 18.sp,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun OnboardingStep(icon: String, title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = icon,
            fontSize = 40.sp,
            modifier = Modifier.padding(end = 16.dp)
        )

        Column {
            Text(
                text = title,
                color = Color.White,
                fontSize = 18.sp,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = description,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}