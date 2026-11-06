package com.hermeswebui.android.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hermeswebui.android.R

@Composable
internal fun ServerSetupHelp(initiallyExpanded: Boolean) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.setup_help_hide else R.string.setup_help_title))
        }
        if (expanded) {
            Text(stringResource(R.string.setup_help_instructions), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { copySetupPrompt(context, R.raw.setup_webui_prompt, R.string.setup_copy_webui) }
            ) { Text(stringResource(R.string.setup_copy_webui)) }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { copySetupPrompt(context, R.raw.setup_webapp_prompt, R.string.setup_copy_webapp) }
            ) { Text(stringResource(R.string.setup_copy_webapp)) }
            Text(stringResource(R.string.setup_help_next), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun copySetupPrompt(context: Context, resource: Int, label: Int) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    if (clipboard == null) {
        Toast.makeText(context, R.string.setup_copy_failed, Toast.LENGTH_LONG).show()
        return
    }
    val prompt = context.resources.openRawResource(resource).bufferedReader().use { it.readText().trim() }
    try {
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(label), prompt))
        Toast.makeText(context, R.string.setup_copy_success, Toast.LENGTH_LONG).show()
    } catch (exception: SecurityException) {
        Log.e("HermesSetup", "Setup prompt clipboard access denied", exception)
        Toast.makeText(context, R.string.setup_copy_failed, Toast.LENGTH_LONG).show()
    }
}
