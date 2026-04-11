package com.saura.imagecleanupassistant.mobile

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

class RemoteAccessServer(
    port: Int,
    private val controller: Controller
) : NanoHTTPD(port) {

    interface Controller {
        fun sessionJson(): JSONObject
        fun entriesJson(queueId: String?, sourceId: String?): JSONObject
        fun startScan(folder: String?): JSONObject
        fun deleteImages(imageIds: Set<Long>): JSONObject
        fun openImage(imageId: Long): RemoteImagePayload?
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.method) {
                Method.OPTIONS -> noContentResponse()
                Method.GET -> handleGet(session)
                Method.POST -> handlePost(session)
                else -> jsonResponse(
                    status = Response.Status.METHOD_NOT_ALLOWED,
                    body = JSONObject().put("error", "Method not allowed").toString()
                )
            }
        } catch (error: Exception) {
            jsonResponse(
                status = Response.Status.INTERNAL_ERROR,
                body = JSONObject()
                    .put("error", error.message ?: "Unexpected server error.")
                    .toString()
            )
        }
    }

    private fun handleGet(session: IHTTPSession): Response =
        when (session.uri) {
            "/" -> htmlResponse(buildDashboardHtml())
            "/api/session" -> jsonResponse(body = controller.sessionJson().toString())
            "/api/entries" -> jsonResponse(
                body = controller.entriesJson(
                    queueId = session.parameters["queue"]?.firstOrNull(),
                    sourceId = session.parameters["source"]?.firstOrNull()
                ).toString()
            )
            "/api/image" -> serveImage(session.parameters["id"]?.firstOrNull())
            "/api/health" -> jsonResponse(
                body = JSONObject()
                    .put("status", "ok")
                    .put("port", listeningPort)
                    .toString()
            )
            else -> jsonResponse(
                status = Response.Status.NOT_FOUND,
                body = JSONObject().put("error", "Not found").toString()
            )
        }

    private fun handlePost(session: IHTTPSession): Response {
        val payload = readJsonBody(session)
        return when (session.uri) {
            "/api/scan" -> jsonResponse(
                status = Response.Status.ACCEPTED,
                body = controller.startScan(payload.optString("folder").ifBlank { null }).toString()
            )
            "/api/delete" -> {
                val ids = payload.optJSONArray("imageIds").toLongSet()
                jsonResponse(body = controller.deleteImages(ids).toString())
            }
            else -> jsonResponse(
                status = Response.Status.NOT_FOUND,
                body = JSONObject().put("error", "Not found").toString()
            )
        }
    }

    private fun serveImage(imageId: String?): Response {
        val id = imageId?.toLongOrNull()
            ?: return jsonResponse(
                status = Response.Status.BAD_REQUEST,
                body = JSONObject().put("error", "Missing image id").toString()
            )
        val image = controller.openImage(id)
            ?: return jsonResponse(
                status = Response.Status.NOT_FOUND,
                body = JSONObject().put("error", "Image not found").toString()
            )
        return newChunkedResponse(Response.Status.OK, image.mimeType, image.stream).apply {
            addHeader("Cache-Control", "private, max-age=120")
            addHeader("Access-Control-Allow-Origin", "*")
        }
    }

    private fun readJsonBody(session: IHTTPSession): JSONObject {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        val raw = files["postData"].orEmpty()
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    private fun JSONArray?.toLongSet(): Set<Long> {
        if (this == null) return emptySet()
        val ids = linkedSetOf<Long>()
        for (index in 0 until length()) {
            val value = optLong(index, Long.MIN_VALUE)
            if (value != Long.MIN_VALUE) ids += value
        }
        return ids
    }

    private fun htmlResponse(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("Access-Control-Allow-Origin", "*")
        }

    private fun jsonResponse(
        body: String,
        status: Response.Status = Response.Status.OK
    ): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("Access-Control-Allow-Origin", "*")
        }

    private fun noContentResponse(): Response =
        newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "").apply {
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Access-Control-Allow-Headers", "Content-Type")
            addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        }

    private fun buildDashboardHtml(): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>Photo Cleanup Remote</title>
