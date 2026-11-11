package com.asjohn720.s1mods.model

import android.content.Context
import android.graphics.Bitmap
import com.asjohn720.s1mods.adb.AdbManager
import com.asjohn720.s1mods.discovery.DiscoveredScooter
import com.asjohn720.s1mods.discovery.NetworkScanner
import com.asjohn720.s1mods.grpc.S1GrpcClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

enum class SetupStep(val stepNum: Int, val title: String) {
    VERIFY_CONNECTION(1, "Detect Scooter & Connect"),
    UNLOCK_ADB(2, "In-Memory ADB Unlock"),
    INSTALL_APKS(3, "Install Selected Mod APKs"),
    GRANT_PERMISSIONS(4, "Grant System Permissions"),
    FINALIZE_HOME(5, "Initialize & Return to Home")
}

enum class ModApp(
    val id: String,
    val displayName: String,
    val assetFileName: String,
    val packageName: String,
    val isMandatory: Boolean,
    val description: String
) {
    BALLS(
        id = "balls",
        displayName = "Balls Navigation",
        assetFileName = "balls-1.1.0-lite-release.apk",
        packageName = "com.asjohn720.ball",
        isMandatory = true,
        description = "Floating nav buttons"
    ),
    ANDROID_AUTO(
        id = "android_auto",
        displayName = "Android Auto",
        assetFileName = "com.asjohn720.androidauto.apk",
        packageName = "com.asjohn720.androidauto",
        isMandatory = false,
        description = "Maps, music and calls"
    ),
    MUSIC(
        id = "music",
        displayName = "Music Mod",
        assetFileName = "musicmod-release.apk",
        packageName = "com.asjohn720.musicmod",
        isMandatory = false,
        description = "Ad-free music"
    )
}

data class ScooterInfo(
    val uid: String = "",
    val androidVersion: String = "",
    val buildId: String = "",
    val model: String = "",
    val scooterMode: String = ""
)

data class RamInfo(
    val totalMb: Int,
    val usedMb: Int,
    val freeMb: Int
)

/**
 * Minimal vehicle identity shown on the connect/setup card.
 * The advertised minimum supported firmware is MoveOS 4.0.3.
 * This is a compatibility indicator, not proof that every RPC/mod works on every build.
 */
data class VehicleSummary(
    val model: String,
    val moveOs: String,
    val majorVersion: Int?,
    val supported: Boolean
)

class ScooterManager(private val context: Context) {

    private val adbManager = AdbManager(context)
    private var grpcClient: S1GrpcClient? = null

    val scooterIp = MutableStateFlow("")
    val scooterScope = MutableStateFlow<Int?>(null)
    val isConnected = MutableStateFlow(false)

    val currentStep = MutableStateFlow<SetupStep?>(null)
    val isSetupRunning = MutableStateFlow(false)
    val setupSuccess = MutableStateFlow(false)
    val errorMessage = MutableStateFlow<String?>(null)

