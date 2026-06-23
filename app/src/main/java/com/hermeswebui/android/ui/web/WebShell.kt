package com.hermeswebui.android.ui.web

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hermeswebui.android.R

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun WebShell(
    webView: WebView,
    isLoading: Boolean,
    hasLoadedContent: Boolean,
    isOffline: Boolean,
    isReconnecting: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onOpenExternal: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isLoading,
        onRefresh = onRefresh
    )

    Box(modifier = Modifier.fillMaxSize().pullRefresh(pullRefreshState)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { webView }
        )

        PullRefreshIndicator(
            refreshing = isLoading,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        if (isLoading && !hasLoadedContent) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        if (errorMessage != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isOffline) "You are offline" else "Unable to open Hermes",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    modifier = Modifier.padding(top = 8.dp, bottom = if (isReconnecting) 4.dp else 16.dp),
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (isReconnecting) {
                    Text(
                        modifier = Modifier.padding(bottom = 16.dp),
                        text = "Reconnecting\u2026",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Button(onClick = onRetry) {
                    Text("Retry")
                }
                Button(modifier = Modifier.padding(top = 8.dp), onClick = onOpenExternal) {
                    Text("Open in browser")
                }
                Button(modifier = Modifier.padding(top = 8.dp), onClick = onOpenSettings) {
                    Text(stringResource(R.string.action_edit_server_url))
                }
            }
        }
    }
}
