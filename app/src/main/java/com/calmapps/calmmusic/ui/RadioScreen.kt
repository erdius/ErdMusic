package com.calmapps.calmmusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.FloatingActionButtonMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.menus.DropdownMenuItemMMD
import com.mudita.mmd.components.menus.DropdownMenuMMD
import com.mudita.mmd.components.text.TextMMD

data class RadioStationUiModel(
    val id: String,
    val name: String,
    val url: String,
)

@Composable
fun RadioScreen(
    stations: List<RadioStationUiModel>,
    currentStationId: String?,
    isPlaying: Boolean,
    onStationClick: (RadioStationUiModel) -> Unit,
    onAddStationClick: () -> Unit,
    onEditStationClick: (RadioStationUiModel) -> Unit,
    onDeleteStationClick: (RadioStationUiModel) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (stations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TextMMD(
                        text = "No internet radio stations yet",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextMMD(
                        text = "Add an Icecast or Shoutcast stream URL to start listening.",
                        fontSize = 16.sp,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    ButtonMMD(onClick = onAddStationClick) {
                        TextMMD(text = "Add station")
                    }
                }
            }
        } else {
            val lastId = stations.lastOrNull()?.id
            LazyColumnMMD(contentPadding = PaddingValues(16.dp)) {
                items(items = stations, key = { it.id }) { station ->
                    StationItem(
                        station = station,
                        isCurrentlyPlaying = station.id == currentStationId && isPlaying,
                        onClick = { onStationClick(station) },
                        onEditClick = { onEditStationClick(station) },
                        onDeleteClick = { onDeleteStationClick(station) },
                        showDivider = station.id != lastId,
                    )
                }
            }
        }

        if (stations.isNotEmpty()) {
            FloatingActionButtonMMD(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                onClick = onAddStationClick,
            ) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = "Add station")
            }
        }
    }
}

@Composable
private fun StationItem(
    station: RadioStationUiModel,
    isCurrentlyPlaying: Boolean,
    onClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    showDivider: Boolean,
) {
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                TextMMD(
                    text = station.name,
                    fontSize = 20.sp,
                    fontWeight = if (isCurrentlyPlaying) FontWeight.Black else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                TextMMD(
                    text = if (isCurrentlyPlaying) "Now playing" else station.url,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "Station options",
                    )
                }

                DropdownMenuMMD(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItemMMD(
                        text = { TextMMD(text = "Edit") },
                        onClick = {
                            showMenu = false
                            onEditClick()
                        },
                    )

                    DashedDivider(thickness = 1.dp)

                    DropdownMenuItemMMD(
                        text = { TextMMD(text = "Delete") },
                        onClick = {
                            showMenu = false
                            onDeleteClick()
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (showDivider) {
            DashedDivider(thickness = 1.dp)
        }
    }
}