    // Last read model + MoveOS, shared by the connect card and the Setup "My Scooter" card.
    val vehicleSummary = MutableStateFlow<VehicleSummary?>(null)

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    fun log(msg: String) {
        _logs.value = _logs.value + msg
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    /**
     * Initializes or updates the active gRPC connection.
     * markConnected=false builds the client for a background read (model/MoveOS)
     * without flipping isConnected, so the connect screen's gate does not jump
     * to the dashboard before the user taps Connect.
     */
    suspend fun connect(
        ip: String = scooterIp.value,
        scope: Int? = scooterScope.value,
        markConnected: Boolean = true
    ) = withContext(Dispatchers.IO) {
        log("[*] Connecting to vehicle at $ip (Scope: ${scope ?: "auto"}) ...")
        grpcClient?.close()
        val client = S1GrpcClient(host = ip, port = 50005, scope = scope)
        client.connect(timeoutSeconds = 6L)
        grpcClient = client
        scooterIp.value = ip
        scooterScope.value = scope
        if (markConnected) isConnected.value = true
        log("[+] Successfully connected to MoveOS Diagnostics daemon (Port 50005)!")
    }

    /**
     * Returns a live gRPC client, building one from the saved address if needed.
     * Manual Mode / testConnection only flip isConnected; they never build a client,
     * so gRPC actions must heal the connection here instead of throwing.
     */
    private suspend fun ensureGrpc(): S1GrpcClient = withContext(Dispatchers.IO) {
        grpcClient?.let { return@withContext it }
        if (scooterIp.value.isBlank()) throw IllegalStateException("Not connected to vehicle")
        connect()
        grpcClient ?: throw IllegalStateException("Not connected to vehicle")
    }

    /**
     * Auto-discovers the scooter on the local Wi-Fi / Hotspot network.
     */
    suspend fun autoDiscover(): DiscoveredScooter? = withContext(Dispatchers.IO) {
        log("[*] Scanning network for vehicle...")
        val discovered = NetworkScanner.scanForScooter(context)
        if (discovered != null) {
            scooterIp.value = discovered.ip
            scooterScope.value = discovered.scope
            log("[+] Discovered Vehicle: ${discovered.ip} (Scope: ${discovered.scope ?: "none"}, Source: ${discovered.source})")
        } else {
            log("[!] Could not auto-detect vehicle on port 50005.")
            log("    Tip: Check 'Connected Devices' in your Hotspot settings and enter the MAC or IP.")
        }
        discovered
    }

    /**
     * Quick test to verify if port 50005 is reachable.
     */
    suspend fun testConnection(ip: String = scooterIp.value, scope: Int? = scooterScope.value): Boolean = withContext(Dispatchers.IO) {
        log("[*] Testing port 50005 on $ip...")
        val open = NetworkScanner.testPort(ip, 50005, scope, 1000)
        // This checks TCP reachability only. It must not set the gRPC connection state.
        if (open) {
            log("[+] Port 50005 is OPEN and responsive!")
        } else {
            log("[!] Port 50005 is unreachable on $ip. Ensure scooter screen is awake!")
        }
        open
    }

    /**
     * Executes the complete 1-Click Automated Setup Wizard.
     * @param selectedApps The set of applications chosen to be installed.
     */
    suspend fun runFullSetup(selectedApps: Set<ModApp> = ModApp.values().toSet()) = withContext(Dispatchers.IO) {
        isSetupRunning.value = true
        setupSuccess.value = false
        errorMessage.value = null
        clearLogs()

        log("==================================================")
        log("       S1MODS AUTOMATED INSTALLATION WIZARD       ")
        log("==================================================")
        log("[*] Selected apps to install: ${selectedApps.joinToString { it.displayName }}")

        try {
            // Step 1: Verify Connection
            currentStep.value = SetupStep.VERIFY_CONNECTION
            log("\n[1/5] Verifying connection to vehicle...")
            if (scooterIp.value.isBlank()) {
                log("[*] No address entered. Attempting auto-discovery...")
                val found = autoDiscover()
                if (found == null || scooterIp.value.isBlank()) {
                    throw IllegalStateException("No vehicle detected. Please tap 'Auto Scan' or use Manual Mode to enter your scooter's IP/MAC address.")
                }
            }
            if (grpcClient == null || !isConnected.value) {
                connect()
            }
            log("[+] gRPC link confirmed alive.")

            // Step 2: Unlock ADB (In-Memory RAM Bind Mount)
            currentStep.value = SetupStep.UNLOCK_ADB
            log("\n[2/5] Authorizing ADB key via in-memory RAM mount...")
            val (_, pubKey) = adbManager.getOrCreateAdbKeyPair()
            val unlocked = grpcClient?.unlockAdb(pubKey) ?: false
            if (!unlocked) {
                log("[*] Warning: bind mount script returned non-zero, trying ADB connect...")
            }
            log("[*] Connecting Kotlin ADB client over TCP Port 5555...")
            adbManager.connect(scooterIp.value, 5555)
            log("[+] ADB connected successfully as 'device'!")

            // Step 3: Extract and Install Chosen APKs
            currentStep.value = SetupStep.INSTALL_APKS
            log("\n[3/5] Installing selected Mod APKs...")

            for (app in selectedApps) {
                val isInstalled = adbManager.isPackageInstalled(app.packageName)
                if (isInstalled) {
                    log("[*] ${app.displayName} already installed on vehicle. Clean uninstalling (${app.packageName})...")
                    val uninstRes = adbManager.uninstallPackage(app.packageName)
                    log("    [+] Clean uninstall: $uninstRes")
                } else {
                    log("[*] ${app.displayName} not found on vehicle. Direct fresh install...")
                }
                installAssetApk(app.assetFileName, app.displayName)
            }

            // Step 4: Grant Permissions
            currentStep.value = SetupStep.GRANT_PERMISSIONS
            log("\n[4/5] Granting required permissions via ADB...")
            if (selectedApps.contains(ModApp.BALLS)) {
                log("[*] Configuring Balls Navigation (Overlay, Settings & Accessibility)...")
                adbManager.grantBallPermissions()
                log("[+] Balls Navigation permissions granted & initialized.")
            }

            if (selectedApps.contains(ModApp.ANDROID_AUTO)) {
                log("[*] Configuring Android Auto permissions (Microphone, Location, Overlay, Storage)...")
                adbManager.grantAndroidAutoPermissions()
                log("[+] Android Auto permissions granted.")
            }

            // Step 5: Finalize & Return to Home
            currentStep.value = SetupStep.FINALIZE_HOME
            log("\n[5/5] Returning vehicle to Dashboard Home...")
            openDashboardHome()

            log("\n==================================================")
            log("     CONGRATULATIONS! ALL MODS INSTALLED!         ")
            log("==================================================")
            log("Installation complete. Your selected apps are ready on screen.")
            log("[*] Note: Use mod apps with the scooter stand UP. Lowering the side stand triggers auto-lock.")
            setupSuccess.value = true

        } catch (e: Exception) {
            val err = e.localizedMessage ?: e.toString()
            log("\n[!] Setup failed: $err")
            errorMessage.value = err
            setupSuccess.value = false
        } finally {
            isSetupRunning.value = false
        }
    }

    /**
     * Extracts an APK bundled in assets to temporary storage and installs it via ADB.
     */
    private suspend fun installAssetApk(assetFileName: String, friendlyName: String) = withContext(Dispatchers.IO) {
        log("[*] Preparing $friendlyName...")
        val tempFile = File(context.cacheDir, assetFileName)
        context.assets.open(assetFileName).use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        val sizeMb = String.format("%.1f", tempFile.length() / (1024.0 * 1024.0))
        log("    Streaming $assetFileName ($sizeMb MB) via ADB...")
        val result = adbManager.installApk(tempFile)
        tempFile.delete()
        log("    [+] $friendlyName result: $result")
    }

    /**
     * Ensures ADB session is unlocked and connected.
     */
    suspend fun ensureAdb(): Boolean = withContext(Dispatchers.IO) {
        if (grpcClient == null || !isConnected.value) {
            connect()
        }
        val (_, pubKey) = adbManager.getOrCreateAdbKeyPair()
        grpcClient?.unlockAdb(pubKey)
        adbManager.connect(scooterIp.value, 5555)
        true
    }

    /**
     * Checks all currently installed packages on the vehicle.
     */
    suspend fun fetchInstalledPackages(): Set<String> = withContext(Dispatchers.IO) {
        val packages = mutableSetOf<String>()
        try {
            if (grpcClient != null && isConnected.value) {
                val (_, out) = grpcClient!!.shell("pm list packages", 10L)
                out.lines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("package:")) {
                        packages.add(trimmed.removePrefix("package:").trim())
                    }
                }
            } else if (isConnected.value) {
                val out = adbManager.shell("pm list packages")
                out.lines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("package:")) {
                        packages.add(trimmed.removePrefix("package:").trim())
                    }
                }
            }
            if (packages.isNotEmpty()) {
                log("[+] Checked packages: ${packages.size} packages installed on vehicle.")
            }
        } catch (e: Exception) {
            log("[!] Could not inspect packages: ${e.message}")
        }
        packages
    }

    /**
     * Installs a single bundled ModApp from assets and automatically applies its permissions.
     */
    suspend fun installSingleModApp(app: ModApp): String = withContext(Dispatchers.IO) {
        log("[*] Installing ${app.displayName} (${app.packageName})...")
        ensureAdb()

        if (adbManager.isPackageInstalled(app.packageName)) {
            log("[*] Clean uninstalling previous version...")
            adbManager.uninstallPackage(app.packageName)
        }

        installAssetApk(app.assetFileName, app.displayName)

        // Automatically apply required permissions
        when (app) {
            ModApp.BALLS -> {
                log("[*] Configuring Balls Navigation overlay & permissions...")
                adbManager.grantBallPermissions()
                log("[+] Balls permissions configured.")
            }
            ModApp.ANDROID_AUTO -> {
                log("[*] Configuring Android Auto permissions...")
                adbManager.grantAndroidAutoPermissions()
                log("[+] Android Auto permissions configured.")
            }
            ModApp.MUSIC -> {
                log("[*] Configuring Music Mod permissions...")
                try {
                    adbManager.shell("pm grant ${app.packageName} android.permission.READ_EXTERNAL_STORAGE")
                } catch (_: Exception) {}
                log("[+] Music Mod configured.")
            }
        }
        log("[+] ${app.displayName} installed and configured successfully!")
        "Installed"
    }

    /**
     * Uninstalls any package cleanly from the vehicle.
     */
    suspend fun uninstallPackage(packageName: String): String = withContext(Dispatchers.IO) {
        log("[*] Uninstalling $packageName...")
        ensureAdb()
        val res = adbManager.uninstallPackage(packageName)
        log("[+] Uninstall result: $res")
        res
    }

    /**
     * Installs an APK from local file storage onto the vehicle.
     */
    suspend fun installApkFile(file: File, friendlyName: String = file.name): String = withContext(Dispatchers.IO) {
        val sizeMb = String.format("%.1f", file.length() / (1024.0 * 1024.0))
        log("[*] Installing $friendlyName ($sizeMb MB)...")
        ensureAdb()
        val res = adbManager.installApk(file)
        log("[+] Installed $friendlyName: $res")
        res
    }

    /**
     * Launches an app on the scooter display via ADB intent.
     */
    suspend fun openApp(packageName: String, friendlyName: String = packageName): Boolean = withContext(Dispatchers.IO) {
        log("[*] Launching $friendlyName ($packageName) on scooter display...")
        try {
            val client = ensureGrpc()
            val launchCmd = when (packageName) {
                "com.asjohn720.ball" -> "am start -n com.asjohn720.ball/.ui.MainActivity"
                else -> "monkey -p $packageName -c android.intent.category.LAUNCHER 1"
            }
            val out = client.shell(launchCmd).second
            val isError = out.contains("No activities found") || out.contains("monkey aborted")
            if (isError) {
                log("[!] Could not launch $friendlyName via launcher: $out")
                false
            } else {
                log("[+] $friendlyName launched successfully on screen.")
                true
            }
        } catch (e: Exception) {
            log("[!] Failed to launch $friendlyName: ${e.message}")
            false
        }
    }

    /**
     * Installs any user-selected APK file from phone storage.
     */
    /**
     * Launches specific Android Settings screen on the scooter and returns execution confirmation.
     */
    suspend fun openSettings(action: String): Boolean = withContext(Dispatchers.IO) {
        log("[*] Launching settings: $action...")
        val client = ensureGrpc()
        val (exitCode, _) = client.shell("am start -a $action", 15L)
        val success = exitCode in 0..1
        if (success) {
            log("[+] Settings screen opened: $action")
        } else {
            log("[!] Could not open settings: $action (exit: $exitCode)")
        }
        success
    }

    /**
     * Returns scooter to Speedometer Home dashboard and returns confirmation.
     */
    suspend fun openDashboardHome(): Boolean = withContext(Dispatchers.IO) {
        log("[*] Bringing Dashboard Home to front...")
        val client = ensureGrpc()
        val (exitCode, _) = client.shell("am start -n com.olaelectric.hmiclient/.MainActivity", 15L)
        client.shell("input keyevent 3", 10L)
        val success = exitCode in 0..1
        if (success) {
            log("[+] Speedometer Home dashboard active.")
        }
        success
    }

    /**
     * Captures a screenshot from the scooter.
     */
    suspend fun captureScreenshot(): Bitmap = withContext(Dispatchers.IO) {
        log("[*] Capturing vehicle display screenshot...")
        val client = ensureGrpc()
        val bmp = client.takeScreenshot()
        log("[+] Screenshot captured successfully (${bmp.width}x${bmp.height} px).")
        bmp
    }

    /**
     * Executes arbitrary root shell command and returns output.
     */
    suspend fun runRootShell(command: String): String = withContext(Dispatchers.IO) {
        log("[*] Root shell command: $command")
        val client = ensureGrpc()
        val (_, out) = client.shell(command, 30L)
        val preview = if (out.length > 200) out.take(200) + "..." else out
        log("[+] Shell output: $preview")
        out
    }

    /**
     * Reads /proc/meminfo from the scooter to calculate live Total, Used, and Free RAM.
     */
    suspend fun fetchRamInfo(): RamInfo? = withContext(Dispatchers.IO) {
        try {
            val output = if (grpcClient != null && isConnected.value) {
                grpcClient!!.shell("cat /proc/meminfo", 8L).second
            } else if (isConnected.value) {
                adbManager.shell("cat /proc/meminfo")
            } else {
                ""
            }
            if (output.isBlank()) return@withContext null

            var totalKb = 0L
            var freeKb = 0L
            var memFreeKb = 0L
            var buffersKb = 0L
            var cachedKb = 0L
            var hasAvailable = false

            output.lines().forEach { line ->
                val parts = line.split(":")
                if (parts.size >= 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim().split("\\s+".toRegex()).firstOrNull()?.toLongOrNull() ?: 0L
                    when (key) {
                        "MemTotal" -> totalKb = value
                        "MemAvailable" -> {
                            freeKb = value
                            hasAvailable = true
                        }
                        "MemFree" -> memFreeKb = value
                        "Buffers" -> buffersKb = value
                        "Cached" -> cachedKb = value
                    }
                }
            }

            if (!hasAvailable) {
                freeKb = memFreeKb + buffersKb + cachedKb
            }

            if (totalKb > 0) {
                val totalMb = (totalKb / 1024).toInt()
                val freeMb = (freeKb / 1024).toInt()
                val usedMb = (totalMb - freeMb).coerceAtLeast(0)
                val info = RamInfo(totalMb, usedMb, freeMb)
                log("[+] Live RAM: Total ${totalMb} MB, Used ${usedMb} MB, Free ${freeMb} MB")
                info
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reads just model + MoveOS version for the connect/setup card.
     * MoveOS version comes from the daemon's GetSwVersion RPC (like Axiom);
     * model from the vehicle-variant XML, falling back to ro.product.model.
     */
    suspend fun fetchVehicleSummary(): VehicleSummary = withContext(Dispatchers.IO) {
        val client = ensureGrpc()

        val swRaw = try { client.getSwVersion() } catch (_: Exception) { "" }
        val firstLine = swRaw.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        val moveOs = when {
            firstLine.isBlank() -> "MoveOS —"
            firstLine.startsWith("MoveOS", ignoreCase = true) -> firstLine
            else -> "MoveOS $firstLine"
        }
        val versionMatch = Regex("""(\d+)\.(\d+)\.(\d+)""").find(firstLine)
        val versionParts = versionMatch?.groupValues?.drop(1)?.mapNotNull { it.toIntOrNull() }
        val major = versionParts?.firstOrNull()
            ?: Regex("""(\d+)""").find(firstLine)?.groupValues?.get(1)?.toIntOrNull()
        val supported = isMoveOsVersionSupported(versionParts, major)

        val model = fetchModelName(client)
        log("[+] $model, $moveOs (supported=$supported)")
        val result = VehicleSummary(model, moveOs, major, supported)
        vehicleSummary.value = result
        result
    }

    /**
     * Accept MoveOS 4.0.3 and newer. Major-only 5.x+ responses remain accepted
     * for daemons that do not return a full semantic version string.
     */
    private fun isMoveOsVersionSupported(parts: List<Int>?, major: Int?): Boolean {
        if (parts != null && parts.size == 3) {
            val (versionMajor, versionMinor, versionPatch) = parts
            return versionMajor > 4 ||
                (versionMajor == 4 && (versionMinor > 0 ||
                    (versionMinor == 0 && versionPatch >= 3)))
        }
        return major != null && major >= 5
    }

    private suspend fun fetchModelName(client: S1GrpcClient): String {
        val xml = try {
            client.shell("cat /data/data/com.olaelectric.vehiclerangeservice/shared_prefs/com.olaelectric.dataanalyser.xml", 10L).second
        } catch (_: Exception) { "" }
        val variant = Regex("""VEHICLE_VARIANT[^>]*>([^<]+)<""", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)
            ?: Regex("""name="[^"]*VARIANT[^"]*">([^<]+)<""", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)
        val raw = (variant ?: try { client.shell("getprop ro.product.model", 8L).second } catch (_: Exception) { "" }).trim()
        val clean = raw.replace("_", " ")
        val base = when {
            clean.contains("S1 Pro", ignoreCase = true) -> "S1 Pro"
            clean.contains("S1 Air", ignoreCase = true) -> "S1 Air"
            clean.contains("S1 X", ignoreCase = true) -> "S1 X"
            clean.contains("S1", ignoreCase = true) -> "S1"
            clean.isNotBlank() -> clean
            else -> "Ola S1"
        }
        // Append generation if the variant string carries it, e.g. "S1 Pro Gen 2".
        val gen = Regex("""gen\s*(\d+)""", RegexOption.IGNORE_CASE).find(clean)?.groupValues?.get(1)
        return if (gen != null && !base.contains("Gen", ignoreCase = true)) "$base Gen $gen" else base
    }

    /**
     * Fetches technical specifications and system properties of the vehicle.
     */
    suspend fun fetchScooterInfo(): ScooterInfo = withContext(Dispatchers.IO) {
        log("[*] Querying vehicle hardware specs and MoveOS version...")
        val client = ensureGrpc()
        val uid = client.shell("id -u").second.trim()
        val android = client.shell("getprop ro.build.version.release").second.trim()
        val build = client.shell("getprop ro.build.display.id").second.trim()
        val model = client.shell("getprop ro.product.model").second.trim()
        val mode = client.shell("getprop persist.ola.security.scootermode").second.trim()
        val info = ScooterInfo(uid = uid, androidVersion = android, buildId = build, model = model, scooterMode = mode)
        log("[+] Model: $model | MoveOS: $build | Android: $android | Root UID: $uid")
        info
    }

    fun disconnect() {
        grpcClient?.close()
        adbManager.disconnect()
        isConnected.value = false
        vehicleSummary.value = null
    }
}
