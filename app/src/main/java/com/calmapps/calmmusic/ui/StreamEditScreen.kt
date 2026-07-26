package com.calmapps.calmmusic.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD

@Composable
fun StreamEditScreen(
    initialName: String,
    initialUrl: String,
    isEditing: Boolean,
    onConfirm: (name: String, url: String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = name,
            onValueChange = { name = it },
            label = { Text(text = "Station name") },
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = url,
            onValueChange = { url = it },
            label = { Text(text = "Stream URL") },
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButtonMMD(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = "Cancel")
            }

            Spacer(modifier = Modifier.width(8.dp))

            ButtonMMD(
                onClick = { onConfirm(name.trim(), url.trim()) },
                enabled = name.isNotBlank() && url.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text(text = if (isEditing) "Save" else "Add")
            }
        }
    }
}
