package dev.alsatianconsulting.cryptocontainer.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.alsatianconsulting.cryptocontainer.util.contentDisplayName

@Composable
fun KeyfilePickerField(
    label: String,
    selectedUris: List<String>,
    onPick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    helperText: String? = null
) {
    val context = LocalContext.current
    val selectedNames = selectedUris.mapIndexed { index, rawUri ->
        contentDisplayName(context, Uri.parse(rawUri), "keyfile-${index + 1}")
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            when (selectedUris.size) {
                0 -> "No keyfiles selected"
                1 -> "1 keyfile selected"
                else -> "${selectedUris.size} keyfiles selected"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
        if (selectedNames.isNotEmpty()) {
            Text(
                selectedNames.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onPick) {
                Text(if (selectedUris.isEmpty()) "Choose Keyfiles" else "Change Keyfiles")
            }
            if (selectedUris.isNotEmpty()) {
                OutlinedButton(onClick = onClear) { Text("Clear Keyfiles") }
            }
        }
        if (!helperText.isNullOrBlank()) {
            Text(
                helperText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}
