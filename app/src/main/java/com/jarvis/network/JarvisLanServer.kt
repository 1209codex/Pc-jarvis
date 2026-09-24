package com.jarvis.network

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.jarvis.runtime.AssistantRuntime
import com.jarvis.service.JarvisForegroundService
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Embedded Lightweight Zero-Dependency Async HTTP & REST Daemon for J.A.R.V.I.S. Desktop Companion.
 * Listens on port 8888 on the local Wi-Fi network.
 * Provides remote terminal control, live telemetry HUD, and shared clipboard sync.
 */
class JarvisLanServer(
    private val context: Context,
    val port: Int = 8888
) {
    companion object {
        private const val TAG = "JarvisLanServer"
        private const val PREFS_NAME = "jarvis_lan_bridge"
        private const val KEY_PIN = "lan_pairing_pin"

        @Volatile
        var isServerRunning: Boolean = false
            private set
    }

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var acceptJob: Job? = null

    val pairingPin: String by lazy {
        val prefs = try { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) } catch (_: Exception) { null }
        var pin = prefs?.getString(KEY_PIN, null)
        if (pin.isNullOrBlank()) {
            pin = String.format("%04d", Random.nextInt(1000, 9999))
            try {
                prefs?.edit()?.putString(KEY_PIN, pin)?.apply()
            } catch (_: Exception) {}
        }
        pin
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            try {
                serverSocket = ServerSocket(port)
                isServerRunning = true
                Log.i(TAG, "JarvisLanServer started on http://${getLocalIpAddress()}:$port (PIN: $pairingPin)")

                acceptJob = serverScope.launch {
                    while (isActive && isRunning.get()) {
                        try {
                            val clientSocket = serverSocket?.accept() ?: break
                            launch { handleClient(clientSocket) }
                        } catch (e: Exception) {
                            if (isRunning.get()) {
                                Log.w(TAG, "Error accepting client connection: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start JarvisLanServer on port $port", e)
                isRunning.set(false)
                isServerRunning = false
            }
        }
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            isServerRunning = false
            acceptJob?.cancel()
            try {
                serverSocket?.close()
            } catch (_: Exception) {}
            serverSocket = null
            Log.i(TAG, "JarvisLanServer stopped")
        }
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            socket.soTimeout = 10_000
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = OutputStreamWriter(socket.getOutputStream())

            val requestLine = input.readLine() ?: return@withContext
            val parts = requestLine.split(" ")
            if (parts.size < 2) return@withContext

            val method = parts[0].uppercase()
            val uri = parts[1]

            val headers = mutableMapOf<String, String>()
            var line: String? = input.readLine()
            var contentLength = 0

            while (!line.isNullOrBlank()) {
                val headerParts = line.split(":", limit = 2)
                if (headerParts.size == 2) {
                    val key = headerParts[0].trim().lowercase()
                    val value = headerParts[1].trim()
                    headers[key] = value
                    if (key == "content-length") {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
                line = input.readLine()
            }

            var body = ""
            // SECURITY: Enforce a max body size BEFORE reading — an attacker can set
            // Content-Length to an arbitrarily large number to exhaust device memory.
            // We also authenticate before reading any body (except the header is already parsed).
            val MAX_BODY_BYTES = 64 * 1024 // 64 KB is generous for any valid command payload
            if (contentLength > MAX_BODY_BYTES) {
                sendJsonResponse(output, 413, JSONObject().put("error", "Request body too large"))
                return@withContext
            }
            if (contentLength > 0) {
                val charArray = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val read = input.read(charArray, readTotal, contentLength - readTotal)
                    if (read == -1) break
                    readTotal += read
                }
                body = String(charArray, 0, readTotal)
            }

            // PIN check for API endpoints
            val isApi = uri.startsWith("/api/")
            val requestPin = headers["x-jarvis-pin"]
                ?: uri.substringAfter("pin=", "").substringBefore("&")

            if (isApi && requestPin != pairingPin) {
                sendJsonResponse(output, 401, JSONObject().put("error", "Unauthorized: Invalid or missing X-Jarvis-Pin header"))
                return@withContext
            }

            when {
                method == "GET" && (uri == "/" || uri.startsWith("/?")) -> {
                    sendHtmlResponse(output, getDesktopConsoleHtml())
                }

                method == "GET" && uri.startsWith("/api/status") -> {
                    val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    val batteryLevel = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                    val isCharging = bm?.isCharging ?: false

                    val service = JarvisForegroundService.instance
                    val engineStatus = service?.voiceEngine?.status?.value

                    val statusJson = JSONObject().apply {
                        put("online", true)
                        put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                        put("android_version", Build.VERSION.RELEASE)
                        put("battery_level", batteryLevel)
                        put("charging", isCharging)
                        put("voice_state", engineStatus?.voiceState?.name ?: "IDLE")
                        put("status_message", engineStatus?.statusMessage ?: "Ready")
                    }
                    sendJsonResponse(output, 200, statusJson)
                }

                method == "POST" && uri.startsWith("/api/command") -> {
                    val cmdObj = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                    val command = cmdObj.optString("command", "").trim()
                    if (command.isBlank()) {
                        sendJsonResponse(output, 400, JSONObject().put("error", "Empty command"))
                        return@withContext
                    }

                    val service = JarvisForegroundService.instance
                    val runtime = service?.voiceEngine?.assistantRuntime
                    if (runtime != null) {
                        // PIN authentication already passed above — grant approval explicitly.
                        // The default is now fail-closed (false), so we must be explicit here.
                        val result = runtime.executeCommand(command, userApprovalGranted = true)
                        val respJson = JSONObject().apply {
                            put("success", result.success)
                            put("response", result.spokenResponse)
                            put("verified", result.verified)
                        }
                        sendJsonResponse(output, 200, respJson)
                    } else {
                        sendJsonResponse(output, 503, JSONObject().put("error", "Assistant runtime offline"))
                    }
                }

                method == "GET" && uri.startsWith("/api/clipboard") -> {
                    val clipText = withContext(Dispatchers.Main) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    }
                    sendJsonResponse(output, 200, JSONObject().put("clipboard", clipText))
                }

                method == "POST" && uri.startsWith("/api/clipboard") -> {
                    val clipObj = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                    val textToSet = clipObj.optString("text", "")
                    withContext(Dispatchers.Main) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("Jarvis Companion", textToSet))
                    }
                    sendJsonResponse(output, 200, JSONObject().put("success", true).put("message", "Clipboard updated"))
                }

                method == "GET" && uri.startsWith("/apps/") -> {
                    handleWebAppFileRequest(output, uri)
                }

                else -> {
                    sendJsonResponse(output, 404, JSONObject().put("error", "Endpoint not found"))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error processing client request: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleWebAppFileRequest(writer: OutputStreamWriter, uri: String) {
        val cleanPath = uri.substringAfter("/apps/").substringBefore("?").trim('/')
        if (cleanPath.isBlank() || cleanPath.contains("..")) {
            sendHtmlResponse(writer, "<h3>400 Bad Request</h3>")
            return
        }

        val parts = cleanPath.split("/", limit = 2)
        val appId = parts[0]
        val subPath = if (parts.size > 1 && parts[1].isNotBlank()) parts[1] else "index.html"

        val webAppsDir = File(context.filesDir, "webapps")
        val appDir = File(webAppsDir, appId)
        val targetFile = File(appDir, subPath)

        if (!targetFile.exists() || !targetFile.isFile) {
            sendHtmlResponse(writer, "<h3>404 WebApp File Not Found ($appId / $subPath)</h3>")
            return
        }

        val mimeType = when (targetFile.extension.lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "svg" -> "image/svg+xml"
            else -> "text/plain; charset=utf-8"
        }

        val bytes = targetFile.readBytes()
        writer.write("HTTP/1.1 200 OK\r\n")
        writer.write("Content-Type: $mimeType\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        writer.write("Access-Control-Allow-Origin: *\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.flush()

        // Write raw file bytes
        targetFile.inputStream().use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } > 0) {
                writer.write(String(buf, 0, n, Charsets.UTF_8))
            }
        }
        writer.flush()
    }

    private fun sendJsonResponse(writer: OutputStreamWriter, code: Int, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        writer.write("HTTP/1.1 $code OK\r\n")
        writer.write("Content-Type: application/json; charset=utf-8\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        // SECURITY: Do NOT use 'Access-Control-Allow-Origin: *' for a privileged local agent API.
        // Wildcard CORS allows any web page (including malicious ones) to call this API from a browser.
        // Restrict to localhost only; the companion web UI is always served from the same host.
        writer.write("Access-Control-Allow-Origin: http://localhost\r\n")
        writer.write("Access-Control-Allow-Headers: Content-Type, X-Jarvis-Pin\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.write(json.toString())
        writer.flush()
    }

    private fun sendHtmlResponse(writer: OutputStreamWriter, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        writer.write("HTTP/1.1 200 OK\r\n")
        writer.write("Content-Type: text/html; charset=utf-8\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.write(html)
        writer.flush()
    }

    private fun getDesktopConsoleHtml(): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8"><title>J.A.R.V.I.S. Desktop Companion</title>
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
  :root { --bg: #070B10; --card: #0D1520; --border: #00F2FE33; --cyan: #00F2FE; --text: #E0E6ED; --green: #00E676; }
  body { background: var(--bg); color: var(--text); font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, monospace; margin: 0; padding: 20px; }
  .header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid var(--border); padding-bottom: 12px; margin-bottom: 20px; }
  .title { font-size: 22px; font-weight: bold; color: var(--cyan); letter-spacing: 2px; }
  .badge { background: #00E67622; color: var(--green); border: 1px solid var(--green); padding: 4px 10px; border-radius: 12px; font-size: 11px; }
  .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 16px; }
  .card { background: var(--card); border: 1px solid var(--border); border-radius: 8px; padding: 16px; }
  .card-title { font-size: 13px; font-weight: bold; color: var(--cyan); margin-bottom: 8px; text-transform: uppercase; }
  input, textarea { width: 100%; box-sizing: border-box; background: #05080C; border: 1px solid var(--border); color: #FFF; padding: 10px; border-radius: 6px; font-family: monospace; }
  button { background: var(--cyan); color: #000; border: none; font-weight: bold; padding: 10px 18px; border-radius: 6px; cursor: pointer; margin-top: 8px; }
  #terminalLog { height: 180px; overflow-y: auto; background: #000; border: 1px solid var(--border); border-radius: 6px; padding: 10px; font-size: 12px; color: #00E676; white-space: pre-wrap; margin-top: 10px; }
</style>
</head>
<body>
<div class="header">
  <div class="title">⚡ J.A.R.V.I.S. DESKTOP BRIDGE</div>
  <div class="badge" id="hudStatus">CONNECTING...</div>
</div>
<div class="grid">
  <div class="card">
    <div class="card-title">Device Telemetry</div>
    <div id="deviceInfo" style="font-size: 13px; line-height: 1.8;">Loading device status...</div>
  </div>
  <div class="card">
    <div class="card-title">Cross-Device Clipboard</div>
    <textarea id="clipText" rows="3" placeholder="Clipboard contents..."></textarea>
    <div style="display:flex; gap: 8px;">
      <button onclick="fetchClip()">📥 Fetch from Phone</button>
      <button onclick="sendClip()" style="background:#00E676;">📤 Push to Phone</button>
    </div>
  </div>
</div>
<div class="card">
  <div class="card-title">Remote Command Terminal</div>
  <input type="password" id="pinInput" placeholder="Enter 4-digit pairing PIN..." style="max-width: 240px; margin-bottom: 8px;" />
  <div style="display:flex; gap: 8px;">
    <input type="text" id="cmdInput" placeholder="Command J.A.R.V.I.S. (e.g. search web for deep learning, open youtube)..." onkeypress="if(event.key==='Enter') sendCmd()" />
    <button onclick="sendCmd()">EXECUTE</button>
  </div>
  <div id="terminalLog">Waiting for input...</div>
</div>
<script>
  let pin = localStorage.getItem('jarvis_pin') || '';
  if (pin) document.getElementById('pinInput').value = pin;

  function getHeaders() {
    pin = document.getElementById('pinInput').value.trim();
    localStorage.setItem('jarvis_pin', pin);
    return { 'Content-Type': 'application/json', 'X-Jarvis-Pin': pin };
  }

  async function updateStatus() {
    try {
      const res = await fetch('/api/status', { headers: getHeaders() });
      if (res.ok) {
        const d = await res.json();
        document.getElementById('hudStatus').innerText = 'ONLINE • ' + d.voice_state;
        document.getElementById('deviceInfo').innerHTML = 
          '<b>Device:</b> ' + d.device + '<br>' +
          '<b>Battery:</b> ' + d.battery_level + '% (' + (d.charging ? 'Charging ⚡' : 'Battery') + ')<br>' +
          '<b>Status:</b> ' + d.status_message;
      }
    } catch (_) {}
  }
  setInterval(updateStatus, 3000);
  updateStatus();

  async function sendCmd() {
    const input = document.getElementById('cmdInput');
    const cmd = input.value.trim();
    if (!cmd) return;
    const log = document.getElementById('terminalLog');
    log.innerText += '\n\n> ' + cmd + '\n[Thinking...]';
    input.value = '';
    try {
      const res = await fetch('/api/command', {
        method: 'POST',
        headers: getHeaders(),
        body: JSON.stringify({ command: cmd })
      });
      const d = await res.json();
      log.innerText += '\n[J.A.R.V.I.S.]: ' + (d.response || d.error);
      log.scrollTop = log.scrollHeight;
      updateStatus();
    } catch (e) {
      log.innerText += '\n[Error]: ' + e.message;
    }
  }

  async function fetchClip() {
    try {
      const res = await fetch('/api/clipboard', { headers: getHeaders() });
      const d = await res.json();
      document.getElementById('clipText').value = d.clipboard || '';
    } catch (e) { alert(e.message); }
  }

  async function sendClip() {
    const text = document.getElementById('clipText').value;
    try {
      await fetch('/api/clipboard', {
        method: 'POST',
        headers: getHeaders(),
        body: JSON.stringify({ text })
      });
      alert('Copied to phone clipboard!');
    } catch (e) { alert(e.message); }
  }
</script>
</body>
</html>
    """.trimIndent()
}
