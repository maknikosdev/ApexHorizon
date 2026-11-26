package com.apexhorizon.app.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apexhorizon.app.data.AppSettings
import com.apexhorizon.app.data.GpxException
import com.apexhorizon.app.data.MapRepository
import com.apexhorizon.app.maps.MapPack
import com.apexhorizon.app.maps.Router
import com.apexhorizon.app.maps.TravelMode
import com.apexhorizon.app.sensors.LastLocation
import com.apexhorizon.app.R
import com.apexhorizon.app.data.GpxParser
import com.apexhorizon.app.data.RouteStore
import com.apexhorizon.app.data.RunStore
import com.apexhorizon.app.data.UiSettings
import com.apexhorizon.app.domain.DemoScenario
import com.apexhorizon.app.domain.DriveEngine
import com.apexhorizon.app.domain.HudState
import com.apexhorizon.app.domain.LatLon
import com.apexhorizon.app.domain.ReplayBuilder
import com.apexhorizon.app.domain.RouteHorizon
import com.apexhorizon.app.domain.RunSummary
import com.apexhorizon.app.domain.Sample
import com.apexhorizon.app.sensors.LiveSampleSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class DriveMode { LIVE, DEMO, REPLAY }

data class DriveInfo(
    val mode: DriveMode,
    val title: String,
    val cameraWanted: Boolean,
    val cameraActive: Boolean = false,
    val replaySpeed: Int = 1,
    val progress: Float = 0f,
    val routeLabel: String,
    val hasRoute: Boolean,
    /** Length of the planned route being followed, or -1 when there is none. */
    val routeTotalM: Double = -1.0,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = AppSettings(app)
    private val runStore = RunStore(File(app.filesDir, "runs"))
    private val routeStore = RouteStore(File(app.filesDir, "route/route.json"))

    /** Navigation back stack. Kept here so the back button logic lives in one place. */
    val stack = mutableStateListOf(Screen.Home)

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<UiSettings> = _settings.asStateFlow()

    private val _route = MutableStateFlow<RouteHorizon?>(null)
    val importedRoute: StateFlow<RouteHorizon?> = _route.asStateFlow()

    private val _runs = MutableStateFlow<List<RunSummary>>(emptyList())
    val runs: StateFlow<List<RunSummary>> = _runs.asStateFlow()

    private val _hud = MutableStateFlow(HudState())
    val hud: StateFlow<HudState> = _hud.asStateFlow()

    private val _drive = MutableStateFlow<DriveInfo?>(null)
    val drive: StateFlow<DriveInfo?> = _drive.asStateFlow()

    private val _summary = MutableStateFlow<RunSummary?>(null)
    val summary: StateFlow<RunSummary?> = _summary.asStateFlow()

    private val _summarySaved = MutableStateFlow(false)
    val summarySaved: StateFlow<Boolean> = _summarySaved.asStateFlow()

    private val _summaryCanSave = MutableStateFlow(false)
    val summaryCanSave: StateFlow<Boolean> = _summaryCanSave.asStateFlow()

    private val _confirmEnd = MutableStateFlow(false)
    val confirmEnd: StateFlow<Boolean> = _confirmEnd.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    // ---- offline maps ------------------------------------------------------------------------
    private val mapRepo = MapRepository(File(app.filesDir, "maps"), app.getString(R.string.maps_manifest_url))
    private val _maps = MutableStateFlow(MapsUi())
    val maps: StateFlow<MapsUi> = _maps.asStateFlow()
    private val _packs = MutableStateFlow<List<LoadedPack>>(emptyList())
    val packs: StateFlow<List<LoadedPack>> = _packs.asStateFlow()
    private val _plan = MutableStateFlow(PlanState())
    val plan: StateFlow<PlanState> = _plan.asStateFlow()
    private val _nav = MutableStateFlow<NavTarget?>(null)
    val navTarget: StateFlow<NavTarget?> = _nav.asStateFlow()
    private var nav: NavTarget?
        get() = _nav.value
        set(v) { _nav.value = v }
    private val downloadJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private var remoteList: List<com.apexhorizon.app.data.RemoteRegion> = emptyList()
    private val progress = java.util.concurrent.ConcurrentHashMap<String, Float>()
    private var lastRerouteMs = 0L
    private var offRouteSinceMs = 0L

    private var engine = DriveEngine(null)
    private var live: LiveSampleSource? = null
    private var replaySamples: List<Sample> = emptyList()
    private var replayIndex = 0
    private var replayJob: Job? = null
    private var startedAtMs = 0L
    private var foreground = true

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val r = routeStore.load()
            val list = runStore.list()
            _route.value = r
            _runs.value = list
            reloadPacks()
        }
    }

    // ---- navigation -------------------------------------------------------------------------

    fun navigate(screen: Screen) {
        if (stack.lastOrNull() != screen) stack.add(screen)
    }

    /** Single place that decides what the Android back button does. */
    fun back() {
        when (stack.lastOrNull()) {
            Screen.Drive -> _confirmEnd.value = true
            else -> if (stack.size > 1) stack.removeAt(stack.lastIndex)
        }
    }

    fun dismissEndDialog() {
        _confirmEnd.value = false
    }

    fun consumeMessage() {
        _message.value = null
    }

    // ---- offline maps: install / update -----------------------------------------------------

    private fun rebuildRows() {
        val installed = mapRepo.installed().associateBy { it.id }
        val remote = remoteList.associateBy { it.id }
        val ids = (installed.keys + remote.keys).toSortedSet()
        val rows = ids.map { id ->
            RegionRow(
                id = id,
                name = remote[id]?.name ?: id.replace('-', ' ').replaceFirstChar { it.uppercase() },
                installed = installed[id],
                remote = remote[id],
                downloading = downloadJobs[id]?.isActive == true,
                progress = progress[id] ?: 0f,
            )
        }
        _maps.value = _maps.value.copy(rows = rows)
    }

    private suspend fun reloadPacks() {
        val loaded = withContext(Dispatchers.IO) {
            _packs.value.forEach { it.pack.close() }
            mapRepo.installed().mapNotNull { r ->
                runCatching { MapPack.open(r.file).let { LoadedPack(r.id, it, Router(it)) } }.getOrNull()
            }
        }
        _packs.value = loaded
        withContext(Dispatchers.IO) { rebuildRows() }
    }

    fun refreshMaps() {
        viewModelScope.launch(Dispatchers.IO) { rebuildRows() }
    }

    /** "Update maps": reads the small manifest from the map server and marks regions with newer data. */
    fun checkForMapUpdates() {
        if (_maps.value.checking) return
        _maps.value = _maps.value.copy(checking = true, error = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { mapRepo.fetchManifest() } }
            result.onSuccess { remoteList = it }
            withContext(Dispatchers.IO) { rebuildRows() }
            _maps.value = _maps.value.copy(
                checking = false,
                checkedOnce = result.isSuccess,
                error = result.exceptionOrNull()?.let { friendly(it) },
            )
        }
    }

    fun downloadRegion(id: String) {
        val region = remoteList.firstOrNull { it.id == id } ?: return
        if (downloadJobs[id]?.isActive == true) return
        progress[id] = 0f
        downloadJobs[id] = viewModelScope.launch {
            val job = coroutineContext[Job]!!
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    mapRepo.download(region, { done, total ->
                        val p = if (total > 0) done.toFloat() / total else 0f
                        if (p - (progress[id] ?: 0f) > 0.01f) {
                            progress[id] = p
                            rebuildRows()
                        }
                    }, { !job.isActive })
                }
            }
            if (result.isSuccess) {
                reloadPacks()
                _message.value = "${region.name} is ready for offline use"
            } else if (job.isActive) {
                _maps.value = _maps.value.copy(error = friendly(result.exceptionOrNull()!!))
            }
            downloadJobs.remove(id)
            withContext(Dispatchers.IO) { rebuildRows() }
        }
        viewModelScope.launch(Dispatchers.IO) { rebuildRows() }
    }

    fun cancelDownload(id: String) {
        downloadJobs[id]?.cancel()
        downloadJobs.remove(id)
        refreshMaps()
    }

    fun deleteRegion(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                _packs.value.firstOrNull { it.id == id }?.pack?.close()
                mapRepo.delete(id)
            }
            if (nav?.packId == id) nav = null
            reloadPacks()
        }
    }

    private fun friendly(t: Throwable): String = when (t) {
        is com.apexhorizon.app.data.MapDownloadException -> t.message ?: "Download failed"
        is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException ->
            "No internet connection. Offline maps you already have keep working."
        is java.io.IOException -> "Network problem: ${t.message ?: "please try again"}"
        else -> "Could not read the map list from the server."
    }

    // ---- trip planning ------------------------------------------------------------------------

    fun openPlan() {
        navigate(Screen.Plan)
    }

    fun setMode(mode: TravelMode) {
        _plan.value = _plan.value.copy(mode = mode, result = null, error = null)
    }

    fun setDestination(w: Waypoint?) {
        _plan.value = _plan.value.copy(to = w, result = null, error = null)
    }

    fun setOrigin(w: Waypoint?) {
        _plan.value = _plan.value.copy(from = w, result = null, error = null)
    }

    fun clearPlan() {
        _plan.value = PlanState()
    }

    fun packFor(p: LatLon): LoadedPack? = _packs.value.firstOrNull { it.pack.contains(p.lat, p.lon) }

    fun myLocation(): LatLon? = LastLocation.get(getApplication())

    fun computeRoute() {
        val st = _plan.value
        val dest = st.to ?: return
        _plan.value = st.copy(busy = true, error = null, result = null)
        viewModelScope.launch {
            val origin = st.from?.latLon ?: myLocation()
            var error: String? = null
            var packId: String? = null
            var plan: com.apexhorizon.app.maps.RoutePlan? = null
            withContext(Dispatchers.Default) {
                val startPack = origin?.let { packFor(it) }
                val endPack = packFor(dest.latLon)
                when {
                    origin == null -> error = "Your location is not available yet. Choose a starting point on the map or in search."
                    startPack == null -> error = "The start is outside your downloaded maps. Download the region first."
                    endPack == null -> error = "The destination is outside your downloaded maps. Download the region first."
                    startPack !== endPack -> error = "Start and destination are in different map regions. Routing between regions is not supported yet."
                    else -> {
                        plan = startPack.router.route(origin, dest.latLon, st.mode)
                        packId = startPack.id
                        if (plan == null) error = "No route found between these points."
                    }
                }
            }
            _plan.value = _plan.value.copy(busy = false, result = plan, packId = packId, error = error)
        }
    }

    /** Makes the planned route the active Route Horizon for the next live drive and returns to Home. */
    fun commitPlan() {
        val st = _plan.value
        val plan = st.result ?: return
        val dest = st.to ?: return
        val id = st.packId ?: return
        val rh = RouteHorizon.create(tripName(st.mode, dest.label), plan.points, RouteHorizon.Kind.PLANNED)
        if (rh == null) {
            _message.value = "This route is too short to guide."
            return
        }
        nav = NavTarget(dest, id, rh, plan, st.mode)
        _message.value = "Trip ready. Press Start drive when you are on the road."
        stack.clear()
        stack.add(Screen.Home)
    }

    private fun tripName(mode: TravelMode, label: String) = if (mode == TravelMode.WALK) "On foot to $label" else "To $label"

    fun clearTrip() {
        nav = null
    }

    private fun maybeReroute(h: HudState) {
        val t = nav ?: return
        val now = System.currentTimeMillis()
        if (h.onRoute || h.gps == com.apexhorizon.app.domain.GpsStatus.LOST || h.speedMps < 2.0) {
            offRouteSinceMs = 0L
            return
        }
        if (offRouteSinceMs == 0L) offRouteSinceMs = now
        if (now - offRouteSinceMs < 6_000 || now - lastRerouteMs < 15_000) return
        lastRerouteMs = now
        val here = engine.currentPosition() ?: return
        val lp = _packs.value.firstOrNull { it.id == t.packId } ?: return
        viewModelScope.launch {
            val plan = withContext(Dispatchers.Default) { lp.router.route(here, t.destination.latLon, t.mode) }
            val rh = plan?.let { RouteHorizon.create(tripName(t.mode, t.destination.label), it.points, RouteHorizon.Kind.PLANNED) }
            if (rh != null && nav === t) {
                nav = NavTarget(t.destination, t.packId, rh, plan, t.mode)
                engine.setRoute(rh)
                _drive.value = _drive.value?.copy(routeTotalM = rh.lengthM)
                offRouteSinceMs = 0L
                _message.value = "New route calculated"
            }
        }
    }

    // ---- settings ---------------------------------------------------------------------------

    fun updateSettings(change: (UiSettings) -> UiSettings) {
        val next = change(_settings.value)
        _settings.value = next
        settingsStore.save(next)
    }

    // ---- route and history ------------------------------------------------------------------

    fun importGpx(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val fallbackName = resolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.removeSuffix(".gpx") else null
                    }
                    val input = resolver.openInputStream(uri) ?: throw GpxException("Could not open the file.")
                    val parsed = input.use { GpxParser.parse(BoundedInputStream(it, MAX_GPX_BYTES)) }
                    val name = parsed.name ?: fallbackName ?: "Imported route"
                    val route = RouteHorizon.create(name, parsed.points, RouteHorizon.Kind.IMPORTED)
                        ?: throw GpxException("This route is too short to use (minimum about 80 m).")
                    routeStore.save(route)
                    route
                }
            }
            result.onSuccess {
                _route.value = it
                _message.value = "Route loaded: ${it.name} (${Units.distance(it.lengthM, false)})"
            }.onFailure {
                _message.value = (it as? GpxException)?.message ?: "Could not import this file."
            }
        }
    }

    fun reverseRoute() {
        val r = _route.value?.reversed() ?: return
        viewModelScope.launch(Dispatchers.IO) { routeStore.save(r) }
        _route.value = r
    }

    fun clearRoute() {
        _route.value = null
        viewModelScope.launch(Dispatchers.IO) { routeStore.clear() }
    }

    fun deleteRun(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runStore.delete(id) }
            _runs.value = _runs.value.filterNot { it.id == id }
        }
    }

    fun deleteAllRuns() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runStore.deleteAll() }
            _runs.value = emptyList()
        }
    }

    fun openRun(run: RunSummary) {
        viewModelScope.launch {
            val track = withContext(Dispatchers.IO) { runStore.loadTrack(run.id) }
            _summary.value = run.copy(track = track)
            _summarySaved.value = true
            _summaryCanSave.value = false
            navigate(Screen.Summary)
        }
    }

    fun saveSummary() {
        val run = _summary.value ?: return
        if (_summarySaved.value) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runStore.save(run) }
            _runs.value = (listOf(run.copy(track = emptyList())) + _runs.value).distinctBy { it.id }
            _summarySaved.value = true
            _message.value = "Run saved to history"
        }
    }

    // ---- drive session ----------------------------------------------------------------------

    fun startLive(cameraAllowed: Boolean) {
        val cam = cameraAllowed && _settings.value.useCamera
        val planned = nav
        val route = planned?.route ?: _route.value
        offRouteSinceMs = 0L
        lastRerouteMs = 0L
        beginSession(
            DriveEngine(route),
            DriveInfo(
                mode = DriveMode.LIVE,
                title = "Live drive",
                cameraWanted = cam,
                routeLabel = if (route != null) "Route: ${route.name}" else "Route: Predictive",
                hasRoute = route != null,
                routeTotalM = if (planned != null) route!!.lengthM else -1.0,
            ),
        )
        resumeSources()
    }

    fun startDemo() {
        val route = DemoScenario.route()
        replaySamples = DemoScenario.samples()
        beginSession(
            DriveEngine(route),
            DriveInfo(
                mode = DriveMode.DEMO,
                title = "Demo drive",
                cameraWanted = false,
                routeLabel = "Route: Demo (synthetic)",
                hasRoute = route != null,
            ),
        )
        resumeSources()
    }

    fun replayRun(run: RunSummary) {
        if (run.track.size < 2) {
            _message.value = "This run has no recorded track to replay."
            return
        }
        val route = RouteHorizon.create(run.title, run.track.map { LatLon(it.lat, it.lon) }, RouteHorizon.Kind.RECORDED)
        replaySamples = ReplayBuilder.samples(run.track)
        beginSession(
            DriveEngine(route),
            DriveInfo(
                mode = DriveMode.REPLAY,
                title = "Replay: ${run.title}",
                cameraWanted = false,
                routeLabel = "Route: Recorded track",
                hasRoute = route != null,
            ),
        )
        resumeSources()
    }

    private fun beginSession(newEngine: DriveEngine, info: DriveInfo) {
        stopEverything()
        engine = newEngine
        replayIndex = 0
        startedAtMs = System.currentTimeMillis()
        _hud.value = HudState()
        _drive.value = info
        _confirmEnd.value = false
        navigate(Screen.Drive)
    }

    fun setReplaySpeed(speed: Int) {
        _drive.value = _drive.value?.copy(replaySpeed = speed)
    }

    fun setCameraActive(active: Boolean) {
        val info = _drive.value ?: return
        if (info.cameraActive == active) return
        _drive.value = info.copy(cameraActive = active)
        if (!active) {
            live?.luma = -1.0
            if (info.cameraWanted && info.mode == DriveMode.LIVE) {
                _message.value = "Camera unavailable — continuing in Sensor Mode"
            }
        }
    }

    fun onLuma(value: Double) {
        if (_drive.value?.cameraActive == true) live?.luma = value
    }

    /** Called when the app leaves/enters the foreground while a drive screen is open. */
    fun onForeground(isForeground: Boolean) {
        foreground = isForeground
        if (_drive.value == null) return
        if (isForeground) resumeSources() else pauseSources()
    }

    private fun resumeSources() {
        val info = _drive.value ?: return
        if (!foreground) return
        when (info.mode) {
            DriveMode.LIVE -> {
                val source = live ?: LiveSampleSource(getApplication()).also { live = it }
                val ok = source.start {
                    val h = engine.process(it)
                    _hud.value = h
                    maybeReroute(h)
                }
                if (!ok) {
                    _message.value = "Location permission is needed for a live drive."
                    abortSession()
                }
            }
            DriveMode.DEMO, DriveMode.REPLAY -> launchReplay()
        }
    }

    private fun pauseSources() {
        live?.stop()
        replayJob?.cancel()
    }

    private fun launchReplay() {
        replayJob?.cancel()
        replayJob = viewModelScope.launch {
            val total = replaySamples.size
            while (replayIndex < total) {
                val sample = replaySamples[replayIndex++]
                _hud.value = engine.process(sample)
                if (replayIndex % 5 == 0) {
                    _drive.value = _drive.value?.copy(progress = replayIndex.toFloat() / total)
                }
                val speed = (_drive.value?.replaySpeed ?: 1).coerceAtLeast(1)
                delay(ReplayBuilder.TICK_MS / speed)
            }
            endDrive()
        }
    }

    private fun stopEverything() {
        replayJob?.cancel()
        live?.stop()
        live = null
    }

    private fun abortSession() {
        stopEverything()
        _drive.value = null
        _confirmEnd.value = false
        while (stack.size > 1 && stack.last() == Screen.Drive) stack.removeAt(stack.lastIndex)
    }

    /** Ends the current drive and shows the run summary (or returns to the previous screen for replays). */
    fun endDrive() {
        val info = _drive.value ?: return
        stopEverything()
        _confirmEnd.value = false
        val e = engine
        nav = null
        val run = RunSummary(
            id = UUID.randomUUID().toString(),
            title = when (info.mode) {
                DriveMode.LIVE -> "Drive " + SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(startedAtMs))
                DriveMode.DEMO -> "Demo drive"
                DriveMode.REPLAY -> info.title
            },
            startedAtMs = startedAtMs,
            durationMs = e.elapsedMs,
            distanceM = e.distanceM,
            avgSpeedMps = e.avgMovingSpeedMps,
            maxSpeedMps = e.maxSpeedMps,
            events = e.events.toList(),
            usedCamera = info.cameraActive,
            isDemo = info.mode != DriveMode.LIVE,
            track = e.track.toList(),
        )
        _drive.value = null
        while (stack.size > 1 && stack.last() == Screen.Drive) stack.removeAt(stack.lastIndex)
        if (info.mode != DriveMode.REPLAY) {
            _summary.value = run
            _summarySaved.value = false
            _summaryCanSave.value = info.mode == DriveMode.LIVE && run.track.size >= 2
            navigate(Screen.Summary)
        }
    }

    override fun onCleared() {
        stopEverything()
        super.onCleared()
    }

    private class BoundedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0 && ++count > limit) throw IOException("File too large")
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                count += n
                if (count > limit) throw IOException("File too large")
            }
            return n
        }
    }

    private companion object {
        const val MAX_GPX_BYTES = 30L * 1024 * 1024
    }
}