<style>
:root {
  --bg: #07080f;
  --surface: #10131d;
  --card: #151925;
  --card-alt: #1a2030;
  --border: #2a3146;
  --border-hi: #404b6a;
  --text: #eef2ff;
  --muted: #9aa4c4;
  --soft: #6f7ca7;
  --accent: #7c6af7;
  --accent-soft: rgba(124,106,247,.15);
  --green: #38d996;
  --green-soft: rgba(56,217,150,.14);
  --red: #ff6b6b;
  --red-soft: rgba(255,107,107,.14);
  --orange: #ffb454;
  --orange-soft: rgba(255,180,84,.14);
  --blue: #63b3ff;
  --blue-soft: rgba(99,179,255,.14);
  --shadow: 0 24px 60px rgba(0,0,0,.35);
  --radius: 22px;
}
* { box-sizing: border-box; }
html, body { margin: 0; min-height: 100%; }
body {
  font-family: Inter, Segoe UI, system-ui, sans-serif;
  color: var(--text);
  background:
    linear-gradient(var(--border) 1px, transparent 1px),
    linear-gradient(90deg, var(--border) 1px, transparent 1px),
    radial-gradient(circle at top, rgba(124,106,247,.18), transparent 28%),
    var(--bg);
  background-size: 40px 40px, 40px 40px, auto, auto;
}
.app {
  width: min(1200px, calc(100vw - 32px));
  margin: 0 auto;
  padding: 28px 0 52px;
}
.hero {
  text-align: center;
  margin-bottom: 30px;
}
.badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 18px;
  border-radius: 999px;
  border: 1px solid rgba(124,106,247,.35);
  background: rgba(124,106,247,.12);
  color: #b6abff;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: .08em;
  text-transform: uppercase;
}
.hero h1 {
  margin: 16px 0 10px;
  font-size: clamp(34px, 5vw, 58px);
  line-height: 1.05;
  letter-spacing: -.04em;
}
.hero p {
  max-width: 680px;
  margin: 0 auto;
  color: var(--muted);
  font-size: 16px;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
  margin: 22px 0 0;
}
.chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: rgba(255,255,255,.03);
  color: var(--muted);
  font-size: 13px;
}
.panel {
  background: rgba(17, 21, 31, .94);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 24px;
  box-shadow: var(--shadow);
  margin-bottom: 18px;
  backdrop-filter: blur(12px);
}
.panel h2 {
  margin: 0 0 6px;
  font-size: 20px;
}
.panel p {
  margin: 0;
  color: var(--muted);
}
.stack {
  display: grid;
  gap: 16px;
}
.remote-grid {
  display: grid;
  grid-template-columns: 1.1fr .9fr;
  gap: 16px;
}
.remote-box, .control-box, .stat-card, .entry-card {
  border: 1px solid var(--border);
  border-radius: 18px;
  background: linear-gradient(180deg, rgba(255,255,255,.02), rgba(255,255,255,.01));
}
.remote-box {
  padding: 18px;
}
.remote-url {
  margin-top: 16px;
  padding: 14px 16px;
  border-radius: 14px;
  border: 1px solid rgba(124,106,247,.28);
  background: rgba(124,106,247,.1);
  color: #f3f0ff;
  font-size: 16px;
  font-weight: 600;
  word-break: break-all;
}
.remote-help {
  margin-top: 12px;
  font-size: 13px;
  color: var(--soft);
}
.pill-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 16px;
}
.pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 9px 14px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: rgba(255,255,255,.04);
  color: var(--muted);
  font-size: 12px;
  font-weight: 600;
}
.pill.good {
  border-color: rgba(56,217,150,.32);
  background: var(--green-soft);
  color: var(--green);
}
.pill.warn {
  border-color: rgba(255,180,84,.32);
  background: var(--orange-soft);
  color: var(--orange);
}
.controls {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 12px;
  align-items: center;
  margin-top: 18px;
}
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 18px;
}
.toolbar > * {
  min-width: 0;
}
select, button {
  height: 48px;
  border-radius: 14px;
  border: 1px solid var(--border);
  background: #121726;
  color: var(--text);
  font-size: 14px;
}
select {
  width: 100%;
  padding: 0 14px;
}
button {
  padding: 0 18px;
  cursor: pointer;
  transition: transform .18s ease, border-color .18s ease, background .18s ease;
}
button:hover {
  transform: translateY(-1px);
  border-color: var(--border-hi);
}
button.primary {
  border-color: rgba(124,106,247,.45);
  background: linear-gradient(135deg, rgba(124,106,247,.9), rgba(99,179,255,.9));
  color: white;
  font-weight: 700;
}
button.ghost {
  background: rgba(255,255,255,.04);
  color: var(--muted);
}
button.danger {
  border-color: rgba(255,107,107,.35);
  background: rgba(255,107,107,.12);
  color: #ffb6b6;
}
button:disabled {
  opacity: .5;
  cursor: not-allowed;
  transform: none;
}
.progress {
  margin-top: 18px;
  padding: 16px;
  border-radius: 16px;
  border: 1px solid rgba(99,179,255,.28);
  background: rgba(99,179,255,.08);
}
.progress-bar {
  margin-top: 12px;
  height: 7px;
  border-radius: 999px;
  overflow: hidden;
  background: rgba(255,255,255,.06);
}
.progress-fill {
  width: 55%;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, var(--accent), var(--blue));
  animation: pulse 1.3s infinite ease-in-out;
}
.stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-top: 18px;
}
.stat-card {
  padding: 18px;
  text-align: center;
}
.stat-value {
  font-size: 30px;
  font-weight: 800;
  line-height: 1;
}
.stat-label {
  margin-top: 8px;
  color: var(--soft);
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: .08em;
}
.tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 20px 0 0;
}
.tab {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: rgba(255,255,255,.04);
  color: var(--muted);
  cursor: pointer;
  font-size: 13px;
  font-weight: 700;
}
.tab.active {
  border-color: rgba(124,106,247,.45);
  background: rgba(124,106,247,.18);
  color: white;
}
.tab-count {
  display: inline-flex;
  min-width: 22px;
  justify-content: center;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(255,255,255,.12);
  font-size: 11px;
}
.content-head {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  margin-top: 18px;
}
.content-sub {
  color: var(--muted);
  font-size: 14px;
}
.result-grid {
  display: grid;
  gap: 16px;
  margin-top: 18px;
}
.entry-card {
  padding: 18px;
}
.entry-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}
.entry-title {
  font-size: 18px;
  font-weight: 700;
}
.entry-subtitle {
  margin-top: 4px;
  color: var(--muted);
  font-size: 14px;
}
.entry-meta {
  display: inline-flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 12px;
}
.meta-chip {
  padding: 6px 10px;
  border-radius: 999px;
  border: 1px solid var(--border);
  background: rgba(255,255,255,.04);
  color: var(--soft);
  font-size: 12px;
}
.image-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}
.image-card {
  border-radius: 18px;
  overflow: hidden;
  border: 1px solid var(--border);
  background: #0d1220;
}
.image-frame {
  position: relative;
  aspect-ratio: 1.15;
  background: #0a0f1b;
}
.image-frame img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.image-badge {
  position: absolute;
  top: 12px;
  left: 12px;
  padding: 7px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  backdrop-filter: blur(8px);
  background: rgba(0,0,0,.45);
}
.image-badge.suggest {
  background: rgba(255,107,107,.18);
  color: #ffc4c4;
  border: 1px solid rgba(255,107,107,.3);
}
.image-info {
  padding: 14px;
}
.image-name {
  font-size: 14px;
  font-weight: 700;
}
.image-meta {
  margin-top: 6px;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.5;
}
.action-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 16px;
}
.empty {
  padding: 44px 18px;
  border: 1px dashed var(--border-hi);
  border-radius: 18px;
  color: var(--muted);
  text-align: center;
}
.empty strong {
  display: block;
  margin-bottom: 8px;
  font-size: 18px;
  color: var(--text);
}
.hidden {
  display: none !important;
}
@keyframes pulse {
  0% { transform: translateX(-30%); opacity: .85; }
  50% { transform: translateX(40%); opacity: 1; }
  100% { transform: translateX(110%); opacity: .85; }
}
@media (max-width: 900px) {
  .remote-grid, .stats, .image-grid {
    grid-template-columns: 1fr;
  }
  .controls {
    grid-template-columns: 1fr;
  }
}
</style>
</head>
<body>
<div class="app">
  <header class="hero">
    <div class="badge">LAN dashboard + on-device cleanup intelligence</div>
    <h1>Photo Cleanup Remote</h1>
    <p>Review duplicates, low-quality shots, messaging clutter, screenshots, and document images from any device on the same Wi-Fi network.</p>
    <div class="chips">
      <div class="chip">Exact duplicates</div>
      <div class="chip">Similar copies</div>
      <div class="chip">Low-quality scoring</div>
      <div class="chip">Messaging clutter detection</div>
      <div class="chip">Runs on the phone</div>
    </div>
  </header>

  <section class="panel">
    <div class="remote-grid">
      <div class="remote-box">
        <h2>Connected Phone</h2>
        <p id="remoteSummary">Loading remote session...</p>
        <div class="remote-url" id="remoteUrl">Waiting for connection details...</div>
        <div class="remote-help" id="remoteHelp">Keep this app open on the phone while you review photos from another device.</div>
        <div class="pill-row">
          <div class="pill" id="serverStatusPill">Server offline</div>
          <div class="pill" id="deleteStatusPill">Delete access unknown</div>
        </div>
      </div>
      <div class="remote-box">
        <h2>How To Use</h2>
        <p>1. Keep the Android app open. 2. Connect your laptop and phone to the same Wi-Fi. 3. Open the address shown here in your browser. 4. Scan a folder and review the image queues below.</p>
        <div class="pill-row">
          <div class="pill">Local network only</div>
          <div class="pill">No cloud upload</div>
          <div class="pill">Images stay on the phone</div>
        </div>
      </div>
    </div>
  </section>

  <section class="panel">
    <h2>Scan Controls</h2>
    <p>Select a phone folder and trigger a fresh scan from this browser.</p>
    <div class="controls">
      <select id="folderSelect"></select>
      <button class="primary" id="scanButton">Scan Selected Folder</button>
    </div>
    <div class="progress hidden" id="progressPanel">
      <strong id="progressTitle">Scanning your phone library...</strong>
      <div class="remote-help" id="progressText">Preparing analysis...</div>
      <div class="progress-bar"><div class="progress-fill"></div></div>
    </div>
    <div class="stats" id="statsBar">
      <div class="stat-card">
        <div class="stat-value" id="statImages">0</div>
        <div class="stat-label">Photos scanned</div>
      </div>
      <div class="stat-card">
        <div class="stat-value" id="statIssues">0</div>
        <div class="stat-label">Items to review</div>
      </div>
      <div class="stat-card">
        <div class="stat-value" id="statQueues">0</div>
        <div class="stat-label">Active queues</div>
      </div>
      <div class="stat-card">
        <div class="stat-value" id="statScan">Never</div>
        <div class="stat-label">Last scan</div>
      </div>
    </div>
  </section>

  <section class="panel">
    <h2>Review Queues</h2>
    <p id="resultsSummary">Scan your phone to load the cleanup queues.</p>
    <div class="tabs" id="queueTabs"></div>
    <div class="content-head">
      <div class="content-sub" id="queueDescription">Queue details will appear here.</div>
      <div class="toolbar">
        <select id="sourceSelect"></select>
        <button class="ghost" id="refreshButton">Refresh</button>
      </div>
    </div>
    <div class="result-grid" id="resultsGrid"></div>
    <div class="empty hidden" id="emptyState">
      <strong>No items yet</strong>
      Scan the phone library or switch to another queue.
    </div>
  </section>
