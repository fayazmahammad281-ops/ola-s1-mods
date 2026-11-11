package com.asjohn720.s1mods.ui.components

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asjohn720.s1mods.discovery.DiscoveredScooter
import com.asjohn720.s1mods.discovery.NetworkScanner
import com.asjohn720.s1mods.model.ScooterManager
import com.asjohn720.s1mods.model.VehicleSummary
import com.asjohn720.s1mods.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Reusable Vehicle Connection Card & Manual Mode Dialog.
 * Uses the high-performance IPv6 multicast auto-discovery engine
 * and a simplified single-input Manual Mode (no Scope ID required).
 */
@Composable
fun VehicleConnectionCard(
    manager: ScooterManager,
    isRunning: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val isConnected by manager.isConnected.collectAsState()
    val scooterIp by manager.scooterIp.collectAsState()
    val scooterScope by manager.scooterScope.collectAsState()

    var isScanning by remember { mutableStateOf(false) }
    var isTestingPort by remember { mutableStateOf(false) }
    var discoveredScooter by remember { mutableStateOf<DiscoveredScooter?>(null) }
    var scanMessage by remember { mutableStateOf<String?>(null) }
    val summary by manager.vehicleSummary.collectAsState()
    var isLoadingSummary by remember { mutableStateOf(false) }

    var showManualModeDialog by remember { mutableStateOf(false) }
    var tempInput by remember { mutableStateOf("") }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, CardBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row: Vehicle Icon + Connection Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isConnected) StatusOk else StatusDanger)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isConnected) "Connected" else "Not connected",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }


            }

            Spacer(modifier = Modifier.height(10.dp))

            // IP Address Display Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceVariantDark)
                    .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = if (scooterIp.isBlank()) "No address"
                    else if (scooterScope != null) "$scooterIp%$scooterScope" else scooterIp,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = if (scooterIp.isBlank()) TextMuted else TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Found scooter: show model + MoveOS + a supported badge (instead of the raw IP).
            if (discoveredScooter != null) {
                val found = discoveredScooter!!
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, CardBorder, RoundedCornerShape(10.dp)),
                    colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusOk, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scooter found", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        when {
                            isLoadingSummary -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = TextSecondary, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Reading model & MoveOS…", fontSize = 12.sp, color = TextSecondary)
                            }
                            summary != null -> {
                                val s = summary!!
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Model", fontSize = 12.sp, color = TextMuted)
                                    Text(s.model, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("MoveOS", fontSize = 12.sp, color = TextMuted)
                                    Text(s.moveOs, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                val badgeColor = if (s.supported) StatusOk else StatusWarning
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(badgeColor.copy(alpha = 0.15f))
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        if (s.supported) Icons.Default.CheckCircle else Icons.Default.Warning,
                                        contentDescription = null, tint = badgeColor, modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        if (s.supported) "Mods supported" else "Mods not supported",
                                        fontSize = 12.sp, fontWeight = FontWeight.Medium, color = badgeColor
                                    )
                                }
                            }
                            else -> Text(found.ip, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextSecondary)
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    try {
                                        // Only mark connected after the gRPC handshake succeeds.
                                        manager.connect(found.ip, found.scope)
                                        if (manager.vehicleSummary.value == null && !isLoadingSummary) {
                                            isLoadingSummary = true
                                            try { manager.fetchVehicleSummary() } catch (_: Exception) {}
                                            isLoadingSummary = false
                                        }
                                        Toast.makeText(context, "gRPC connected", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        manager.isConnected.value = false
                                        Toast.makeText(
                                            context,
                                            "Scooter is reachable, but gRPC failed: ${e.localizedMessage ?: e.javaClass.simpleName}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(42.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentMint, contentColor = BgDark)
                        ) {
                            Text("Connect", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            if (scanMessage != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = scanMessage!!,
                    fontSize = 11.sp,
                    color = if (scanMessage!!.contains("not found", ignoreCase = true)) StatusDanger else TextSecondary,
                    lineHeight = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Primary action: Auto Scan — full width, white so it's the obvious tap.
            Button(
                onClick = {
                    coroutineScope.launch {
                        isScanning = true
                        scanMessage = "Scanning…"
                        discoveredScooter = null
                        manager.vehicleSummary.value = null
                        val found = manager.autoDiscover()
                        isScanning = false
                        if (found != null) {
                            discoveredScooter = found
                            scanMessage = null
                            // Read model + MoveOS WITHOUT connecting the gate, so the
                            // Connect button stays visible for the user to tap.
                            isLoadingSummary = true
                            try {
                                manager.connect(found.ip, found.scope, markConnected = false)
                                manager.fetchVehicleSummary()
                            } catch (_: Exception) {}
                            isLoadingSummary = false
                        } else {
                            scanMessage = "Not found. Check hotspot or use Manual."
                        }
                    }
                },
                enabled = !isScanning && !isRunning,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentMint,
                    contentColor = BgDark,
                    disabledContainerColor = SurfaceHighlight,
                    disabledContentColor = TextMuted
                )
            ) {
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BgDark, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scanning…", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Auto Scan", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Secondary actions: Manual + Test, below the primary button.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        tempInput = scooterIp
                        showManualModeDialog = true
                    },
                    enabled = !isRunning,
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, CardBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Manual", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }

                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            isTestingPort = true
                            val open = manager.testConnection()
                            isTestingPort = false
                            Toast.makeText(
                                context,
                                if (open) "Reachable" else "Unreachable. Is the scooter screen on?",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    enabled = !isTestingPort && !isRunning,
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, CardBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                ) {
                    if (isTestingPort) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = TextPrimary, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Test", fontSize = 13.sp)
                    } else {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Test", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }

    // Manual Mode Dialog (Clean Single-Input, NO Scope ID required)
    if (showManualModeDialog) {
        val cleanInput = tempInput.trim()
        val derivedIpv6 = remember(cleanInput) {
            NetworkScanner.macToIpv6(cleanInput)
        }
        val isIpv4 = remember(cleanInput) {
            cleanInput.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(:\d+)?$""")) || (cleanInput.contains(".") && !cleanInput.contains(":"))
        }
        val isIpv6 = remember(cleanInput, derivedIpv6) {
            cleanInput.contains(":") && !cleanInput.contains("-") && derivedIpv6 == null
        }

        AlertDialog(
            onDismissRequest = { showManualModeDialog = false },
            title = {
                Text("Manual connect", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "IP: on the scooter, open Settings → Wi-Fi and tap the connected network to see its IP address.\n\nMAC: on your phone, open Hotspot → Connected devices to see the scooter's MAC.",
                        fontSize = 12.sp,
                        color = TextMuted,
                        lineHeight = 18.sp
                    )

                    // Single Unified Input Field (No Scope ID needed)
                    OutlinedTextField(
                        value = tempInput,
                        onValueChange = { tempInput = it },
                        label = { Text("IP or MAC address") },
                        placeholder = { Text("192.168.43.50") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentMint,
                            unfocusedBorderColor = CardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedContainerColor = SurfaceVariantDark,
                            unfocusedContainerColor = SurfaceVariantDark
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )

                    // Real-time format recognition preview
                    if (derivedIpv6 != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(AccentMintSubtle)
                                                                .padding(8.dp)
                        ) {
                            Column {
                                Text("MAC → IPv6", fontSize = 11.sp, color = TextSecondary)
                                Text(derivedIpv6, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextPrimary)
                            }
                        }
                    } else if (isIpv4) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(AccentSkySubtle)
                                .padding(8.dp)
                        ) {
                            Text("IPv4", fontSize = 11.sp, color = TextSecondary)
                        }
                    } else if (isIpv6) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(AccentSkySubtle)
                                .padding(8.dp)
                        ) {
                            Text("IPv6", fontSize = 11.sp, color = TextSecondary)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = tempInput.trim()
                        val scopeFromPercent = if (trimmed.contains("%")) trimmed.split("%").getOrNull(1)?.toIntOrNull() else null
                        val baseIp = if (trimmed.contains("%")) trimmed.split("%")[0] else trimmed
                        val finalIp = derivedIpv6 ?: baseIp

                        manager.scooterIp.value = finalIp
                        manager.scooterScope.value = scopeFromPercent
                        showManualModeDialog = false

                        // Quick connection test
                        coroutineScope.launch {
                            isTestingPort = true
                            val open = manager.testConnection(finalIp, scopeFromPercent)
                            isTestingPort = false
                            if (open) {
                                try {
                                    // An open TCP port is reachability, not a successful gRPC connection.
                                    manager.connect(finalIp, scopeFromPercent)
                                    Toast.makeText(context, "gRPC connected", Toast.LENGTH_SHORT).show()
                                    discoveredScooter = DiscoveredScooter(ip = finalIp, scope = scopeFromPercent)
                                    manager.vehicleSummary.value = null
                                    isLoadingSummary = true
                                    try { manager.fetchVehicleSummary() } catch (_: Exception) {}
                                    isLoadingSummary = false
                                } catch (e: Exception) {
                                    manager.isConnected.value = false
                                    Toast.makeText(
                                        context,
                                        "Port is reachable, but gRPC failed: ${e.localizedMessage ?: e.javaClass.simpleName}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            } else {
                                manager.isConnected.value = false
                                Toast.makeText(context, "Saved, but not reachable yet", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentMint, contentColor = BgDark),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Connect", fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualModeDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark,
            shape = RoundedCornerShape(12.dp)
        )
    }
}
