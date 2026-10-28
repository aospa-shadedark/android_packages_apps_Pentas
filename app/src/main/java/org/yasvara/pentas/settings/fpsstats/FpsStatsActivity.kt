/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.yasvara.pentas.settings.fpsstats

import org.yasvara.pentas.ui.kit.Sym

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemProperties
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.AndroidEntryPoint
import org.yasvara.pentas.data.fpsstats.FpsStatsRepository
import org.yasvara.pentas.data.fpsstats.SessionListItem
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import org.yasvara.pentas.ui.theme.PentasTheme
import org.yasvara.pentas.app.pressable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

@AndroidEntryPoint(ComponentActivity::class)
class FpsStatsActivity : Hilt_FpsStatsActivity() {

    @Inject lateinit var repository: FpsStatsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            PentasTheme {
                FpsStatsListScreen(
                    repository = repository,
                    onBack = { finish() },
                    onSessionClick = { sessionId ->
                        startActivity(
                            Intent(this, FpsStatsDetailActivity::class.java)
                                .putExtra("session_id", sessionId)
                        )
                    },
                    packageManager = packageManager,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FpsStatsListScreen(
    repository: FpsStatsRepository,
    onBack: () -> Unit,
    onSessionClick: (String) -> Unit,
    packageManager: PackageManager,
) {
    var sessions by remember { mutableStateOf(repository.listSessions()) }
    var pendingDelete by remember { mutableStateOf<SessionListItem?>(null) }
    val view = LocalView.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { sessions = repository.listSessions() }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete session?") },
            text = { Text("Delete the recording for ${session.appName}? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    repository.deleteSession(session.id)
                    sessions = repository.listSessions()
                    pendingDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Sessions",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Sym.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = ChartWhite,
                    navigationIconContentColor = ChartWhite,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Device Info Header
            item {
                DeviceInfoCard(
                    platform = SystemProperties.get("ro.board.platform", "unknown").uppercase(),
                    model = Build.MODEL,
                    osVersion = "Android ${Build.VERSION.RELEASE}",
                    profile = "---",
                )
            }

            // Sessions
            if (sessions.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No recordings yet.\nStart recording from the game panel.",
                            color = ChartTextDim,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        packageManager = packageManager,
                        onClick = { onSessionClick(session.id) },
                        onDelete = { pendingDelete = session },
                    )
                }
            }

            // Bottom spacer
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionRow(
    session: SessionListItem,
    packageManager: PackageManager,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val view = LocalView.current
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val dayFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val today = remember { dayFormat.format(Date()) }
    val sessionDay = dayFormat.format(Date(session.startTimeMs))
    val isToday = today == sessionDay

    // Try loading app icon
    val appIcon = remember(session.packageName) {
        try {
            packageManager.getApplicationIcon(session.packageName).toBitmap(48, 48).asImageBitmap()
        } catch (_: Exception) { null }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressable(onClick)
            .background(ChartCardBg, RoundedCornerShape(12.dp))
            .border(0.5.dp, ChartCardBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // App icon
        if (appIcon != null) {
            Image(
                bitmap = appIcon,
                contentDescription = session.appName,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(ChartCardBorder, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = session.appName.take(1),
                    color = ChartWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Info
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isToday) {
                    Text(
                        text = "today",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = session.appName,
                    color = ChartWhite,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = dateFormat.format(Date(session.startTimeMs)),
                    color = ChartTextDim,
                    fontSize = 11.sp,
                )
                Text(
                    text = "%.1f".format(session.fpsAvg),
                    color = ChartCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "%.2fW".format(session.powerAvgW),
                    color = ChartTextDim,
                    fontSize = 11.sp,
                )
            }
        }

        // Duration
        Text(
            text = "${session.durationSec}s",
            color = ChartTextDim,
            fontSize = 11.sp,
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Delete button
        IconButton(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
                onDelete()
            },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                Sym.Delete,
                contentDescription = "Delete",
                tint = ChartTextDim,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
