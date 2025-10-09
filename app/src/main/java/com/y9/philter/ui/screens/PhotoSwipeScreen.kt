package com.y9.philter.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.y9.philter.Photo
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoSwipeScreen(
    photos: List<Photo>,
    currentIndex: Int,
    trashCount: Int,
    onSwipeLeft: (Photo) -> Unit,
    onSwipeRight: (Photo) -> Unit,
    onOpenTrash: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        // Trash button + badge
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            FloatingActionButton(
                onClick = onOpenTrash,
                containerColor = Color(0xFF2C2C2C),
                contentColor = Color.White
            ) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = "Open Trash")
            }
            if (trashCount > 0) {
                Badge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                ) {
                    Text(trashCount.toString())
                }
            }
        }

        // If no photos left
        if (photos.isEmpty()) {
            Text(
                text = "No photos to review",
                color = Color.White,
                fontSize = 20.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (currentIndex >= photos.size) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("All done!", color = Color.White, fontSize = 24.sp)
                Spacer(Modifier.height(16.dp))
                if (trashCount > 0) {
                    Button(onClick = onOpenTrash) { Text("Review Trash ($trashCount)") }
                }
            }
        } else {
            // Show current + next card (stack effect)
            for (i in (currentIndex + 1 downTo currentIndex).take(2)) {
                if (i < photos.size) {
                    key(photos[i].id) {
                        SwipeablePhotoCard(
                            photo = photos[i],
                            isTopCard = i == currentIndex,
                            onSwipeLeft = { onSwipeLeft(photos[i]) },
                            onSwipeRight = { onSwipeRight(photos[i]) },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .padding(top = 80.dp)
                        )
                    }
                }
            }

            // Date at bottom
            if (photos.isNotEmpty() && currentIndex < photos.size) {
                val dateFormatter = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
                val dateText = remember(photos[currentIndex].dateAdded) {
                    dateFormatter.format(Date(photos[currentIndex].dateAdded * 1000L))
                }
                Text(
                    text = dateText,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                )
            }
        }
    }
}

@Composable
fun SwipeablePhotoCard(
    photo: Photo,
    isTopCard: Boolean,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    modifier: Modifier = Modifier
) {
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val rotation = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val swipeThreshold = 300f

    // Calculate overlay opacity based on swipe distance
    val deleteOpacity = ((-offsetX.value / swipeThreshold).coerceIn(0f, 1f) * 0.7f)
    val keepOpacity = ((offsetX.value / swipeThreshold).coerceIn(0f, 1f) * 0.7f)

    Box(
        modifier = modifier
            .graphicsLayer {
                translationX = if (isTopCard) offsetX.value else 0f
                translationY = if (isTopCard) offsetY.value else 0f
                rotationZ = if (isTopCard) rotation.value else 0f
                scaleX = if (isTopCard) 1f else 0.95f
                scaleY = if (isTopCard) 1f else 0.95f
            }
            .then(
                if (isTopCard) {
                    Modifier.pointerInput(photo.id) {
                        detectDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    when {
                                        offsetX.value > swipeThreshold -> {
                                            launch {
                                                offsetX.animateTo(1000f, tween(200))
                                                rotation.animateTo(20f, tween(200))
                                            }.join()
                                            onSwipeRight()
                                            offsetX.snapTo(0f)
                                            rotation.snapTo(0f)
                                            offsetY.snapTo(0f)
                                        }
                                        offsetX.value < -swipeThreshold -> {
                                            launch {
                                                offsetX.animateTo(-1000f, tween(200))
                                                rotation.animateTo(-20f, tween(200))
                                            }.join()
                                            onSwipeLeft()
                                            offsetX.snapTo(0f)
                                            rotation.snapTo(0f)
                                            offsetY.snapTo(0f)
                                        }
                                        else -> {
                                            offsetX.animateTo(0f)
                                            offsetY.animateTo(0f)
                                            rotation.animateTo(0f)
                                        }
                                    }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                scope.launch {
                                    offsetX.snapTo(offsetX.value + dragAmount.x)
                                    offsetY.snapTo(offsetY.value + dragAmount.y)
                                    rotation.snapTo(offsetX.value / 20f)
                                }
                            }
                        )
                    }
                } else Modifier
            )
    ) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .shadow(8.dp, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
        ) {
            Box(Modifier.fillMaxSize()) {
                Image(
                    painter = rememberAsyncImagePainter(photo.uri),
                    contentDescription = photo.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                // Delete overlay (swipe left)
                if (isTopCard && deleteOpacity > 0.1f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Red.copy(alpha = deleteOpacity))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .size(100.dp)
                                .align(Alignment.Center)
                        )
                    }
                }

                // Keep overlay (swipe right)
                if (isTopCard && keepOpacity > 0.1f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Green.copy(alpha = keepOpacity))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .size(100.dp)
                                .align(Alignment.Center)
                        )
                    }
                }
            }
        }
    }
}