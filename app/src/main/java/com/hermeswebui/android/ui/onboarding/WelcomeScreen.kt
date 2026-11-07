package com.hermeswebui.android.ui.onboarding

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.hermeswebui.android.R
import com.hermeswebui.android.ui.settings.copySetupPrompt
import kotlinx.coroutines.launch

internal const val WELCOME_PAGE_COUNT = 5

/** Test tags for the welcome guide controls. */
internal object WelcomeTags {
    const val PAGER = "welcome_pager"
    const val SKIP = "welcome_skip"
    const val BACK = "welcome_back"
    const val NEXT = "welcome_next"
    const val GET_STARTED = "welcome_get_started"
}

/**
 * First-run welcome guide. A swipeable pager that explains what Hermes-Android is, the two
 * interfaces it can host, how to have the user's own agent set the host side up, and why they
 * need a private network such as Tailscale to reach it away from home.
 *
 * It is purely informational: it never starts a network request or sends anything to the server,
 * and external pages open in the browser. [onFinish] runs for Get started, Skip and Back from the
 * first page.
 */
@Composable
internal fun WelcomeScreen(onFinish: () -> Unit, modifier: Modifier = Modifier) {
    val pagerState = rememberPagerState(pageCount = { WELCOME_PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == WELCOME_PAGE_COUNT - 1

    BackHandler {
        if (pagerState.currentPage > 0) {
            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
        } else {
            onFinish()
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                // Keeps the layout stable on the last page, where Skip has nothing left to skip.
                if (!isLastPage) {
                    TextButton(onClick = onFinish, modifier = Modifier.testTag(WelcomeTags.SKIP)) {
                        Text(stringResource(R.string.welcome_skip))
                    }
                } else {
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag(WelcomeTags.PAGER)
            ) { page ->
                WelcomePage(page)
            }

            PageIndicator(
                current = pagerState.currentPage,
                count = WELCOME_PAGE_COUNT,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (pagerState.currentPage > 0) {
                    TextButton(
                        onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                        modifier = Modifier.testTag(WelcomeTags.BACK)
                    ) { Text(stringResource(R.string.welcome_back)) }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }
                if (isLastPage) {
                    Button(onClick = onFinish, modifier = Modifier.testTag(WelcomeTags.GET_STARTED)) {
                        Text(stringResource(R.string.welcome_get_started), fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Button(
                        onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                        modifier = Modifier.testTag(WelcomeTags.NEXT)
                    ) { Text(stringResource(R.string.welcome_next), fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

@Composable
private fun WelcomePage(page: Int) {
    val context = LocalContext.current
    when (page) {
        0 -> PageFrame(
            icon = Icons.Outlined.SettingsRemote,
            title = R.string.welcome_intro_title,
            body = R.string.welcome_intro_body
        ) {
            ControlDiagram()
        }
        1 -> PageFrame(
            icon = Icons.Outlined.PhoneAndroid,
            title = R.string.welcome_interfaces_title,
            body = R.string.welcome_interfaces_body
        ) {
            InterfaceCard(
                title = R.string.welcome_webui_title,
                body = R.string.welcome_webui_body,
                linkLabel = R.string.welcome_webui_link,
                url = WelcomeLinks.HERMES_WEBUI,
                context = context
            )
            InterfaceCard(
                title = R.string.welcome_webapp_title,
                body = R.string.welcome_webapp_body,
                linkLabel = R.string.welcome_webapp_link,
                url = WelcomeLinks.HERMES_WEBAPP_DOCS,
                context = context
            )
        }
        2 -> PageFrame(
            icon = Icons.Outlined.Terminal,
            title = R.string.welcome_setup_title,
            body = R.string.welcome_setup_body
        ) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { copySetupPrompt(context, R.raw.setup_webui_prompt, R.string.setup_copy_webui) }
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.setup_copy_webui))
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { copySetupPrompt(context, R.raw.setup_webapp_prompt, R.string.setup_copy_webapp) }
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.setup_copy_webapp))
            }
            Text(
                stringResource(R.string.welcome_setup_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        3 -> PageFrame(
            icon = Icons.Outlined.VpnLock,
            title = R.string.welcome_remote_title,
            body = R.string.welcome_remote_body
        ) {
            WelcomeLinks.remoteAccessOptions.forEach { option ->
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { openExternalLink(context, option.url) }
                ) {
                    Text(option.name, modifier = Modifier.weight(1f))
                    Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = stringResource(R.string.welcome_link_open, option.name),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                stringResource(R.string.welcome_remote_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        else -> PageFrame(
            icon = Icons.Outlined.RocketLaunch,
            title = R.string.welcome_ready_title,
            body = R.string.welcome_ready_body
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        stringResource(R.string.welcome_needs_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    ChecklistItem(R.string.welcome_needs_address)
                    ChecklistItem(R.string.welcome_needs_signin)
                    ChecklistItem(R.string.welcome_needs_private)
                }
            }
        }
    }
}

@Composable
private fun ChecklistItem(text: Int) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Hermes Agent on the computer, controlled from this app on the phone. Purely decorative. */
@Composable
private fun ControlDiagram() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        DiagramNode(Icons.Outlined.Computer, R.string.welcome_diagram_agent)
        Icon(
            imageVector = Icons.Outlined.SyncAlt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp)
        )
        DiagramNode(Icons.Outlined.PhoneAndroid, R.string.welcome_diagram_app)
    }
}

@Composable
private fun DiagramNode(icon: ImageVector, label: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(34.dp)
            )
        }
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
    }
}

/** Shared layout: a tinted hero icon, title, body copy, then page-specific content. Scrolls on small screens. */
@Composable
private fun PageFrame(
    icon: ImageVector,
    title: Int,
    body: Int,
    content: @Composable () -> Unit
) {
    // The page scrolls when it is taller than the screen, and is vertically centered when it is shorter,
    // so short pages do not sit in the top half with a blank area below.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val minPageHeight = maxHeight
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .heightIn(min = minPageHeight)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(48.dp)
            )
        }
        Text(
            stringResource(title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        content()
        Spacer(modifier = Modifier.height(8.dp))
    }
    }
}

@Composable
private fun InterfaceCard(title: Int, body: Int, linkLabel: Int, url: String, context: Context) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { openExternalLink(context, url) }) {
                Text(stringResource(linkLabel))
                Spacer(modifier = Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun PageIndicator(current: Int, count: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.welcome_page_indicator, current + 1, count)
    Row(
        modifier = modifier.semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { index ->
            val color = animateColorAsState(
                if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                label = "welcomeDot"
            ).value
            Box(
                modifier = Modifier
                    .size(if (index == current) 10.dp else 8.dp)
                    .background(color, CircleShape)
            )
        }
    }
}

/** Opens one of the fixed [WelcomeLinks] in the user's browser; never loads it inside the app. */
private fun openExternalLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.welcome_open_link_failed, Toast.LENGTH_LONG).show()
    }
}