</div>

<script>
const ALL_SOURCE_ID = "__all__";
const ALL_FOLDERS_ID = "__all_folders__";
let sessionState = null;
let selectedQueue = "EXACT";
let selectedSource = ALL_SOURCE_ID;
let pollingHandle = null;

function esc(value) {
  return String(value || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

async function fetchJson(url, options) {
  const response = await fetch(url, options);
  const text = await response.text();
  let data = {};
  if (text) {
    try { data = JSON.parse(text); }
    catch (error) { throw new Error("Invalid server response"); }
  }
  if (!response.ok) {
    throw new Error(data.error || data.message || ("Request failed with " + response.status));
  }
  return data;
}

function setPill(element, text, mode) {
  element.textContent = text;
  element.className = "pill" + (mode ? " " + mode : "");
}

function relativeTime(millis) {
  if (!millis) return "Never";
  const delta = Date.now() - millis;
  const minutes = Math.floor(delta / 60000);
  const hours = Math.floor(minutes / 60);
  const days = Math.floor(hours / 24);
  if (minutes < 1) return "Now";
  if (minutes < 60) return minutes + "m";
  if (hours < 24) return hours + "h";
  if (days < 7) return days + "d";
  return new Date(millis).toLocaleDateString();
}

async function refreshSession() {
  sessionState = await fetchJson("/api/session");
  const queueIds = sessionState.queues.map(function(queue) { return queue.id; });
  if (queueIds.indexOf(selectedQueue) === -1) {
    selectedQueue = queueIds[0] || "EXACT";
  }
  const sourceIds = sessionState.availableSources.map(function(source) { return source.id; });
  if (sourceIds.indexOf(selectedSource) === -1) {
    selectedSource = ALL_SOURCE_ID;
  }
  renderSession();
  await renderEntries();
}

function renderSession() {
  document.getElementById("remoteSummary").textContent = sessionState.remoteAccess.statusText;
  document.getElementById("remoteUrl").textContent = sessionState.remoteAccess.localUrl || "The phone server is not reachable right now.";
  document.getElementById("remoteHelp").textContent = sessionState.remoteAccess.deleteHint;

  setPill(
    document.getElementById("serverStatusPill"),
    sessionState.remoteAccess.isEnabled ? "Server live on port " + sessionState.remoteAccess.port : "Server offline",
    sessionState.remoteAccess.isEnabled ? "good" : "warn"
  );
  setPill(
    document.getElementById("deleteStatusPill"),
    sessionState.remoteAccess.remoteDeleteEnabled ? "Browser delete enabled" : "Browser delete requires phone permission",
    sessionState.remoteAccess.remoteDeleteEnabled ? "good" : "warn"
  );

  const folderSelect = document.getElementById("folderSelect");
  folderSelect.innerHTML = '<option value="' + ALL_FOLDERS_ID + '">All folders</option>' +
    sessionState.availableFolders.map(function(folder) {
      const isSelected = sessionState.selectedScanFolder === folder.folder;
      return '<option value="' + esc(folder.folder) + '"' + (isSelected ? ' selected' : '') + '>' +
        esc(folder.folder) + ' (' + folder.count + ')</option>';
    }).join("");

  const sourceSelect = document.getElementById("sourceSelect");
  sourceSelect.innerHTML = sessionState.availableSources.map(function(source) {
    const selected = selectedSource === source.id ? ' selected' : '';
    return '<option value="' + esc(source.id) + '"' + selected + '>' +
      esc(source.title) + ' (' + source.count + ')</option>';
  }).join("");

  document.getElementById("scanButton").disabled = sessionState.isScanning || !sessionState.hasPermission;
  document.getElementById("scanButton").textContent = sessionState.isScanning ? "Scanning..." : "Scan Selected Folder";

  document.getElementById("statImages").textContent = String(sessionState.imageCount);
  document.getElementById("statIssues").textContent = String(sessionState.totalReviewItems);
  document.getElementById("statQueues").textContent = String(sessionState.activeQueueCount);
  document.getElementById("statScan").textContent = relativeTime(sessionState.lastScanMillis);
  document.getElementById("resultsSummary").textContent = sessionState.summaryText;

  const progressPanel = document.getElementById("progressPanel");
  if (sessionState.isScanning) {
    progressPanel.classList.remove("hidden");
    document.getElementById("progressText").textContent = sessionState.statusText;
  } else {
    progressPanel.classList.add("hidden");
  }

  const tabsHtml = sessionState.queues.map(function(queue) {
    const active = queue.id === selectedQueue ? " active" : "";
    return '<button class="tab' + active + '" data-queue="' + queue.id + '">' +
      esc(queue.title) + '<span class="tab-count">' + queue.count + '</span></button>';
  }).join("");
  document.getElementById("queueTabs").innerHTML = tabsHtml;
  Array.from(document.querySelectorAll(".tab")).forEach(function(button) {
    button.addEventListener("click", function() {
      selectedQueue = button.getAttribute("data-queue");
      renderSession();
      renderEntries();
    });
  });

  const activeQueue = sessionState.queues.find(function(queue) { return queue.id === selectedQueue; });
  document.getElementById("queueDescription").textContent = activeQueue ? activeQueue.description : "Select a queue to review.";
}

function imageCard(image, label, suggested) {
  const badgeClass = suggested ? "image-badge suggest" : "image-badge";
  const badgeText = suggested ? label + " - suggested delete" : label;
  return '<div class="image-card">' +
    '<div class="image-frame">' +
      '<img loading="lazy" src="/api/image?id=' + image.id + '" alt="' + esc(image.name) + '" />' +
      '<div class="' + badgeClass + '">' + esc(badgeText) + '</div>' +
    '</div>' +
    '<div class="image-info">' +
      '<div class="image-name">' + esc(image.name) + '</div>' +
      '<div class="image-meta">' + esc(image.folder) + '<br />' + esc(image.dimensionsText) + ' · ' + esc(image.sizeText) + '</div>' +
    '</div>' +
  '</div>';
}

function renderSingleEntry(entry) {
  return '<article class="entry-card">' +
    '<div class="entry-head">' +
      '<div><div class="entry-title">' + esc(entry.title) + '</div><div class="entry-subtitle">' + esc(entry.subtitle) + '</div></div>' +
    '</div>' +
    '<div class="entry-meta"><span class="meta-chip">' + esc(entry.metaText) + '</span></div>' +
    '<div class="result-grid">' + imageCard(entry.image, "Review", false) + '</div>' +
    '<div class="action-row">' +
      '<button class="danger" onclick="deleteImages([' + entry.image.id + '], \'' + esc(entry.image.name) + '\')">Delete This Image</button>' +
    '</div>' +
  '</article>';
}

function renderPairEntry(entry) {
  const leftSuggested = entry.suggestedDeleteId === entry.first.id;
  const rightSuggested = entry.suggestedDeleteId === entry.second.id;
  return '<article class="entry-card">' +
    '<div class="entry-head">' +
      '<div>' +
        '<div class="entry-title">' + esc(entry.title) + '</div>' +
        '<div class="entry-subtitle">' + esc(entry.subtitle) + '</div>' +
      '</div>' +
      '<div class="meta-chip">Confidence ' + entry.confidence + '%</div>' +
    '</div>' +
    '<div class="image-grid">' +
      imageCard(entry.first, "Left", leftSuggested) +
      imageCard(entry.second, "Right", rightSuggested) +
    '</div>' +
    '<div class="action-row">' +
      '<button class="danger" onclick="deleteImages([' + entry.first.id + '], \'' + esc(entry.first.name) + '\')">Delete Left</button>' +
      '<button class="danger" onclick="deleteImages([' + entry.second.id + '], \'' + esc(entry.second.name) + '\')">Delete Right</button>' +
      '<button class="ghost" onclick="deleteImages([' + entry.first.id + ',' + entry.second.id + '], \'' + esc(entry.title) + '\')">Delete Both</button>' +
      '<button class="primary" onclick="deleteImages([' + entry.suggestedDeleteId + '], \'' + esc(entry.title) + '\')">Delete Suggested</button>' +
    '</div>' +
  '</article>';
}

async function renderEntries() {
  const resultsGrid = document.getElementById("resultsGrid");
  const emptyState = document.getElementById("emptyState");

  if (!sessionState || sessionState.imageCount === 0) {
    resultsGrid.innerHTML = "";
    emptyState.classList.remove("hidden");
    emptyState.innerHTML = "<strong>No photos scanned yet</strong>Run a scan to start reviewing your phone library.";
    return;
  }

  const payload = await fetchJson(
    "/api/entries?queue=" + encodeURIComponent(selectedQueue) + "&source=" + encodeURIComponent(selectedSource)
  );

  if (!payload.entries.length) {
    resultsGrid.innerHTML = "";
    emptyState.classList.remove("hidden");
    emptyState.innerHTML = "<strong>" + esc(payload.emptyText) + "</strong>Try another queue or scan a different folder.";
    return;
  }

  emptyState.classList.add("hidden");
  resultsGrid.innerHTML = payload.entries.map(function(entry) {
    return entry.kind === "pair" ? renderPairEntry(entry) : renderSingleEntry(entry);
  }).join("");
}

async function startScan() {
  const folderValue = document.getElementById("folderSelect").value;
  const body = {
    folder: folderValue === ALL_FOLDERS_ID ? null : folderValue
  };
  await fetchJson("/api/scan", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
  await refreshSession();
}

async function deleteImages(ids, label) {
  if (!sessionState.remoteAccess.remoteDeleteEnabled) {
    alert(sessionState.remoteAccess.deleteHint);
    return;
  }

  const confirmed = confirm("Delete " + label + " from the phone?");
  if (!confirmed) return;

  const result = await fetchJson("/api/delete", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ imageIds: ids })
  });

  if (result.deletedIds && result.deletedIds.length) {
    const suffix = result.errors && result.errors.length ? "\\n\\nWarnings:\\n" + result.errors.join("\\n") : "";
    alert("Deleted " + result.deletedIds.length + " image(s)." + suffix);
  } else {
    alert((result.errors || ["Nothing was deleted."]).join("\\n"));
  }

  await refreshSession();
}

document.addEventListener("DOMContentLoaded", function() {
  document.getElementById("scanButton").addEventListener("click", startScan);
  document.getElementById("refreshButton").addEventListener("click", refreshSession);
  document.getElementById("sourceSelect").addEventListener("change", function(event) {
    selectedSource = event.target.value;
    renderEntries();
  });

  refreshSession().catch(function(error) {
    alert(error.message || "Could not load the phone session.");
  });

  pollingHandle = setInterval(function() {
    refreshSession().catch(function() {});
  }, 3000);
});
</script>
</body>
</html>
        """.trimIndent()
}
