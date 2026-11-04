package com.shelflife.tv

import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors

/**
 * One full-screen 3D room driven entirely by the TV remote.
 * Room: ▲ TV, ▶ shelf, ◀ the Settings gear, hold OK to shuffle. TV: ▼ (or BACK) to the room.
 * Shelf: ◀ ▶ choose a case, ▲ ▼ change shelf, OK pick it up, Back to the room.
 * Holding a case: ◀ ▶ turn it over, OK put the disc in the player, Back put it back.
 * Disc menu: ▲ ▼ and OK, Back to the main menu / eject.
 * Watching: OK play/pause, ◀ ▶ rewind / fast-forward, ▲ +30 s, ▼ −10 s, Back the disc menu.
 */
/** Bump when the spine artwork changes, so cached spines are reprinted. */
private const val SPINE_STYLE = 12
/** Calibration: + plays the camera whooshes later, − earlier, to suit the TV's picture and sound delay. */
private const val SFX_DELAY_MS = 0L

class MainActivity : ComponentActivity() {
    private lateinit var room: RoomRenderer
    private lateinit var flick: SoundPool
    private val menuMusic by lazy { MenuMusic(this) }
    private val menuMusicOn get() = prefs.getBoolean("menuMusic", true)
    private var flickId = 0
    private var clackId = 0
    private var pulloutId = 0
    private var zoomInId = 0
    private var zoomOutId = 0
    private var trayOpenId = 0; private var trayCloseId = 0; private var discDropId = 0; private var discSpinId = 0
    private lateinit var screensaver: Screensaver
    private val screensaverHolder by lazy { FrameLayout(this) }
    private val overlayState = mutableStateOf(OverlayState(keys = emptyList()))

    private val store by lazy { TokenStore(this) }
    private val plex by lazy { Plex(store.clientId) }
    // Twelve at once for Plex's latency, but at background priority: decoding, printing and compressing
    // spines on the TV box's 4 cores must never take CPU from the frame being drawn (a shuffle's 3D
    // frames went from 5 ms to 20–50 ms of CPU while all twelve printed).
    private val network = Executors.newFixedThreadPool(12) { r -> Thread { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() } }
    private val main = Handler(Looper.getMainLooper())
    private var server: Plex.Server? = null
    private var libraryCount = 0
    private var signIn: SignIn? = null
    private var signInRun = 0      // bumps to cancel a running code poll
    // The next shuffle's films, fetched in the background after each restock so holding OK doesn't wait on Plex.
    private var nextShelf: Pair<List<Movie>, Int>? = null
    private var nextShelfFrom: Plex.Server? = null
    private var stockRun = 0       // bumps so late artwork from an old shelf is ignored
    private var status: String? = null

    // The disc in the player
    private lateinit var playerView: PlayerView
    private lateinit var disc: DiscPlayer
    private val menuState = mutableStateOf<DiscMenuState?>(null)
    private val osdState = mutableStateOf<String?>(null)

    // --- Streaming-style playback controls (Settings → Playback controls) ---
    // Shown over the film in the full-screen TV view only; DVD style keeps the green readouts instead.
    private val streaming get() = prefs.getString("playbackControls", "dvd") == "streaming"
    private val controls = mutableStateOf<StreamingInfo?>(null)
    private var extraTitle: String? = null
    private var seekTarget: Long? = null
    private val hideControls = Runnable { if (disc.playing && seekTarget == null) controls.value = null }
    private val doSeek = Runnable { seekTarget?.let { disc.seekTo(it) }; seekTarget = null; showControls() }

    /** Brings the controls up; they go again after 4 s of playing (they stay while paused). */
    private fun showControls() {
        val movie = discMovie ?: return
        if (controls.value == null) controls.value = StreamingInfo(
            title = extraTitle.takeIf { disc.extra } ?: movie.title,
            details = if (disc.extra) movie.title else listOfNotNull(movie.year?.toString(), movie.rating?.substringAfterLast('/')?.uppercase(), version?.label?.takeIf { movie.versions.size > 1 }).joinToString("  ·  "),
            position = { disc.position }, duration = { disc.duration }, playing = { disc.playing }, target = { seekTarget })
        main.removeCallbacks(hideControls); main.postDelayed(hideControls, 4_000)
    }
    /** A camera move you asked for, with a whoosh: in toward the shelf or TV, out to the room. */
    private fun zoom(point: Viewpoint): Boolean {
        // zoom_in peaks at -8.7 dB like the flick, 230 ms in; zoom_out peaks at -9.4 dB, with a low-mid lift so TV speakers carry it (about 2 dB under zoom_in), 340 ms in.
        if (point == Viewpoint.ROOM) atMidMove("sfxZoom", zoomOutId, 1f, peakMs = 340, seconds = 0.75f)
        else atMidMove("sfxZoom", zoomInId, 1f, peakMs = 230, seconds = 0.75f)
        return room.go(point)
    }
    /**
     * Plays a clip so its loudest moment ([peakMs] in) lands half-way through a [seconds]-long move, when
     * the move is fastest. A newer one replaces one still waiting.
     */
    private var waitingSfx: Runnable? = null
    private fun atMidMove(key: String, id: Int, volume: Float, peakMs: Long, seconds: Float) {
        waitingSfx?.let { main.removeCallbacks(it) }
        val play = Runnable { sfx(key, id, volume) }
        waitingSfx = play
        main.postDelayed(play, maxOf(0L, (seconds * 500).toLong() - peakMs + SFX_DELAY_MS))
    }
    /** A sound effect, unless it's switched off in Settings → Sound effects. */
    private fun sfx(key: String, id: Int, volume: Float, rate: Float = 1f) {
        if (!prefs.getBoolean(key, true)) return
        val v = volume * sfxVolume / 100f
        flick.play(id, v, v, 1, 0, rate)
    }
    private val sfxVolume get() = prefs.getInt("sfxVolume", 100)  // Settings → Sound effects → Effects volume
    private fun closeControls() { main.removeCallbacks(hideControls); main.removeCallbacks(doSeek); seekTarget = null; controls.value = null }

    /** Streaming-style keys while watching: OK pauses and plays, ◀ ▶ step 10 s (⏪ ⏩ 30 s), BACK hides the
     *  controls, then opens the disc menu. Steps add up and the film moves once you stop pressing. */
    private fun streamingKey(keyCode: Int) {
        fun step(seconds: Int) {
            val end = disc.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            seekTarget = ((seekTarget ?: disc.position) + seconds * 1000L).coerceIn(0, end - 1_000)
            main.removeCallbacks(doSeek); main.postDelayed(doSeek, 450)
            showControls()
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { if (disc.playing) disc.pause() else disc.play(); showControls() }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { disc.play(); showControls() }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { disc.pause(); showControls() }
            KeyEvent.KEYCODE_DPAD_LEFT -> step(-10)
            KeyEvent.KEYCODE_DPAD_RIGHT -> step(10)
            KeyEvent.KEYCODE_MEDIA_REWIND -> step(-30)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> step(30)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> showControls()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (controls.value != null) closeControls() else showMenu(MenuPage.MAIN)
            KeyEvent.KEYCODE_MENU -> showMenu(MenuPage.MAIN)
        }
    }
    private var discMovie: Movie? = null
    private var discInfo: Plex.DiscInfo? = null
    private var mainPosition = 0L
    private var versionChoice = 0
    private val version get() = discMovie?.versions?.getOrNull(versionChoice)
    private val playKey get() = version?.ratingKey ?: discMovie?.id.orEmpty()
    private val playIndex get() = version?.mediaIndex ?: 0
    private var synopsisScroll = 0

    // The 2000s warning screens before a film starts from the beginning. The first 3 s can't be
    // skipped (pressing shows the prohibited icon, as players did); after that OK skips.
    private val warningCard = mutableStateOf<Int?>(null)
    private val prohibited = mutableStateOf(false)
    private var warningStart = 0L
    private var warningRun = 0
    private var afterWarning: (() -> Unit)? = null

    // Follows the old single "warnings" switch until changed.
    private val fbiOn get() = prefs.getBoolean("fbiWarning", prefs.getBoolean("warnings", true))
    // QuickPlay: the start-up choice after the tray closes. Trailers come from Plex (the shelf's own films).
    private val quickPlayOn get() = prefs.getBoolean("quickPlay", true)
    private val quickPlaySeconds get() = prefs.getInt("quickPlaySeconds", 14)   // the background video runs 14 s
    private val trailerCount get() = prefs.getInt("trailerCount", 3)
    private val quickChoice = mutableStateOf(0)          // 0 QuickPlay, 1 Previews then menu, 2 straight to the menu
    private val quickDeadline = mutableStateOf(0L)
    private var trailerRun = 0
    private var trailers: List<Plex.Extra>? = null      // null while Plex is still being asked
    private var wantTrailers = false                     // Main Menu was chosen before the search finished
    private var trailerMode = false                      // a trailer is playing (▶ skips, Menu/Back end them)
    private var quickPlayed = false                      // QuickPlay chose the film: its bonus features follow it
    private val trailerQueue = ArrayDeque<Plex.Extra>()

    /** The warning card for 6.5 s, then [then]. */
    private fun showWarning(then: () -> Unit) {
        menuMusic.stop()
        val run = ++warningRun
        afterWarning = then
        warningStart = System.currentTimeMillis()
        menuState.value = null
        warningCard.value = 0
        refresh()
        main.postDelayed({ if (run == warningRun) endWarning() }, 6_500)
    }

    /** "And now, our Feature Presentation" ([clip], picked by the film's studio), with the ⏭ icon flashing up as
     *  if the previews were skipped. */
    private val skipShown = mutableStateOf(false)
    private val featureClip = mutableStateOf(Bumpers.DISNEY)
    private fun showFeature(clip: Bumpers.Clip, then: () -> Unit) {
        if (Extras.file(clip.file) == null) { then(); return }  // that studio's intro isn't downloaded
        menuMusic.stop()
        val run = ++warningRun
        afterWarning = then
        warningStart = 0L  // nothing to sit through: OK skips straight to the film
        menuState.value = null
        featureClip.value = clip
        warningCard.value = 2
        skipShown.value = true
        refresh()
        main.postDelayed({ if (run == warningRun) skipShown.value = false }, 1_400)
        // The clip ends it (AntiPiracyScreen's onFeatureEnd); this only covers a player that never finishes.
        main.postDelayed({ if (run == warningRun) endWarning() }, clip.fallbackMs)
    }

    private fun endWarning() {
        warningRun++
        warningCard.value = null
        prohibited.value = false
        skipShown.value = false
        afterWarning?.also { afterWarning = null }?.invoke()
    }

    private fun warningKey(keyCode: Int) {
        if (warningCard.value == 3) {  // the QuickPlay card: ▲ ▼ choose, OK or the countdown confirms, Back is Main Menu
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> if (quickChoice.value > 0) { quickChoice.value--; sfx("sfxFlick", flickId, 0.7f) }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> if (quickChoice.value < 2) { quickChoice.value++; sfx("sfxFlick", flickId, 0.7f) }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> chooseQuick()
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MENU -> { quickChoice.value = 2; chooseQuick() }
            }
            return
        }
        if (warningCard.value == 4) { endWarning(); return }   // Coming Soon: any key goes on to the first trailer
        if (System.currentTimeMillis() - warningStart < 3_000) {
            prohibited.value = true
            val run = warningRun
            main.postDelayed({ if (run == warningRun) prohibited.value = false }, 900)
        } else endWarning()
    }
    /** The QuickPlay card, after the tray closes. QuickPlay picks itself when the countdown runs out. */
    private fun showQuickPlay() {
        val run = ++warningRun
        afterWarning = null
        warningStart = 0L
        menuState.value = null
        quickChoice.value = 0
        val total = quickPlaySeconds * 1000L
        quickDeadline.value = System.currentTimeMillis() + total
        warningCard.value = 3
        refresh()
        findTrailers()
        main.postDelayed({ if (run == warningRun && warningCard.value == 3) chooseQuick() }, total)
    }
    private fun chooseQuick() {
        if (quickChoice.value == 1 && trailers == null) { wantTrailers = true; warningRun++; return }   // the card waits for Plex's answer
        warningRun++
        warningCard.value = null
        quickPlayed = quickChoice.value == 0
        when (quickChoice.value) {
            0 -> if (discInfo?.partKey != null) playFeature(intro = true) else showMenu(MenuPage.MAIN)
            1 -> startTrailers()
            else -> showMenu(MenuPage.MAIN)
        }
    }
    /** Looks for trailers among the shelf's other films, the way a disc's previews ran: titles released in the
     *  three years before the feature or the year after, the feature's own studio first, then unwatched ones.
     *  Only if that yields fewer than wanted does the window widen, and never past six years either way. */
    private fun findTrailers() {
        val server = server ?: return
        val feature = discMovie ?: return
        val run = ++trailerRun
        trailers = null; wantTrailers = false
        val want = trailerCount
        if (want == 0) { trailers = emptyList(); return }
        val bumper = Bumpers.forMovie(feature, this)
        val year = feature.year
        val others = room.shelf.movies().filter { it.id != feature.id && it.year != null }.shuffled()
        fun inWindow(m: Movie, back: Int, ahead: Int) = year == null || (m.year!! in (year - back)..(year + ahead))
        val tiers = listOf(3 to 1, 6 to 2)   // (years before, years after): the strict window, then one wider try
        network.execute {
            val found = ArrayList<Plex.Extra>(); val tried = HashSet<String>()
            for ((back, ahead) in tiers) {
                if (found.size >= want) break
                val ranked = others.filter { inWindow(it, back, ahead) && it.id !in tried }
                    .sortedBy { m -> (if (bumper != null && Bumpers.forMovie(m, this) == bumper) 0 else 2) + (if (m.watched) 1 else 0) }
                for (m in ranked.take(14)) {
                    if (run != trailerRun || found.size >= want) break
                    tried += m.id
                    plex.trailers(server, m.id).firstOrNull()?.let { found += Plex.Extra(m.title, it.partKey) }
                }
            }
            android.util.Log.i("ShelfLifeDisc", "trailers for ${feature.title} (${feature.year}): ${found.map { it.title }}")
            main.post { if (run == trailerRun) { trailers = found; if (wantTrailers) { wantTrailers = false; warningCard.value = null; startTrailers() } } }
        }
    }
    /** "Coming Soon" (only when there is something to show), then each trailer in turn, then the disc menu. */
    private fun startTrailers() {
        val list = trailers.orEmpty()
        if (list.isEmpty()) { showMenu(MenuPage.MAIN); return }
        trailerQueue.clear(); trailerQueue.addAll(list)
        val run = ++warningRun
        afterWarning = { nextTrailer() }
        warningStart = System.currentTimeMillis()
        warningCard.value = 4
        refresh()
        main.postDelayed({ if (run == warningRun) endWarning() }, 3_000)
    }
    private fun nextTrailer() {
        val server = server
        val t = trailerQueue.removeFirstOrNull()
        if (server == null || t?.partKey == null) { endTrailers(); return }
        trailerMode = true
        if (disc.loaded && !disc.extra) mainPosition = disc.position
        extraTitle = t.title
        disc.load(plex.directUrl(server, t.partKey), hls = false, startMs = 0, play = true, extra = true, fallback = null)
        watch()
    }
    private fun endTrailers() {
        trailerMode = false; trailerQueue.clear()
        playerView.visibility = View.GONE
        disc.stop()
        showMenu(MenuPage.MAIN)
    }

    private var audioChoice = 0          // index into discInfo.audio (Plex's list = the file's order)
    private var subtitleChoice = -1      // index into discInfo.subtitles, -1 = off
    private var checkedDefaultAudio = false
    private var inserting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Coming back to ShelfLife, Android would first show a screenshot of the last screen (the room)
        // until the app draws; without one it shows the plain splash, then the curtain or the menu.
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        val surface = SurfaceView(this)
        playerView = PlayerView(this).apply {
            visibility = View.GONE
            setBackgroundColor(android.graphics.Color.BLACK)         // letterbox bars are black, not the room
            setShutterBackgroundColor(android.graphics.Color.BLACK)
            isFocusable = false
            keepScreenOn = true
            (videoSurfaceView as? SurfaceView)?.setZOrderMediaOverlay(true)  // above the 3D room's surface
        }
        setContentView(FrameLayout(this).apply {
            addView(surface)
            addView(screensaverHolder)
            addView(playerView)
            addView(searchField, FrameLayout.LayoutParams(1, 1))
            addView(ComposeView(this@MainActivity).apply {
                setContent {
                    Box {
                        // The disc menu fades in, and fades away over the film when you press Play.
                        val lastMenu = androidx.compose.runtime.remember { mutableStateOf<DiscMenuState?>(null) }
                        menuState.value?.let { lastMenu.value = it }
                        androidx.compose.animation.AnimatedVisibility(menuState.value != null,   // hard cut in and out, like a disc player
                            enter = androidx.compose.animation.EnterTransition.None,
                            exit = androidx.compose.animation.ExitTransition.None) {
                            lastMenu.value?.let {
                                Box(Modifier.fillMaxSize().graphicsLayer { val s = room.screenScale.floatValue; scaleX = s; scaleY = s; clip = true }) { DiscMenu(it, menuActions) }
                            }
                        }
                        SearchScreen(search.value, room.shelfTop.value, searchCovers)
                        Overlay(overlayState.value)
                        osdState.value?.let { OnScreenDisplay(it) }
                        androidx.compose.animation.AnimatedVisibility(controls.value != null && menuState.value == null && warningCard.value == null,
                            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)),
                            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(400))) {
                            val last = androidx.compose.runtime.remember { mutableStateOf<StreamingInfo?>(null) }
                            controls.value?.let { last.value = it }
                            last.value?.let { StreamingControls(it) }
                        }
                        warningCard.value?.let { AntiPiracyScreen(it, prohibited.value, skipShown.value, featureClip.value.file, quickChoice.value, quickDeadline.value, quickPlaySeconds * 1000L) { if (warningCard.value == 2) endWarning() } }
                        room.fpsText.value?.let { FrameReadout(it) }
                        LoadingCurtain(loading.value != null, loadProgress.floatValue, warmedUp.value, filmStop.value, { logoSettled = true }) { curtainShown = true; startRoom() }
                    }
                }
            })
        })
        bench = intent.getBooleanExtra("bench", false)
        // Saved settings, unless the bench is running (it starts from the defaults every time).
        Extras.init(this)
        loadExtras()
        network.execute { if (Extras.download()) main.post { loadExtras() } }
        Companies.init(applicationContext)
        Printing.studioLogos = { movie -> listOfNotNull(studioLogos[Bumpers.forMovie(movie, applicationContext)], Companies.cached(movie)) }
        room = RoomRenderer(this, surface, Quality.parse(intent.getStringExtra("quality"), if (bench) Quality() else savedQuality()))
        // A short flick every time the highlighted case moves along the shelf.
        flick = SoundPool.Builder().setMaxStreams(4)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        flickId = flick.load(this, R.raw.shelf_flick, 1)  // CC0, Killersmurf96, freesound.org/s/422535
        clackId = flick.load(this, R.raw.case_open, 1)   // the case's latch letting go as the lid swings open (CC0, DeantheDinosauce, freesound.org/s/177827)
        pulloutId = flick.load(this, R.raw.case_pullout, 1) // the case sliding off the shelf, very quiet (CC0, MasterSuite, freesound.org/s/667384)
        zoomInId = flick.load(this, R.raw.zoom_in, 1)       // low whoosh into the shelf or TV (CC0, bolkmar, freesound.org/s/475135)
        // The DVD player as the disc goes in (work/make_deck_sfx.py, CC0: freesound 594043 SamsterBirdies, 351378 thaighaudio).
        trayOpenId = flick.load(this, R.raw.tray_open, 1); trayCloseId = flick.load(this, R.raw.tray_close, 1)
        discDropId = flick.load(this, R.raw.disc_drop, 1); discSpinId = flick.load(this, R.raw.disc_spin, 1)
        zoomOutId = flick.load(this, R.raw.zoom_out, 1)     // low whoosh back out to the room (CC0, DJT4NN3R, freesound.org/s/449995)
        // Each flick a touch different in pitch (±8%), the way no two cases catch a finger the same way.
        room.shelf.onFocusMoved = { sfx("sfxFlick", flickId, 1f, 0.92f + 0.16f * Math.random().toFloat()) }
        // The idle DVD-player screen on the TV whenever no disc is in and nothing covers the room.
        screensaver = Screensaver(this, { room.tvQuad }) { discMovie == null && loading.value == null && warningCard.value == null && room.shelf.held == null }
        screensaver.mode = runCatching { Screensaver.Mode.valueOf(prefs.getString("screensaver", "COLORFUL")!!) }.getOrDefault(Screensaver.Mode.COLORFUL)
        screensaver.setLogo(Extras.bitmap(Extras.DVD_VIDEO_LOGO))
        screensaverHolder.addView(screensaver.view)
        disc = DiscPlayer(this, playerView, discEvents)
        watchFrames()
        main.postDelayed(progress, 10_000)
        val token = store.token
        if (intent.getBooleanExtra("spineSheet", false)) network.execute { spineSheet() }
        resume = if (bench || intent.hasExtra("film")) null else loadResume(fromRecreate = savedInstanceState != null)
        if (token == null) reveal() else connect(token)
        main.postDelayed({ reveal() }, 45_000)  // never stay stuck behind the curtain
        main.postDelayed({ enterDone = true; startRoom() }, 1_500)  // in case no open animation is reported
        refresh()
    }

    // OK in the room acts on release, so holding it can shuffle instead.
    private var okDown = false
    private var gearFocused = false
    private val tvAvailable get() = discMovie != null || screensaver.mode != Screensaver.Mode.OFF
    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (!okDown || (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER)) return super.onKeyLongPress(keyCode, event)
        okDown = false
        gearFocused = false
        shuffle()
        return true
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER) return super.onKeyUp(keyCode, event)
        if (okDown) {
            okDown = false
            if (gearFocused) { gearFocused = false; openSettings() }  // in the room a short OK does nothing; holding it shuffles
            refresh(); room.requestRender()
        }
        return true
    }
    private fun shuffle() {
        val server = server
        if (server == null) showStatus("Connect Plex in Settings to shuffle your own films") else restock(server)
        refresh()
    }

    // Quality while running: am start --activity-single-top -n com.shelflife.tv/.MainActivity --es quality "cropHeld=false"
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("quality")?.let { room.quality = Quality.parse(it) }
    }

    // The 3D room starts drawing only once the curtain is up AND Android's open animation is over: that
    // animation fades the window and the 3D surface separately, so a room drawn under the half-faded
    // curtain showed through for a few frames.
    private var curtainShown = false
    private var enterDone = false
    private var roomStarted = false
    override fun onEnterAnimationComplete() { super.onEnterAnimationComplete(); enterDone = true; startRoom() }
    private fun startRoom() {
        if (!curtainShown || !enterDone || roomStarted) return
        roomStarted = true
        room.holding = false
        warmUp()
    }

    override fun onResume() { super.onResume(); room.resume(); screensaver.start() }
    override fun onPause() { room.pause(); screensaver.stop(); super.onPause() }
    override fun onStop() {
        // Home button / TV off / screensaver: pause the film and save the spot in Plex.
        val watching = playerView.visibility == View.VISIBLE && disc.loaded
        if (disc.loaded && !disc.extra) mainPosition = disc.position
        if (disc.loaded && disc.playing) { disc.pause(); report("paused") }
        saveResume(watching)
        returnToMenu = watching  // back from the screensaver/Home mid-film: the disc menu, Resume ready
        super.onStop()
    }
    private var returnToMenu = false
    override fun onStart() {
        super.onStart()
        if (returnToMenu) { returnToMenu = false; showMenu(MenuPage.MAIN) }
    }
    override fun onDestroy() {
        if (isFinishing) resumeFile.delete()  // left on purpose (BACK out of the room): the next launch starts fresh
        signInRun++
        main.removeCallbacks(progress)
        report("stopped"); endTranscodes()
        network.shutdown()  // lets those last reports finish
        menuMusic.stop(); flick.release(); disc.release(); room.destroy(); super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val shelf = room.shelf
        val ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
        val back = keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE
        // The case lifting off the shelf or going back is the heaviest moment for the Onn's GPU, and the
        // UI layer shares it: the hints stay as they are until the case has finished moving.
        val okKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
        val caseMoves = loading.value == null && fetchingCover == null && !inserting && menuState.value == null && discMovie == null &&
            ((room.shelf.held == null && room.viewpoint == Viewpoint.SHELF && okKey) || (room.shelf.held != null && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)))
        if (cardLeaving) return true
        if (caseMoves) { main.removeCallbacks(hideHints); main.postDelayed({ pokeHints(); refresh() }, 1_400) } else pokeHints()
        val handled = timed("key $keyCode") { when {
            loading.value != null -> true
            warningCard.value != null -> { warningKey(keyCode); true }
            fetchingCover != null -> true
            inserting -> true
            signIn != null -> { if (back) closeSignIn(); true }
            settingsFocus != null -> { settingsKey(keyCode, ok, back); true }
            menuState.value != null -> {  // the menu's own items take ▲ ▼ OK; only Back reaches here
                if (back) { if (menuState.value?.page != MenuPage.MAIN) menuActions.open(MenuPage.MAIN) else eject() }
                true
            }
            discMovie != null && streaming -> { streamingKey(keyCode); true }
            discMovie != null && trailerMode -> {  // a trailer: ▶ or next skips it, Menu or Back skips them all
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                        if (trailerQueue.isEmpty()) endTrailers() else nextTrailer()
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MENU -> endTrailers()
                }
                true
            }
            discMovie != null -> {  // watching
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> disc.toggle()
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> disc.scan(forward = false)
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> disc.scan(forward = true)
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MENU -> showMenu(MenuPage.MAIN)
                }
                true
            }
            shelf.held != null -> when {
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> { synopsisScroll++; true }
                keyCode == KeyEvent.KEYCODE_DPAD_UP -> { synopsisScroll = maxOf(0, synopsisScroll - 1); true }
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { shelf.turn(-1); true }
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> { shelf.turn(1); true }
                back -> {
                    // The card goes first (0.12 s fade), then the case flies home and the blur eases out.
                    cardLeaving = true
                    main.postDelayed({
                        cardLeaving = false; cardShown = false; shelf.putBack(); refresh(); room.requestRender()
                        // Picked from search: once the blur has eased out, the camera settles back on the top shelf.
                        if (room.searchLift) main.postDelayed({ if (shelf.held == null && search.value == null) room.liftForSearch(false) }, 550)
                    }, 140)
                    true
                }
                ok -> { insertDisc(); true }
                else -> false
            }
            search.value != null -> { searchKey(keyCode, ok, back); true }
            room.viewpoint == Viewpoint.SHELF -> when {
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { shelf.moveFocus(-1); true }
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> { shelf.moveFocus(1); true }
                // ▲ past the top shelf: search.
                keyCode == KeyEvent.KEYCODE_DPAD_UP -> if (room.shelfRow == Shelf.ROWS - 1) { openSearch(); true } else room.moveShelf(1)
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> room.moveShelf(-1)
                back -> zoom(Viewpoint.ROOM)
                ok -> { if (shelf.restocking) shelf.finishRestockNow(); takeOut(); true }
                else -> false
            }
            // Full-screen TV (no disc in): ▼ or BACK steps back into the room.
            room.viewpoint == Viewpoint.TV -> { val leave = back || keyCode == KeyEvent.KEYCODE_DPAD_DOWN; if (leave) zoom(Viewpoint.ROOM); leave }
            // The room: ▲ the TV, ▶ the shelf, ◀ the gear (OK opens Settings), hold OK shuffles.
            gearFocused -> {
                when {
                    ok -> { event.startTracking(); okDown = true }  // acted on when OK is released
                    keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {}
                    else -> gearFocused = false
                }
                true
            }
            else -> when {
                keyCode == KeyEvent.KEYCODE_DPAD_UP -> { if (tvAvailable) zoom(Viewpoint.TV); true }
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> zoom(Viewpoint.SHELF)
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { gearFocused = true; true }
                keyCode == KeyEvent.KEYCODE_MENU -> { openSettings(); true }
                ok -> { if (event.repeatCount == 0) { event.startTracking(); okDown = true }; true }
                else -> false
            }
        } }
        if (handled) { timed("refresh") { refresh() }; room.requestRender() }
        return handled || super.onKeyDown(keyCode, event)
    }

    /** Logs where UI frame time goes (adb logcat -s ShelfLifePerf), every 5 s. */
    private fun watchFrames() {
        val names = listOf("input" to android.view.FrameMetrics.INPUT_HANDLING_DURATION, "anim" to android.view.FrameMetrics.ANIMATION_DURATION,
            "layout" to android.view.FrameMetrics.LAYOUT_MEASURE_DURATION, "draw" to android.view.FrameMetrics.DRAW_DURATION,
            "sync" to android.view.FrameMetrics.SYNC_DURATION, "gpu-cmd" to android.view.FrameMetrics.COMMAND_ISSUE_DURATION,
            "swap" to android.view.FrameMetrics.SWAP_BUFFERS_DURATION, "total" to android.view.FrameMetrics.TOTAL_DURATION)
        val sums = LongArray(names.size); var count = 0; var since = System.nanoTime()
        val thread = android.os.HandlerThread("frame-metrics").apply { start() }
        window.addOnFrameMetricsAvailableListener({ _, m, _ ->
            names.forEachIndexed { i, (_, id) -> sums[i] += m.getMetric(id) }
            count++
            if (System.nanoTime() - since > 5_000_000_000L) {
                android.util.Log.i("ShelfLifePerf", "UI $count frames, avg ms: " + names.indices.joinToString(" ") { "${names[it].first}=${"%.1f".format(sums[it] / count / 1e6)}" })
                sums.fill(0); count = 0; since = System.nanoTime()
            }
        }, android.os.Handler(thread.looper))
    }

    /** Runs [block] and logs it when it holds the main thread long enough to drop frames. */
    private inline fun <T> timed(label: String, block: () -> T): T {
        val t = System.nanoTime()
        return block().also { val ms = (System.nanoTime() - t) / 1e6; if (ms > (if (bench) 3 else 16)) android.util.Log.i("ShelfLifePerf", "$label took ${"%.1f".format(ms)} ms on the main thread") }
    }

    // --- Plex ---

    /** Shows a plex.tv/link code and waits for it to be entered; codes are replaced as they expire. */
    private fun openSignIn() {
        val run = ++signInRun
        signIn = SignIn(null, "Getting a code…")
        refresh()
        network.execute {
            while (run == signInRun) {
                val pin = runCatching { plex.createPin() }.getOrElse { e ->
                    main.post { if (run == signInRun) { signIn = SignIn(null, "Couldn't reach plex.tv: ${e.message}. Retrying…"); refresh() } }
                    Thread.sleep(5_000); null
                } ?: continue
                main.post { if (run == signInRun) { signIn = SignIn(pin.code, "Waiting for you to enter the code…   BACK to use the sample films"); refresh() } }
                repeat(maxOf(30, (pin.expiresInSeconds - 30) / 2)) {  // until plex.tv retires the code
                    if (run != signInRun) return@execute
                    Thread.sleep(2_000)
                    val token = runCatching { plex.tokenFor(pin) }.getOrNull()
                    if (token != null) {
                        main.post {
                            if (run != signInRun) return@post
                            store.token = token
                            closeSignIn()
                            connect(token)
                        }
                        return@execute
                    }
                }
            }
        }
    }

    private fun closeSignIn() { signInRun++; signIn = null; refresh() }

    // --- Picking up where the app left off ---
    //
    // When ShelfLife stops (screensaver, Home, TV off) it writes what's on screen to a small file. If
    // Android closes it while it waits (it's ~350 MB on a 3 GB box), or Google TV goes back to its
    // home screen, the next start within a couple of hours puts back the same shelf, the same view
    // and highlighted case, a case in your hands, and a disc in the player (its menu, ready to resume).

    private class Resume(val savedAt: Long, val shelf: List<Movie>, val libraryCount: Int, val view: Viewpoint, val row: Int, val focus: Int,
                         val held: Boolean, val disc: Movie?, val version: Int, val position: Long, val watching: Boolean)
    private var resume: Resume? = null
    private val resumeFile by lazy { java.io.File(filesDir, "resume.json") }
    private val RESUME_WINDOW_MS = 2 * 60 * 60 * 1000L

    private fun saveResume(watching: Boolean) {
        if (bench || loading.value != null) return
        val shelf = room.shelf
        runCatching {
            val o = org.json.JSONObject().apply {
                put("savedAt", System.currentTimeMillis())
                put("shelf", org.json.JSONArray().apply { shelf.movies().forEach { put(it.toJson()) } })
                put("libraryCount", libraryCount)
                // Moments in between (the disc going in, the warning cards) come back as their destination.
                val view = when {
                    discMovie != null -> Viewpoint.TV
                    room.viewpoint == Viewpoint.SHELF -> Viewpoint.SHELF
                    room.viewpoint == Viewpoint.TV -> Viewpoint.TV
                    else -> Viewpoint.ROOM
                }
                put("view", view.name); put("row", room.shelfIndex); put("focus", shelf.focus)
                put("held", shelf.held != null && discMovie == null)
                discMovie?.let { put("disc", it.toJson()); put("version", versionChoice); put("position", mainPosition); put("watching", watching) }
            }
            val tmp = java.io.File(filesDir, "resume.json.tmp")
            tmp.writeText(o.toString()); tmp.renameTo(resumeFile)
        }.onFailure { android.util.Log.w("ShelfLifeResume", "couldn't save: ${it.message}") }
    }

    /** The saved state, when this start is a comeback (a recreated activity, or within [RESUME_WINDOW_MS] of stopping). */
    private fun loadResume(fromRecreate: Boolean): Resume? = runCatching {
        if (!resumeFile.exists()) return null
        val o = org.json.JSONObject(resumeFile.readText())
        val age = System.currentTimeMillis() - o.getLong("savedAt")
        if (!fromRecreate && age > RESUME_WINDOW_MS) return null
        val shelf = o.getJSONArray("shelf").let { a -> (0 until a.length()).map { movieFromJson(a.getJSONObject(it)) } }
        Resume(o.getLong("savedAt"), shelf, o.optInt("libraryCount"), Viewpoint.valueOf(o.getString("view")), o.optInt("row", 3), o.optInt("focus", -1),
            o.optBoolean("held"), o.optJSONObject("disc")?.let { movieFromJson(it) }, o.optInt("version"), o.optLong("position"), o.optBoolean("watching"))
            .also { android.util.Log.i("ShelfLifeResume", "resuming: ${it.view} row ${it.row}, ${it.shelf.size} films, held=${it.held}, disc=${it.disc?.title}, ${age / 1000}s after stopping") }
    }.getOrElse { android.util.Log.w("ShelfLifeResume", "couldn't read: ${it.message}"); null }

    /** Once the shelf is up: back to the view, the case and the disc as they were. */
    private fun applyResume() {
        val r = resume ?: return
        resume = null
        val shelf = room.shelf
        if (r.focus >= 0) shelf.restoreFocus(r.focus)
        val disc = r.disc
        when {
            disc != null && server != null -> {
                // The disc is still in the player: its menu on the TV, Resume ready (Plex has the spot too).
                loadDisc(disc, r.version)
                mainPosition = r.position
                room.jumpTo(Viewpoint.TV, r.row)
                showMenu(MenuPage.MAIN)
            }
            r.view == Viewpoint.SHELF || r.held -> {
                room.jumpTo(Viewpoint.SHELF, r.focus.takeIf { it >= 0 }?.div(Shelf.COLUMNS) ?: r.row)
                if (r.held) takeOut()
            }
            r.view == Viewpoint.TV && tvAvailable -> room.jumpTo(Viewpoint.TV, r.row)
        }
        refresh()
    }

    // --- Settings (▲ in the room) ---

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private fun savedQuality() = Quality(
        blur = prefs.getBoolean("blur", true),
        smoothCase = prefs.getBoolean("smoothCase", false),
        smoothRoom = prefs.getBoolean("smoothRoom", false),
        steady30 = prefs.getBoolean("steady30", false),
        showFps = prefs.getBoolean("showFps", false),
        sheen = prefs.getInt("sheen", 2),
        resolution = prefs.getInt("resolution", 1080),
    )
    private var settingsFocus: Int? = null
    private val openSections = HashSet<String>()   // settings sections the user has opened (dropdowns)
    private fun openSettings() { settingsFocus = 0; refresh() }

    /** The rows and what OK does on each. Plex appears only while it isn't connected. */
    private fun settingsRows(): List<Pair<SettingRow, () -> Unit>> {
        val q = room.quality
        fun onOff(on: Boolean) = if (on) "On" else "Off"
        val out = ArrayList<Pair<SettingRow, () -> Unit>>()
        if (server == null) out += SettingRow("Plex", "Not connected. Connect to fill the shelf with your own films.", "Connect") to { settingsFocus = null; openSignIn() }
        fun section(name: String, detail: String, rows: List<Pair<SettingRow, () -> Unit>>) {
            val open = name in openSections
            out += SettingRow(name, detail, if (open) "▾" else "▸", header = true) to { if (open) openSections -= name else openSections += name; refresh() }
            if (open) out += rows
        }
        section("Picture and speed", "How the room is drawn, and what it costs the TV box.", listOf(
            SettingRow("Background blur", "Softens the room behind a case in your hands. Off: the room stays sharp and is drawn every frame.", onOff(q.blur)) to { setQuality(q.copy(blur = !q.blur)) },
            SettingRow("Smooth case edges", "Smooths jagged edges on the case in your hands. Costly: on the Onn, turning a case drops from about 54 to 22 frames a second.", onOff(q.smoothCase)) to { setQuality(q.copy(smoothCase = !q.smoothCase)) },
            SettingRow("Smooth room edges", "Smooths jagged edges across the whole room. Costly: on the Onn, browsing drops from about 57 to 35 frames a second.", onOff(q.smoothRoom)) to { setQuality(q.copy(smoothRoom = !q.smoothRoom)) },
            SettingRow("3D resolution", "The room drawn at 1080p, 900p or 720p and scaled up to fill the screen. Lower is smoother on the TV box and a little softer; menus and text stay sharp.",
                "${q.resolution}p") to { setQuality(q.copy(resolution = listOf(1080, 900, 720).let { it[(it.indexOf(q.resolution) + 1) % it.size] })) },
            SettingRow("Steady 30 fps", "Moves at an even 30 frames a second instead of jumping between 30 and 60. Smoother-looking when the box can't hold 60.", onOff(q.steady30)) to { setQuality(q.copy(steady30 = !q.steady30)) },
            SettingRow("Cover detail", "High: covers fetched at 1000 × 1500, sharpest up close. Standard: half size, so a case comes off the shelf sooner.",
                if (prefs.getBoolean("hiResCovers", true)) "High" else "Standard") to { flip("hiResCovers", true) },
            SettingRow("Frame rate", "Shows frames per second in the top-left corner while things move.", onOff(q.showFps)) to { setQuality(q.copy(showFps = !q.showFps)) },
        ))
        section("Room", "The TV and the cases.", listOf(
            SettingRow("DVD screensaver", "The DVD logo drifting around the TV while no disc is in: colorful (a new color at every wall), grey, white, or off.",
                screensaver.mode.label) to {
                screensaver.mode = Screensaver.Mode.entries[(screensaver.mode.ordinal + 1) % Screensaver.Mode.entries.size]
                prefs.edit().putString("screensaver", screensaver.mode.name).apply()
            },
            SettingRow("Cover sheen", "A plastic sleeve's gloss sliding across the covers as you turn a case. Costs a little on the TV box at High.",
                listOf("Off", "Low", "Medium", "High")[q.sheen]) to { setQuality(q.copy(sheen = (q.sheen + 1) % 4)) },
        ))
        val dream = DvdDream.Settings(this)
        section("TV screensaver", "The bouncing DVD logo as your TV's own screensaver, full screen. Its colour follows DVD screensaver, under Room.", listOf(
            SettingRow("Use as screensaver", "Opens the TV's screensaver settings: choose ShelfLife DVD there. If the TV has no such page, see docs/screensaver.md.", "Open") to { openDreamSettings() },
            SettingRow("Speed", "How fast the logo drifts across the screen.", dream.speeds[dream.speed].first) to { dream.speed = (dream.speed + 1) % dream.speeds.size; refresh() },
            SettingRow("Logo size", "How big the logo is, against the screen.", dream.sizes[dream.size].first) to { dream.size = (dream.size + 1) % dream.sizes.size; refresh() },
            SettingRow("Glow", "A soft glow around the logo, in its colour.", onOff(dream.glow)) to { dream.glow = !dream.glow; refresh() },
            SettingRow("Scanlines", "Fine dark lines across the picture, like an old tube TV.", onOff(dream.scanlines)) to { dream.scanlines = !dream.scanlines; refresh() },
            SettingRow("Corner flash", "The screen flashes when the logo lands in a corner.", onOff(dream.cornerFlash)) to { dream.cornerFlash = !dream.cornerFlash; refresh() },
        ))
        section("Before the film", "The disc's start-up ritual: QuickPlay, warnings, previews, the opener.", listOf(
            SettingRow("Warning card", "The anti-spoiler warning before a film plays from the start.", onOff(fbiOn)) to { flip("fbiWarning", fbiOn) },
            SettingRow("Feature presentation", "\"And now, our Feature Presentation\" before a film plays from the start, after the warnings.",
                onOff(prefs.getBoolean("featurePresentation", true))) to { flip("featurePresentation", true) },
            SettingRow("QuickPlay", "After the disc goes in: QuickPlay straight to the film, or Main Menu by way of the previews. Off: the disc menu comes up as before.", onOff(quickPlayOn)) to { flip("quickPlay", true) },
            SettingRow("QuickPlay countdown", "How long the QuickPlay card waits before starting the film on its own.", "${quickPlaySeconds} s") to {
                prefs.edit().putInt("quickPlaySeconds", listOf(5, 8, 14, 20).let { it[(it.indexOf(quickPlaySeconds) + 1) % it.size] }).apply(); refresh() },
            SettingRow("Previews", "Trailers from Plex for other films on the shelf, before the disc menu when you pick Main Menu. Same studio first, then the same decade.", if (trailerCount == 0) "Off" else "$trailerCount") to {
                prefs.edit().putInt("trailerCount", (trailerCount + 1) % 4).apply(); refresh() },
        ))
        section("Sound effects", "The little sounds as you move around the room and handle the cases.", listOf(
            SettingRow("Effects volume", "How loud all of the room's little sounds are, together.", "$sfxVolume%") to {
                prefs.edit().putInt("sfxVolume", listOf(25, 50, 75, 100).let { it[(it.indexOf(sfxVolume) + 1) % it.size] }).apply(); refresh(); sfx("sfxFlick", flickId, 1f) },
            SettingRow("Shelf flick", "A soft flick each time the highlight moves along the shelf, or between QuickPlay choices.", onOff(prefs.getBoolean("sfxFlick", true))) to { flip("sfxFlick", true) },
            SettingRow("Case pull-out", "The case sliding off the shelf as you take it out.", onOff(prefs.getBoolean("sfxPullout", true))) to { flip("sfxPullout", true) },
            SettingRow("Case opening", "The latch letting go as the case opens to take the disc out.", onOff(prefs.getBoolean("sfxCaseOpen", true))) to { flip("sfxCaseOpen", true) },
            SettingRow("Disc player", "The tray sliding out and back, the disc dropping in, and the spin-up as a disc goes in.", onOff(prefs.getBoolean("sfxDeck", true))) to { flip("sfxDeck", true) },
            SettingRow("Camera whoosh", "A low whoosh as the view moves in to the shelf or TV, and back out to the room.", onOff(prefs.getBoolean("sfxZoom", true))) to { flip("sfxZoom", true) },
        ))
        section("Playback", "While the film plays.", listOf(
            SettingRow("Menu music", "The film's main theme under the disc menu: a 30-second preview from the iTunes Store, looped. Found once per film; nothing is stored.", onOff(menuMusicOn)) to { flip("menuMusic", true); if (!menuMusicOn) menuMusic.stop() },
            SettingRow("Playback controls", "DVD: green readouts that answer each button, like a DVD player. Streaming: the title, a progress bar and the time left, like a streaming app.",
                if (streaming) "Streaming" else "DVD") to { prefs.edit().putString("playbackControls", if (streaming) "dvd" else "streaming").apply(); refresh() },
        ))
        return out
    }

    private fun settingsKey(keyCode: Int, ok: Boolean, back: Boolean) {
        val rows = settingsRows()
        val focus = settingsFocus ?: return
        when {
            back -> settingsFocus = null
            keyCode == KeyEvent.KEYCODE_DPAD_UP -> settingsFocus = maxOf(0, focus - 1)
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> settingsFocus = minOf(rows.size - 1, focus + 1)
            ok || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> rows.getOrNull(focus)?.second?.invoke()
        }
    }

    private fun setQuality(q: Quality) {
        room.quality = q
        prefs.edit().putBoolean("blur", q.blur).putBoolean("smoothCase", q.smoothCase).putBoolean("smoothRoom", q.smoothRoom)
            .putBoolean("steady30", q.steady30).putBoolean("showFps", q.showFps).putInt("sheen", q.sheen).putInt("resolution", q.resolution).apply()
    }
    /** The TV's own screensaver settings, where ShelfLife DVD can be chosen; a hint where the TV hides them. */
    private fun openDreamSettings() {
        val intent = android.content.Intent(android.provider.Settings.ACTION_DREAM_SETTINGS)
        if (intent.resolveActivity(packageManager) != null) runCatching { startActivity(intent) }.onFailure { showStatus("Couldn't open the TV's screensaver settings") }
        else showStatus("This TV hides its screensaver settings: see docs/screensaver.md")
    }
    private fun flip(key: String, default: Boolean) { prefs.edit().putBoolean(key, !prefs.getBoolean(key, default)).apply(); refresh() }

    private fun connect(token: String) {
        showStatus("Finding your Plex server…", sticky = true)
        loading.value?.let { loading.value = "Finding your Plex server…"; loadProgress.floatValue = 0.12f }
        network.execute {
            runCatching { plex.findServer(token) }
                .onSuccess { found ->
                    main.post { server = found; restock(found, resume?.shelf) }
                    // Library survey for checking how versions combine: adb logcat -s ShelfLifeLibrary
                    network.execute { runCatching { plex.libraryReport(found) }.getOrElse { listOf("report failed: ${it.message}") }.forEach { android.util.Log.i("ShelfLifeLibrary", it) } }
                }
                .onFailure { e -> main.post { showStatus("Plex: ${e.message}"); reveal() } }
        }
    }

    /** A fresh random shelf-full (or [saved], the shelf as it was), then each spine reprinted from its poster's colours. */
    private fun restock(server: Plex.Server, saved: List<Movie>? = null) {
        val run = ++stockRun
        showStatus("Loading your movies…", sticky = true)
        loading.value?.let { loadProgress.floatValue = 0.22f }
        val savedTotal = resume?.libraryCount ?: 0
        val ahead = nextShelf?.takeIf { saved == null && nextShelfFrom === server }
        nextShelf = null
        network.execute {
            runCatching { saved?.takeIf { it.isNotEmpty() }?.let { it to maxOf(savedTotal, it.size) } ?: ahead ?: plex.shelf(server) }
                .onSuccess { (fetched, total) ->
                    // Testing: `--es film "<title>"` stocks every slot with that film, so whatever's picked up is it.
                    val movies = intent.getStringExtra("film")?.let { title ->
                        val film = fetched.firstOrNull { it.title == title } ?: runCatching { plex.shelf(server, maxOf(total, 2000)).first.firstOrNull { it.title == title } }.getOrNull()
                        android.util.Log.i("ShelfLifeAudit", "test shelf of \"$title\": ${if (film == null) "not found" else "stocked"}")
                        film?.let { f -> fetched.map { f } }
                    } ?: fetched
                    main.post {
                        if (run != stockRun) return@post
                        libraryCount = total
                        spineQueue.clear()
                        // With the room in view the shelf restocks case by case; behind the curtain it's instant.
                        if (loading.value == null) timed("beginRestock") { room.shelf.beginRestock(movies) }
                        else timed("stock") { room.shelf.stock(movies, print = false) }
                        showStatus("${movies.size} of $total films · via ${server.route}")
                        val listHasLogos = movies.any { it.logo != null }
                        spinesExpected = movies.size; spinesPrinted = 0
                        val prepNanos = java.util.concurrent.atomic.AtomicLong(); val mipNanos = java.util.concurrent.atomic.AtomicLong()
                        // Fetched in random order, so a restock ripples across the whole shelf at random.
                        for (movie in movies.shuffled()) {
                            network.execute {
                                val mips = prepareSpine(server, movie, listHasLogos, prepNanos, mipNanos)
                                main.post {
                                    if (run != stockRun) return@post
                                    mips?.let { spineQueue.addLast(movie.id to it) }
                                    if (++spinesPrinted == spinesExpected) android.util.Log.i("ShelfLifePerf", "spines ready: worker time %d ms reading, decoding or printing, %d ms making mip levels".format(prepNanos.get() / 1_000_000, mipNanos.get() / 1_000_000))
                                    loading.value?.let { loadProgress.floatValue = 0.3f + 0.65f * spinesPrinted / maxOf(1, spinesExpected) }
                                    drainSpines()
                                    checkShelfReady()
                                }
                            }
                        }
                        // Queued behind this shelf's spines: the next shuffle's films, ready before you ask.
                        network.execute { runCatching { plex.shelf(server) }.onSuccess { next -> main.post { if (run == stockRun) { nextShelf = next; nextShelfFrom = server } } } }
                        checkShelfReady()
                    }
                }
                .onFailure { e -> main.post { showStatus("Plex: ${e.message}"); reveal() } }
        }
    }

    /**
     * A film's spine as the GPU takes it, from the raw cache when it's there; otherwise from the older PNG
     * cache, or printed from its poster's colours (and its logo's), then saved raw. Worker threads only.
     */
    private fun prepareSpine(server: Plex.Server, movie: Movie, listHasLogos: Boolean, prepNanos: java.util.concurrent.atomic.AtomicLong, mipNanos: java.util.concurrent.atomic.AtomicLong): Printing.Mips? {
        val cached = spineCache(movie)
        val paletteFile = java.io.File(cached.path.removeSuffix(".png") + ".palette")
        // Line 1: the 18 palette colours; line 2: the cover's main real colour (may be empty).
        runCatching {
            if (paletteFile.exists()) paletteFile.readLines().let { lines ->
                Printing.palettes[movie.id] = lines[0].split(',').map { it.trim().toInt() }.toIntArray()
                lines.getOrNull(1)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.let { Printing.accents[movie.id] = it.toIntArray() }
            }
        }
        // A cached spine is kept as the GPU takes it, every mip level included: reading it back is
        // its whole preparation. An older PNG one, or a newly printed one, is shrunk and saved once.
        val raw = java.io.File(cached.path.removeSuffix(".png") + ".mips")
        val t0 = System.nanoTime()
        var mips = if (movie.thumb != null && paletteFile.exists()) Printing.readMips(raw) else null
        var keep = false
        val spine = if (mips != null) null else if (movie.thumb == null) Printing.spine(movie) else cached.takeIf { it.exists() && paletteFile.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.path) }?.also { keep = true } ?: runCatching {
            val poster = plex.image(server, movie.thumb!!, 24, 36) ?: return@runCatching null
            val palette = Printing.palette(poster)
            val accents = Printing.accents(poster).also { Printing.accents[movie.id] = it }
            Printing.palettes[movie.id] = palette
            runCatching { paletteFile.parentFile?.mkdirs(); paletteFile.writeText(palette.joinToString(",") + "\n" + accents.joinToString(",")) }
            // The title takes its colour from the film's own logo, when Plex has one.
            val logoPath = movie.logo ?: if (!listHasLogos) plex.logo(server, movie.id) else null
            val titleColor = logoPath?.let { plex.image(server, it, 96, 48) }
                ?.let { Printing.logoColor(it, Printing.spineBackground(movie, palette)) }
            Printing.spine(movie, palette, titleColor).also { keep = true }
        }.getOrNull() ?: Printing.spine(movie)  // no poster reachable: the plain printed spine
        val t1 = System.nanoTime()
        if (spine != null) mips = Printing.mips(spine).also {
            if (keep) runCatching { raw.parentFile?.mkdirs(); Printing.writeMips(it, raw); cached.delete() }
        }
        prepNanos.addAndGet(t1 - t0); mipNanos.addAndGet(System.nanoTime() - t1)
        return mips
    }

    /**
     * `am start … --ez logoAudit true`: every film's logo as the disc (PNG) and the spine (default) fetch it,
     * with how much of it is see-through; logos whose edges are solid are saved for a look. Writes
     * logo-audit.tsv and logos/ in the app's external files.
     */
    /** Export only film metadata needed to audit the bumper mapping; no Plex credentials. */
    private fun bumperAudit(server: Plex.Server) {
        val films = runCatching { plex.shelf(server, maxOf(libraryCount, 2000)).first }
            .getOrElse { android.util.Log.w("ShelfLifeAudit", "bumper catalog: ${it.message}"); return }
        val rows = org.json.JSONArray()
        films.sortedWith(compareBy<Movie> { it.title }.thenBy { it.year }).forEach { movie ->
            rows.put(org.json.JSONObject().apply {
                put("title", movie.title)
                put("year", movie.year)
                put("studio", movie.studio)
                put("bumper", when (Bumpers.forMovie(movie, this@MainActivity)) {
                    Bumpers.PARAMOUNT -> "Paramount"
                    Bumpers.DISNEY -> "Disney"
                    Bumpers.FOX -> "Fox"
                    Bumpers.WARNER -> "Warner"
                    Bumpers.SONY -> "Sony"
                    Bumpers.UNIVERSAL -> "Universal"
                    else -> "None"
                })
            })
        }
        java.io.File(getExternalFilesDir(null), "bumper-catalog.json").writeText(rows.toString())
        // One printed case back per opener, to check the distributor logo: files/backs/<title>.png.
        val backs = java.io.File(getExternalFilesDir(null), "backs").apply { deleteRecursively(); mkdirs() }
        listOf("Home Alone", "Interstellar", "Spirited Away", "Batman Begins", "Dunkirk", "Fight Club").forEach { title ->
            val movie = films.firstOrNull { it.title == title } ?: return@forEach
            val poster = movie.thumb?.let { plex.image(server, it, 520, 780) }
            Companies.fetch(movie)
            java.io.File(backs, "$title.png").outputStream().use { Printing.back(movie, poster, 1).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        android.util.Log.i("ShelfLifeAudit", "bumper catalog: ${films.size} films")
    }

    private fun logoAudit(server: Plex.Server) {
        val dir = java.io.File(getExternalFilesDir(null), "logos").apply { deleteRecursively(); mkdirs() }
        val out = StringBuilder("title\tlogo\tpng_alpha\tpng_clear\tpng_edge\tdefault_alpha\tdefault_clear\tsolidity\trim\tink\n")
        fun stats(b: android.graphics.Bitmap): Triple<Boolean, Float, Float> {
            val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
            val clear = px.count { (it ushr 24) < 16 }.toFloat() / px.size
            val edge = (0 until b.width).flatMap { listOf(px[it], px[(b.height - 1) * b.width + it]) } + (0 until b.height).flatMap { listOf(px[it * b.width], px[it * b.width + b.width - 1]) }
            return Triple(b.hasAlpha(), clear, edge.map { it ushr 24 }.average().toFloat() / 255)
        }
        val all = runCatching { plex.shelf(server, maxOf(libraryCount, 2000)).first }.getOrElse { android.util.Log.w("ShelfLifeAudit", "library: ${it.message}"); return }
        for (movie in all.sortedBy { it.title }) {
            val path = movie.logo ?: plex.logo(server, movie.id)
            if (path == null) { out.append("${movie.title}\tnone\n"); continue }
            val png = discLogoFor(server, movie); val plain = plex.image(server, path, 96, 48)
            val a = png?.let { stats(it) }; val b = plain?.let { stats(it) }
            val sil = png?.let { Printing.silhouette(IntArray(it.width * it.height).also { px -> it.getPixels(px, 0, it.width, 0, 0, it.width, it.height) }.map { p -> p ushr 24 }.toIntArray(), it.width, it.height) }
            val ink = png?.let { Printing.inkMask(it) }
            val outcome = when { png == null -> "-"; ink == null -> "title"; ink === png -> "logo"; else -> "keyed" }
            out.append("${movie.title}\tyes\t${a?.first}\t${"%.2f".format(a?.second ?: -1f)}\t${"%.2f".format(a?.third ?: -1f)}\t${b?.first}\t${"%.2f".format(b?.second ?: -1f)}\t${"%.2f".format(sil?.solidity ?: -1f)}\t${"%.2f".format(sil?.rim ?: -1f)}\t$outcome\n")
            val name = movie.title.replace(Regex("[^A-Za-z0-9]+"), "_")
            if (png != null && ((sil?.solidity ?: 0f) > 0.6f || (sil?.rim ?: 0f) > 0.6f || outcome != "logo")) runCatching {
                java.io.File(dir, "$name.png").outputStream().use { png.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                if (ink != null && ink !== png) java.io.File(dir, "$name-ink.png").outputStream().use { ink.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                if (outcome != "logo") java.io.File(dir, "$name-disc.png").outputStream().use { discLabel(movie, png).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        java.io.File(getExternalFilesDir(null), "logo-audit.tsv").writeText(out.toString())
        // Studios, for matching feature-presentation bumpers: how many films each has.
        java.io.File(getExternalFilesDir(null), "studios.tsv").writeText(all.groupingBy { it.studio ?: "(none)" }.eachCount()
            .entries.sortedByDescending { it.value }.joinToString("\n") { "${it.value}\t${it.key}" })
        android.util.Log.i("ShelfLifeAudit", "logo audit done: ${all.size} films")
    }

    /**
     * Once the room is up: every film in the library gets its spine made and cached raw, one at a time on
     * one background thread, so no shuffle waits to make one. Once per library size; a film added later
     * (or reprinted, once watched) is made when it's first shelved.
     */
    private fun warmSpineCache(server: Plex.Server) {
        val total = libraryCount
        if (bench || total == 0 || prefs.getInt("spinesCachedFor", -1) == total) return
        network.execute {
            val all = runCatching { plex.shelf(server, total).first }.getOrNull() ?: return@execute
            val logos = all.any { it.logo != null }
            val started = System.nanoTime()
            val prep = java.util.concurrent.atomic.AtomicLong(); val mip = java.util.concurrent.atomic.AtomicLong()
            val made = all.count { movie ->
                val base = spineCache(movie).path.removeSuffix(".png")
                val cached = movie.thumb == null || (java.io.File("$base.mips").exists() && java.io.File("$base.palette").exists())
                !cached && prepareSpine(server, movie, logos, prep, mip) != null
            }
            android.util.Log.i("ShelfLifePerf", "spine cache: made $made of ${all.size} films in ${(System.nanoTime() - started) / 1_000_000} ms")
            main.post { prefs.edit().putInt("spinesCachedFor", total).apply() }
        }
    }

    /**
     * OK on a case: the cover (and its blurred back) are fetched first, so the case comes off the
     * shelf already printed. After 8 s it comes out anyway with the generated cover.
     */
    private var fetchingCover: String? = null
    private var discLogo: Pair<String, android.graphics.Bitmap>? = null
    /** A film's logo as its disc label takes it (the audit fetches it the same way). */
    private fun discLogoFor(server: Plex.Server, movie: Movie) = (movie.logo ?: plex.logo(server, movie.id))?.let { plex.image(server, it, 640, 320, png = true) }
    private fun takeOut(fromSearch: Boolean = false) {
        val shelf = room.shelf
        val movie = shelf.focused ?: return
        val server = server
        val thumb = movie.thumb
        synopsisScroll = 0
        // Fetch the cover first, then lift at normal speed. No dialog; 8 s fallback to the generated cover.
        // Meanwhile the room is snapshotted (without this case) and blurred, ready for the lift.
        // From search the room is already blurred (the case is lost in it), so that blur stays.
        if (!fromSearch) room.prepareFocusPull(shelf.focusedCaseEntities())
        fetchingCover = movie.id
        var lifted = false
        fun lift() {
            if (fetchingCover != movie.id || lifted) return
            lifted = true
            // The detailed case takes the shelf box's place first and leaves once the GPU has drawn it
            // with its covers: a texture's first use can stall the GPU, and a still case hides that.
            shelf.pickUp(fly = false)
            refresh()
            // The case flies and the background blurs together, both eased over 0.6 s.
            room.afterGpu(afterNextFrame = true) {
                room.whenFocusReady(500) {
                    if (fetchingCover == movie.id) {
                        fetchingCover = null; shelf.flyToHand(); room.startFocusPull(); room.searching = false
                        atMidMove("sfxPullout", pulloutId, 0.56f, peakMs = 280, seconds = 0.6f)  // −5 dB: the clip's RMS sits 5 dB above the flick's
                        // The details card comes in once the case has landed, never during the pull.
                        main.postDelayed({ if (shelf.held?.id == movie.id) { cardShown = true; refresh() } }, 620)
                    }
                }
            }
        }
        if (server == null || thumb == null || shelf.hasPoster(movie.id)) { lift(); prepareLabel(movie); return }
        network.execute {
            val hi = prefs.getBoolean("hiResCovers", true)
            val poster = if (hi) plex.image(server, thumb, 1000, 1500) else plex.image(server, thumb, 520, 780)
            val front = poster?.let { Printing.front(it, if (hi) 1024 else 512) }
            Companies.fetch(movie)  // the production company's logo, before the back is printed
            val back = poster?.let { Printing.back(movie, it, if (hi) 2 else 1) }
            main.post {
                if (front != null && back != null) {
                    shelf.setFront(movie.id, front); shelf.setBack(movie.id, back)
                    room.afterGpu { lift() }  // uploaded first, so the upload doesn't land in the lift
                } else lift()
            }
            // While you look at the case, fetch the film's logo for its disc.
            val logo = discLogoFor(server, movie)
            main.post { if (logo != null) discLogo = movie.id to logo; prepareLabel(movie) }
        }
        main.postDelayed({ lift() }, 8_000)
    }

    // Spine textures go to the GPU a few per frame, so 144 arriving at once never freeze the remote.
    // Startup curtain: stays up until every spine is printed, then fades to reveal the room.
    private val loading = mutableStateOf<String?>("Loading…")
    private val loadProgress = androidx.compose.runtime.mutableFloatStateOf(0.04f)
    private var spinesExpected = 0
    private var spinesPrinted = 0
    private fun checkShelfReady() {
        if (loading.value != null && spinesPrinted >= spinesExpected && spineQueue.isEmpty() && !draining) reveal()
    }
    private fun reveal() {
        if (loading.value == null) return
        filmStop.value = true
        room.requestRender()
        // Lift only once the room has actually been drawn underneath (a few frames in).
        if (room.framesDrawn < 3 || !warmedUp.value || !logoSettled) { main.postDelayed({ reveal() }, 100); return }
        loadProgress.floatValue = 1f
        main.postDelayed({ loading.value = null; applyResume(); pokeHints(6_000); refresh(); server?.let { warmSpineCache(it) } }, 450)
        if (intent.getBooleanExtra("logoAudit", false)) server?.let { s -> network.execute { logoAudit(s) } }  // let the bar reach the end first
        if (intent.getBooleanExtra("bumperAudit", false)) server?.let { s -> network.execute { bumperAudit(s) } }
        if (bench) main.postDelayed({ runBench() }, 2_500)
        if (intent.getBooleanExtra("bakeInsert", false)) main.postDelayed({ bakeInsertClip() }, 2_500)
    }

    /**
     * `adb shell am start -n com.shelflife.tv/.MainActivity --ez bench true`: the same remote presses
     * every run, with frame pacing per phase logged to ShelfLifePerf ("BENCH" lines). Nothing is
     * reported to Plex while it runs.
     */
    private var bench = false
    private fun runBench() {
        val tour = listOf(  // phase, keys, ms between keys, ms to settle after the last key
            Phase("to shelf", listOf(KeyEvent.KEYCODE_DPAD_CENTER), 0, 1_500),
            Phase("browse", List(6) { KeyEvent.KEYCODE_DPAD_RIGHT }, 350, 800),
            Phase("rows", listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN), 900, 1_000),
            Phase("pick up", listOf(KeyEvent.KEYCODE_DPAD_CENTER), 0, 5_000),
            Phase("turn", listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT), 1_200, 1_200),
            Phase("put back", listOf(KeyEvent.KEYCODE_BACK), 0, 1_500),
            Phase("pick again", listOf(KeyEvent.KEYCODE_DPAD_CENTER), 0, 2_500),  // covers already on the GPU
            Phase("insert", listOf(KeyEvent.KEYCODE_DPAD_CENTER), 0, 10_500),
            Phase("eject", listOf(KeyEvent.KEYCODE_BACK), 0, 2_500),
            Phase("shuffle", listOf(SHUFFLE), 0, 6_000),
        )
        var t = 0L
        for (p in tour) {
            main.postDelayed({ room.phase = p.name }, t)
            p.keys.forEachIndexed { i, code -> main.postDelayed({ press(code) }, t + i * p.spacing) }
            t += (p.keys.size - 1) * p.spacing + p.settle
        }
        main.postDelayed({ room.phase = null; android.util.Log.i("ShelfLifePerf", "BENCH done\n" + room.benchLines.joinToString("\n")) }, t)
    }
    /**
     * `am start … --ez spineSheet true`: prints spines for test covers that are hard to letter (red on
     * blue, green on orange, a white cover…) beside the naive title-on-cover version, to
     * <external files>/spine-sheet.png. The check for Printing's legibility rules.
     */
    private fun spineSheet() {
        // Each test cover: the spine's palette (two alternating colours make it busy) and the title colour.
        data class Case(val name: String, val a: Int, val b: Int, val title: Int?, val accent: Int? = null)
        fun c(r: Int, g: Int, b: Int) = android.graphics.Color.rgb(r, g, b)
        val cases = listOf(
            Case("RED ON WHITE", c(238, 236, 232), c(238, 236, 232), c(200, 28, 38)),
            Case("RED ON CREAM", c(242, 228, 176), c(236, 220, 160), c(196, 30, 36)),
            Case("RED ON BUSY GREY", c(225, 225, 222), c(140, 140, 145), c(210, 34, 40)),
            Case("RED ON BLACK", c(16, 16, 18), c(16, 16, 18), c(232, 40, 44)),
            Case("RED ON DARK BUSY", c(70, 40, 30), c(20, 30, 50), c(232, 44, 44)),
            Case("PINK ON WHITE", c(240, 240, 238), c(240, 240, 238), c(228, 84, 150)),
            Case("WHITE ON BUSY MID", c(200, 84, 40), c(56, 92, 140), null),
            Case("WHITE ON YELLOW", c(242, 204, 40), c(236, 196, 36), null),
            Case("WHITE ON BLACK", c(12, 12, 14), c(12, 12, 14), null),
            Case("YELLOW ON NAVY", c(22, 32, 84), c(22, 32, 84), c(240, 212, 40)),
            Case("INK ON WHITE COVER", c(236, 236, 236), c(236, 236, 236), null),
            Case("INK ON BUSY LIGHT", c(232, 230, 220), c(150, 170, 190), c(30, 30, 40)),
            Case("ORANGE ON BLUE", c(30, 60, 170), c(30, 60, 170), c(245, 140, 30)),
            Case("GOLD ON BROWN", c(92, 60, 40), c(70, 46, 30), c(206, 164, 60)),
        )
        val w = 64; val h = 900; val styles = Printing.TitleShadow.entries
        val col = w + 4; val group = styles.size * col + 28
        val sheet = android.graphics.Bitmap.createBitmap(cases.size * group, h + 40, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(sheet).apply { drawColor(android.graphics.Color.rgb(60, 60, 64)) }
        val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 16f; textAlign = android.graphics.Paint.Align.CENTER }
        cases.forEachIndexed { i, k ->
            val movie = Movie("sheet-$i", k.name, 2001, "", 100, "PG-13", k.a, thumb = "x", resolution = "1080")
            val palette = IntArray(18) { j -> if (j % 2 == 0) k.a else k.b }
            styles.forEachIndexed { si, style ->
                val x = i * group + si * col.toFloat()
                canvas.drawBitmap(Printing.spine(movie, palette, k.title, style), x, 0f, null)
                canvas.drawText(style.name, x + w / 2, h + 26f, label)
            }
        }
        // "Previously Viewed" stickers on watched test films, short to very long titles: spine and back
        // bitmaps per film in files/stickers/ (laid out unrolled afterwards to check the wrap lines up).
        val stickerDir = java.io.File(getExternalFilesDir(null), "stickers").apply { deleteRecursively(); mkdirs() }
        listOf("Fog", "The Long Summer at Willow Creek", "A Very Long Title About a Very Small Town", "Stage Live: A Play in Two Parts, Part Two: The Second Half of It", "Agent Nine - The Last Assignment of All").forEachIndexed { i, t ->
            val poster = android.graphics.Bitmap.createBitmap(300, 450, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(poster).apply {
                drawPaint(android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 0f, 450f, c(40 + i * 30, 70, 120), c(200, 120 + i * 20, 60), android.graphics.Shader.TileMode.CLAMP) })
                drawText(t.uppercase(), 150f, 60f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 22f; textAlign = android.graphics.Paint.Align.CENTER })
            }
            val movie = Movie("sticker-$i", t, 2001, "A test synopsis for the back of the case, long enough to fill a few lines of text the way a real film's summary would.", 100, "PG-13", c(80, 80, 120), thumb = "x", resolution = "1080", watched = true)
            val palette = Printing.palette(poster)
            Printing.palettes[movie.id] = palette
            fun save(b: android.graphics.Bitmap, n: String) = java.io.File(stickerDir, "$i-$n.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            save(Printing.spine(movie, palette, null), "spine")
            save(Printing.back(movie, poster, 1), "back")
            save(Printing.front(poster, 512), "front")
        }

        // Covers with a big black shape: drawn as posters, read by Printing like real ones.
        data class PosterCase(val name: String, val bg: Int, val blackShare: Float, val patch: Int, val patchShare: Float)
        val posters = listOf(
            PosterCase("PALE, BIG BLACK TREE", c(232, 226, 210), 0.3f, c(200, 40, 40), 0.06f),   // black wins over a little red
            PosterCase("PALE, BIG RED, SMALL TREE", c(232, 226, 210), 0.16f, c(200, 40, 40), 0.3f), // red is the bigger feature
            PosterCase("DARK, BLACK SKY", c(60, 70, 90), 0.4f, c(230, 150, 40), 0.06f),           // spine too dark for black
            PosterCase("PALE BLUE, BLACK TREE", c(150, 190, 225), 0.3f, c(40, 90, 200), 0.05f),
        )
        val posterSheet = android.graphics.Bitmap.createBitmap(posters.size * (2 * col + 28), h + 40, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(posterSheet).apply {
            drawColor(android.graphics.Color.rgb(60, 60, 64))
            posters.forEachIndexed { i, p ->
                val poster = android.graphics.Bitmap.createBitmap(200, 300, android.graphics.Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(poster).apply {
                    drawColor(p.bg)
                    val ink = android.graphics.Paint().apply { color = c(14, 14, 16) }
                    drawRect(0f, 300f * (1 - p.blackShare), 200f, 300f, ink)                  // the black shape
                    drawRect(20f, 20f, 180f, 20f + 300f * p.patchShare * 200f / 160f, android.graphics.Paint().apply { color = p.patch })
                }
                val movie = Movie("poster-$i", p.name, 2001, "", 100, "PG-13", p.bg, thumb = "x", resolution = "sd")
                Printing.palettes[movie.id] = Printing.palette(poster)
                Printing.accents[movie.id] = Printing.accents(poster)
                val x = i * (2 * col + 28f)
                drawBitmap(poster, null, android.graphics.RectF(x, 300f, x + w, 300f + w * 1.5f), null)
                drawBitmap(Printing.spine(movie, Printing.palettes[movie.id], null), x + col, 0f, null)
            }
        }
        java.io.File(getExternalFilesDir(null), "poster-sheet.png").outputStream().use { posterSheet.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val out = java.io.File(getExternalFilesDir(null), "spine-sheet.png")
        out.outputStream().use { sheet.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        android.util.Log.i("ShelfLifeSpine", "sheet written: ${out.path}")
        // And every spine printed so far (the shelf's real films), for a look at the whole shelf.
        val printed = java.io.File(getExternalFilesDir(null), "spines").apply { deleteRecursively(); mkdirs() }
        // The cache also holds older styles' spines: the newest shelf-full is the current one.
        java.io.File(cacheDir, "spines").listFiles { f -> f.name.endsWith(".png") }?.sortedByDescending { it.lastModified() }?.take(144)?.forEach { it.copyTo(java.io.File(printed, it.name)) }
    }
    private class Phase(val name: String, val keys: List<Int>, val spacing: Long, val settle: Long)
    private val SHUFFLE = -1  // the bench's stand-in for holding OK
    private fun press(code: Int) {
        if (code == SHUFFLE) { shuffle(); return }
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    /**
     * Behind the loading curtain, once the room has drawn: the detailed case with the blur, then the
     * disc in both passes, so every shader those need is compiled before the first pick-up (on the
     * Mali GPU a first-use compile stalled that pick-up for a good fraction of a second).
     */
    private val warmedUp = mutableStateOf(false)  // state: the loading logo starts its fall once the room has warmed up
    private val filmStop = mutableStateOf(false)  // the library has loaded: the logo's film runs down to a stop
    private var logoSettled = false
    private fun warmUp() {
        if (room.framesDrawn < 2) { main.postDelayed({ warmUp() }, 50); return }
        val shelf = room.shelf; val deck = room.deck
        room.warming = true
        deck.inHand(true); deck.showDisc(shelf.inFrontOfCamera(0.06f, 0f, -0.26f, 1f, Pose.yaw(0.4f)))
        main.postDelayed({ deck.inHand(false); deck.showDisc(shelf.inFrontOfCamera(0.1f, 0f, -0.7f, 1f, Pose.yaw(0.4f))); room.requestRender() }, 350)
        main.postDelayed({ deck.hideDisc(); room.warming = false }, 700)
        main.postDelayed({ warmedUp.value = true }, 1_200)  // the blur has faded back out
    }

    /**
     * Printed spines are kept on disk, so later launches only download films they haven't seen.
     * The key changes whenever anything printed on the spine (or the printing code) changes.
     */
    /** The marks fetched by [Extras] (logos for the spines, case backs and the screensaver); again once a download lands. */
    private var studioLogos = emptyMap<Bumpers.Clip, android.graphics.Bitmap>()
    private fun loadExtras() {
        Printing.bluRayLogo = Extras.bitmap(Extras.BLURAY_LOGO)
        studioLogos = mapOf(Bumpers.PARAMOUNT to "paramount", Bumpers.FOX to "fox", Bumpers.DISNEY to "disney", Bumpers.WARNER to "warner", Bumpers.SONY to "sony", Bumpers.UNIVERSAL to "universal")
            .mapNotNull { (k, v) -> Extras.bitmap(Extras.studioLogo(v))?.let { k to it } }.toMap()
        if (::screensaver.isInitialized) screensaver.setLogo(Extras.bitmap(Extras.DVD_VIDEO_LOGO))
    }

    private fun spineCache(movie: Movie): java.io.File {
        val key = listOf(SPINE_STYLE, Printing.bluRayLogo != null, movie.id, movie.title, movie.year, movie.rating, movie.resolution, movie.thumb, movie.logo, movie.versions.firstOrNull()?.video, movie.watched).joinToString("|").hashCode()
        return java.io.File(cacheDir, "spines/${movie.id}-${Integer.toHexString(key)}.png")
    }

    private val spineQueue = ArrayDeque<Pair<String, Printing.Mips>>()
    private var draining = false
    private fun drainSpines() {
        if (draining) return
        draining = true
        android.view.Choreographer.getInstance().postFrameCallback(object : android.view.Choreographer.FrameCallback {
            override fun doFrame(t: Long) {
                // A time budget per frame, so the slower TV box takes fewer per frame; more behind the loading
                // curtain or while a restock's spines upload (the shelf stands still until they're done).
                // Otherwise also at most two a frame: each upload's real cost lands on the GPU driver after
                // this budget is spent.
                val still = loading.value != null || room.shelf.restocking
                val budget = if (still) 8_000_000L else 3_000_000L
                val most = if (still) Int.MAX_VALUE else 2
                val start = System.nanoTime()
                var n = 0
                timed("spines") { do { val (id, bmp) = spineQueue.removeFirstOrNull() ?: break; room.shelf.setSpine(id, bmp) } while (++n < most && System.nanoTime() - start < budget) }
                if (spineQueue.isEmpty()) {
                    draining = false
                    // A restock's cases move once the GPU has finished the uploads (they froze the animation).
                    val run = stockRun
                    if (spinesPrinted >= spinesExpected && room.shelf.restocking) room.afterGpu(1500, afterNextFrame = true) { if (run == stockRun) room.shelf.flushRestock() }
                    checkShelfReady()
                } else android.view.Choreographer.getInstance().postFrameCallback(this)
            }
        })
    }

    private fun showStatus(text: String, sticky: Boolean = false) {
        status = text
        refresh()
        if (!sticky) main.postDelayed({ if (status == text) { status = null; refresh() } }, 5_000)
    }

    // --- The disc ---

    /**
     * The disc slides out of the case, the view glides down to the player as its tray opens,
     * the disc settles on the tray, the tray closes, and the TV fills the screen with the disc menu.
     */
    /**
     * The case opens, the disc lifts out and turns in the light for a moment, then flies off to the
     * right; the view turns to the player and the disc drops in from above onto the tray.
     */
    private fun insertDisc() {
        val shelf = room.shelf
        val movie = shelf.held ?: return
        cardShown = false
        val deck = room.deck
        inserting = true
        if (labelFor != movie.id) { deck.printLabel(discLabel(movie, discLogo?.takeIf { it.first == movie.id }?.second)); labelFor = movie.id }
        loadDisc(movie)
        refresh()
        val t0 = if (shelf.showingBack) { shelf.turn(1); 600L } else 0L  // front toward you first
        fun at(ms: Long, block: () -> Unit) = main.postDelayed(block, t0 + ms)
        // The lid opens, the disc lifts out and turns in the light, flies off right and the lid closes: the
        // renderer runs it from InsertTimeline (as the baked clip, when there is one).
        at(0) { sfx("sfxCaseOpen", clackId, 1.0f); deck.inHand(true); room.startInsert() }
        at((InsertTimeline.SECONDS * 1000).toLong()) {
            room.endInsert()
            shelf.putBack()
            deck.inHand(false)
            deck.showDisc(Deck.hover.copy(y = 1.7f))  // waiting out of sight above the player
            room.go(Viewpoint.DECK, 1.5f)
        }
        at(3550) { deck.moveTray(Deck.trayOpen, 0.5f); sfx("sfxDeck", trayOpenId, 1f) }
        at(4400) { deck.moveDisc(Deck.hover, 0.6f) }   // drops in from above
        at(5000) { deck.moveDisc(Deck.onTray, 0.3f) }
        at(5270) { sfx("sfxDeck", discDropId, 1f) }  // its rattle 30 ms in, as the disc meets the tray (5.0 s + 0.3)
        at(5350) { sfx("sfxDeck", trayCloseId, 1f) }
        at(5950) { sfx("sfxDeck", discSpinId, 1f) }  // tray shut: it spins up
        at(5350) { deck.moveTray(Deck.trayClosed, 0.6f); deck.moveDisc(Deck.inside, 0.6f) }
        // Step back a little, square on to the TV; the menu comes up on its screen, then ease in.
        at(6050) { deck.hideDisc(); room.go(Viewpoint.TV_NEAR, 1.2f) }
        // The disc menu draws on the TV's own screen, so it can come up while the camera is still easing in; the
        // QuickPlay card is a full-screen overlay, so it waits until the TV fills the frame.
        val quick = quickPlayOn && server != null
        at(7300) { inserting = false; if (!quick) showMenu(MenuPage.MAIN) }
        at(7900) { if (discMovie != null) room.go(Viewpoint.TV, 1.8f, gentle = true) }
        at(9750) { if (quick && discMovie != null && menuState.value == null && warningCard.value == null) showQuickPlay() }
    }

    /**
     * `am start -n com.shelflife.tv/.MainActivity --ez bakeInsert true`: takes a case off the shelf and renders
     * the disc coming out to <external files>/insert-bake, for work/make_insert_clip.py.
     */
    private fun bakeInsertClip() {
        if (room.shelf.held == null) {
            if (fetchingCover == null) { room.jumpTo(Viewpoint.SHELF, 3); takeOut() }
            main.postDelayed({ bakeInsertClip() }, 500); return
        }
        main.postDelayed({ room.bakeInsert(java.io.File(getExternalFilesDir(null), "insert-bake")) { android.util.Log.i("ShelfLifePerf", "insert baked to $it") } }, 2_000)
    }

    /**
     * The disc's label (a 1024² drawing) is printed off the main thread while you look at the case
     * and uploaded while nothing moves, so OK starts the disc at once.
     */
    private var labelFor: String? = null
    private fun prepareLabel(movie: Movie) {
        val logo = discLogo?.takeIf { it.first == movie.id }?.second
        network.execute {
            val label = discLabel(movie, logo)
            main.post {
                if (room.shelf.held?.id != movie.id || inserting) return@post
                room.deck.printLabel(label); labelFor = movie.id; room.flush()
            }
        }
    }
    private fun discLabel(movie: Movie, logo: android.graphics.Bitmap?): android.graphics.Bitmap {
        // The logo's lettering as the one ink prints it (a logo on a plaque loses its plaque); with no
        // clean shape, the title is printed instead.
        val ink = logo?.let { Printing.inkMask(it) }
        // One flat colour from the spine's title; a white title prints black on the silver disc.
        val fill = ink?.let { Printing.titleColorFor(movie, logo) }?.let { c ->
            val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }
            if (hsv[1] < 0.18f && hsv[2] > 0.8f) android.graphics.Color.rgb(12, 12, 14) else c
        }
        return Printing.disc(movie, ink, fill)
    }

    /** Fetches the menu's art and special features, and readies the film (paused) so its tracks are known. */
    private fun loadDisc(movie: Movie, version: Int = 0) {
        discMovie = movie
        discInfo = null
        scenes = null
        mainPosition = 0
        versionChoice = version.coerceIn(0, maxOf(0, movie.versions.size - 1))
        menuState.value = null
        val server = server ?: return
        val key = playKey; val index = playIndex
        network.execute {
            val info = runCatching { plex.discInfo(server, key, index) }.getOrNull()
            val poster = movie.thumb?.let { plex.image(server, it, 600, 900) }
            // The large backdrop from the film's Plex page. When Plex has none (or hands back the poster,
            // which comes out portrait), use the poster heavily blurred instead of stretching it.
            val backdrop = info?.art?.takeIf { it != movie.thumb }?.let { plex.image(server, it, 1920, 1080) }?.takeIf { it.width > it.height * 1.25f }
            val art = backdrop ?: poster?.let { Printing.blurredBackdrop(it) }
            main.post {
                if (discMovie?.id != movie.id) return@post
                discInfo = info
                audioChoice = info?.audio?.indexOfFirst { it.isDefault }?.coerceAtLeast(0) ?: 0
                subtitleChoice = info?.subtitles?.indexOfFirst { it.isDefault } ?: -1
                checkedDefaultAudio = false
                // Scene Selection only when Plex knows the film's chapters (two or more).
                scenes = info?.chapters?.takeIf { it.size >= 2 }?.map { SceneTile(it.title, it.startMs) }
                android.util.Log.i("ShelfLifeDisc", "${movie.title}: ${info?.chapters?.size ?: 0} chapters")  // adb logcat -s ShelfLifeDisc
                menuState.value = menuState.value?.copy(art = art?.asImageBitmap(), poster = poster?.asImageBitmap(), extras = info?.extras?.map { it.title } ?: emptyList(), scenes = scenes)
                info?.chapters?.takeIf { it.size >= 2 }?.forEachIndexed { i, ch ->
                    val thumb = ch.thumb ?: return@forEachIndexed
                    network.execute {
                        val image = plex.image(server, thumb, 320, 180)?.let { Printing.trimBars(it) }?.asImageBitmap() ?: return@execute
                        main.post {
                            if (discMovie?.id != movie.id) return@post
                            scenes = scenes?.mapIndexed { k, t -> if (k == i) t.copy(thumb = image) else t }
                            menuState.value = menuState.value?.copy(scenes = scenes)
                        }
                    }
                }
                pendingArt = Triple(art, poster, info)
                // Resume where Plex says you left off (ignored in the first 5 s or the last 5%).
                val saved = info?.viewOffset ?: 0L
                val total = info?.durationMs ?: 0L
                mainPosition = if (saved > 5_000 && (total == 0L || saved < total * 0.95)) saved else 0L
                menuState.value = menuState.value?.copy(resume = mainPosition > 0)
                info?.partKey?.let { part -> disc.load(plex.directUrl(server, part), hls = false, startMs = mainPosition, play = false, extra = false, fallback = { at -> plex.transcodeUrl(server, key, at, mediaIndex = index) }) }
            }
        }
    }

    // --- Telling Plex what you watched ---

    /** Reports the main feature's position to Plex (extras aren't tracked). */
    private fun report(state: String, position: Long = disc.position) {
        val server = server ?: return
        if (bench) return
        if (discMovie == null || !disc.loaded || disc.extra) return
        val key = playKey
        val duration = disc.duration.takeIf { it > 0 } ?: discInfo?.durationMs ?: 0L
        network.execute { val ok = plex.timeline(server, key, state, position, duration); android.util.Log.i("ShelfLifePlex", "timeline $state ${position / 1000}s of ${duration / 1000}s -> ${if (ok) "saved" else "failed"}") }
    }

    /** Every 10 s while watching, so Plex's Resume point and "in progress" stay current. */
    private val progress = object : Runnable {
        override fun run() {
            if (menuState.value == null && playerView.visibility == View.VISIBLE) report(if (disc.playing) "playing" else "paused")
            main.postDelayed(this, 10_000)
        }
    }

    /** Ends this app's transcode sessions on the server (eject, version change, new track choice). */
    private fun endTranscodes() {
        val server = server ?: return
        val ids = plex.takeSessions()
        if (ids.isNotEmpty()) network.execute { plex.stopTranscodes(server, ids) }
    }
    private var scenes: List<SceneTile>? = null
    private var pendingArt: Triple<android.graphics.Bitmap?, android.graphics.Bitmap?, Plex.DiscInfo?>? = null

    private fun showMenu(page: MenuPage) {
        val movie = discMovie ?: return
        trailerMode = false; trailerQueue.clear(); quickPlayed = false
        if (disc.loaded) {
            val wasWatching = playerView.visibility == View.VISIBLE
            if (!disc.extra) mainPosition = disc.position
            disc.pause()
            if (wasWatching) report("paused")
        }
        osdState.value = null
        closeControls()
        room.resume()
        val (art, poster, info) = pendingArt ?: Triple(null, null, null)
        menuState.value = (menuState.value ?: DiscMenuState(movie, art = art?.asImageBitmap(), poster = poster?.asImageBitmap(), extras = if (server == null) emptyList() else info?.extras?.map { it.title }))
            .copy(page = page, resume = mainPosition > 5_000, version = movie.versions.takeIf { it.size > 1 }?.getOrNull(versionChoice)?.label, scenes = scenes)
        updateTrackLabels()
        refresh()
        if (menuMusicOn && server != null) network.execute {
            val url = menuMusic.lookup(movie) ?: return@execute
            main.post { if (discMovie?.id == movie.id && menuState.value != null) menuMusic.play(movie.id, url) }
        }
    }

    private fun watch() {
        menuMusic.stop()
        menuState.value = null
        playerView.visibility = View.VISIBLE
        room.pause()  // the room is hidden behind the film; let the GPU rest
        refresh()
    }

    /** Plays the feature from [mainPosition]; [intro]: from the very start, the warnings and feature presentation first. */
    private fun playFeature(intro: Boolean) {
            val server = server
            val part = discInfo?.partKey
            val movie = discMovie
            when {
                server == null -> showStatus("This is a sample disc. Connect Plex in Settings (▲ in the Room view) to watch your films.")
                part == null || movie == null -> showStatus("Still reading the disc…")
                else -> {
                    val start = {
                        if (disc.extra || !disc.loaded) checkedDefaultAudio = false
                        val key = playKey; val index = playIndex
                        if (disc.extra || !disc.loaded) disc.load(plex.directUrl(server, part), hls = false, startMs = mainPosition, play = true, extra = false, fallback = { at -> plex.transcodeUrl(server, key, at, mediaIndex = index) })
                        else disc.play()
                        watch()
                        if (streaming) showControls() else discEvents.onOsd("▶  Play", false)
                    }
                    // From the very beginning, the disc plays its warning first; Resume goes straight in.
                    val fromStart = mainPosition < 2_000 && (disc.extra || !disc.loaded || disc.position < 2_000)
                    // From the very start: warnings, then the release's feature presentation, then the film.
                    val bumper = Bumpers.forMovie(movie, this)
                    val feature = { if (bumper != null && prefs.getBoolean("featurePresentation", true)) showFeature(bumper, start) else start() }
                    when {
                        !fromStart || !intro -> start()
                        fbiOn -> showWarning(feature)
                        else -> feature()
                    }
                }
            }
    }

    private val menuActions: DiscMenuActions = DiscMenuActions(
        play = { playFeature(intro = true) },
        open = { page -> menuState.value = menuState.value?.copy(page = page); updateTrackLabels() },
        playScene = { i ->
            // Straight to that chapter: no warnings, like a DVD's scene menu.
            val start = scenes?.getOrNull(i)?.startMs
            if (start != null) {
                mainPosition = start
                if (disc.loaded && !disc.extra) disc.seekTo(start)
                menuState.value = menuState.value?.copy(page = MenuPage.MAIN)
                playFeature(intro = false)
            }
        },
        startOver = {
            mainPosition = 0
            if (disc.loaded && !disc.extra) disc.seekTo(0)
            menuState.value = menuState.value?.copy(resume = false)
            menuActions.play()
        },
        playExtra = { i ->
            val server = server
            val part = discInfo?.extras?.getOrNull(i)?.partKey
            if (server == null || part == null) showStatus("That special feature isn't available from Plex.")
            else {
                if (!disc.extra) mainPosition = disc.position
                extraTitle = discInfo?.extras?.getOrNull(i)?.title
                disc.load(plex.directUrl(server, part), hls = false, startMs = 0, play = true, extra = true, fallback = null)
                watch()
                if (streaming) showControls()
            }
        },
        cycleAudio = {
            val tracks = discInfo?.audio.orEmpty()
            if (tracks.size < 2) disc.cycleAudio() else { audioChoice = (audioChoice + 1) % tracks.size; applyTracks() }
        },
        cycleVersion = {
            val movie = discMovie
            val server = server
            val count = movie?.versions?.size ?: 0
            if (movie != null && server != null && count > 1) {
                versionChoice = (versionChoice + 1) % count
                // A different cut is a different file: stop the old one and read the new one's tracks.
                report("stopped"); endTranscodes()
                disc.stop(); mainPosition = 0
                menuState.value = menuState.value?.copy(version = version?.label, resume = false)
                val key = playKey; val index = playIndex; val choice = versionChoice
                network.execute {
                    val info = runCatching { plex.discInfo(server, key, index) }.getOrNull()
                    main.post {
                        if (discMovie?.id != movie.id || versionChoice != choice || info == null) return@post
                        discInfo = info
                        audioChoice = info.audio.indexOfFirst { it.isDefault }.coerceAtLeast(0)
                        subtitleChoice = info.subtitles.indexOfFirst { it.isDefault }
                        checkedDefaultAudio = false
                        info.partKey?.let { part -> disc.load(plex.directUrl(server, part), hls = false, startMs = 0, play = false, extra = false, fallback = { at -> plex.transcodeUrl(server, key, at, mediaIndex = index) }) }
                        updateTrackLabels()
                    }
                }
            }
        },
        cycleSubtitles = {
            val tracks = discInfo?.subtitles.orEmpty()
            if (tracks.isEmpty()) disc.cycleSubtitles() else { subtitleChoice = if (subtitleChoice + 1 >= tracks.size) -1 else subtitleChoice + 1; applyTracks() }
        },
    )

    /**
     * Uses the file's own tracks when this box can decode them; otherwise asks Plex to convert the
     * audio (and burn in subtitles) so the chosen track is what you hear, never a fallback language.
     */
    private fun applyTracks() {
        val info = discInfo ?: return
        val server = server ?: return
        val movie = discMovie ?: return
        val wantSubs = subtitleChoice >= 0
        val audioOk = !disc.converted && disc.groups(androidx.media3.common.C.TRACK_TYPE_AUDIO, true).size == info.audio.size &&
            disc.selectTrack(androidx.media3.common.C.TRACK_TYPE_AUDIO, audioChoice)
        val subsOk = !disc.converted && (if (!wantSubs) { disc.subtitlesOff(); true } else
            disc.groups(androidx.media3.common.C.TRACK_TYPE_TEXT, true).size == info.subtitles.size && disc.selectTrack(androidx.media3.common.C.TRACK_TYPE_TEXT, subtitleChoice))
        updateTrackLabels()
        if (audioOk && subsOk) return
        val partId = info.partId ?: return
        val audioId = info.audio.getOrNull(audioChoice)?.id
        val subtitleId = info.subtitles.getOrNull(subtitleChoice)?.id
        val at = disc.position
        val playing = disc.playing
        network.execute {
            runCatching { plex.selectTracks(server, partId, audioId, subtitleId) }
            main.post {
                if (discMovie?.id != movie.id) return@post
                endTranscodes()
                disc.load(plex.transcodeUrl(server, playKey, at / 1000, convertAudio = !audioOk || disc.converted, burnSubtitles = wantSubs, mediaIndex = playIndex), hls = true, startMs = 0, play = playing, extra = false, fallback = null)
            }
        }
    }

    private fun updateTrackLabels() {
        val info = discInfo
        menuState.value = menuState.value?.copy(
            audio = info?.audio?.getOrNull(audioChoice)?.name ?: disc.audioLabel(),
            subtitles = if (info?.subtitles.isNullOrEmpty()) disc.subtitleLabel() else info!!.subtitles.getOrNull(subtitleChoice)?.name ?: "Off",
        )
    }

    private val discEvents = object : DiscPlayer.Events {
        override fun onEnded(extra: Boolean) {
            playerView.visibility = View.GONE
            if (trailerMode) { if (trailerQueue.isEmpty()) endTrailers() else nextTrailer(); return }
            if (extra) { disc.stop(); showMenu(MenuPage.EXTRAS); return }
            // Finished: Plex marks it watched and clears the Resume point.
            val server = server
            val key = playKey
            val length = disc.duration
            if (server != null) network.execute { plex.timeline(server, key, "stopped", length, length); plex.scrobble(server, key) }
            endTranscodes()
            mainPosition = 0
            disc.seekTo(0)
            // QuickPlay promised "a selection of bonus features": the film's own Plex extras run, then the menu.
            val bonus = discInfo?.extras?.filter { it.partKey != null }.orEmpty()
            if (quickPlayed && bonus.isNotEmpty()) { quickPlayed = false; trailers = bonus; trailerQueue.clear(); trailerQueue.addAll(bonus); nextTrailer(); return }
            quickPlayed = false
            showMenu(MenuPage.MAIN)
        }
        override fun onFailed(message: String) {
            playerView.visibility = View.GONE
            if (trailerMode) { if (trailerQueue.isEmpty()) endTrailers() else nextTrailer(); return }
            showStatus("Couldn't play this: $message")
            showMenu(if (disc.extra) MenuPage.EXTRAS else MenuPage.MAIN)
        }
        override fun onTracksChanged() {
            val info = discInfo
            // First look at the file: if its default audio can't be decoded here, convert it rather than
            // letting the player fall back to some other language.
            if (!checkedDefaultAudio && info != null && !disc.extra && !disc.converted) {
                val groups = disc.groups(androidx.media3.common.C.TRACK_TYPE_AUDIO, true)
                if (groups.isNotEmpty()) {
                    checkedDefaultAudio = true
                    if (groups.size == info.audio.size && !groups[audioChoice].isSupported) applyTracks()
                }
            }
            updateTrackLabels()
        }
        override fun onOsd(text: String?, sticky: Boolean) {
            osdState.value = text
            if (!sticky) main.postDelayed({ if (osdState.value == text) osdState.value = null }, 2_000)
        }
    }

    private fun eject() {
        menuMusic.stop()
        report("stopped")   // Plex keeps the spot for Resume (or marks it watched if you were near the end)
        endTranscodes()
        disc.stop()
        playerView.visibility = View.GONE
        menuState.value = null
        osdState.value = null
        closeControls()
        discMovie = null
        discInfo = null
        pendingArt = null
        room.resume()
        room.deck.moveTray(Deck.trayOpen, 0.4f)
        main.postDelayed({ room.deck.moveTray(Deck.trayClosed, 0.4f) }, 900)
        zoom(Viewpoint.ROOM)
        refresh()
    }

    // --- Search (▲ past the top shelf) ---
    //
    // The camera rises above the top shelf and a search bar appears there, as wide as the shelf. OK opens the
    // TV's keyboard into a hidden text field; matching films show below the bar as DVD cases, five to a row,
    // over the room blurred the way it is behind a case in your hands. OK on one puts it in the highlighted
    // slot and takes it out.

    private val search = mutableStateOf<SearchState?>(null)
    private val searchCovers = androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.graphics.ImageBitmap>()
    private val coversAsked = HashSet<String>()
    private var library: List<Movie>? = null   // every film, fetched the first time search opens
    private var libraryFrom: Plex.Server? = null
    private val searchField by lazy {
        object : android.widget.EditText(this) {
            // BACK with the keyboard up closes the keyboard; the field keeps focus, so it's noticed here.
            override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) main.post { stopTyping() }
                return super.onKeyPreIme(keyCode, event)
            }
        }.apply {
            alpha = 0f; isFocusable = false; isFocusableInTouchMode = false; isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
            setOnEditorActionListener { _, _, _ -> stopTyping(); true }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { if (search.value != null) setQuery(s?.toString() ?: "") }
            })
        }
    }
    private val keyboard get() = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager

    private fun openSearch() {
        searchField.setText("")
        search.value = SearchState("", emptyList(), focus = -1, editing = false, loading = false)
        room.liftForSearch(true)
        loadLibrary()
        sfx("sfxFlick", flickId, 0.7f)
    }

    private fun closeSearch() {
        if (search.value?.editing == true) stopTyping()
        val blurred = room.searching
        search.value = null
        room.searching = false
        // The blur eases out first: moving the camera drops it at once.
        main.postDelayed({ if (search.value == null && room.shelf.held == null) room.liftForSearch(false) }, if (blurred) 450 else 0)
        refresh()
    }

    /** Every film in the library (the shelf holds a random 144), once per server; the shelf's own without Plex. */
    private fun loadLibrary() {
        val server = server
        if (server == null) { library = room.shelf.movies(); return }
        if (library != null && libraryFrom === server) return
        search.value = search.value?.copy(loading = true)
        network.execute {
            val all = runCatching { plex.shelf(server, maxOf(libraryCount, 2000)).first }.getOrNull()
            main.post {
                library = all ?: room.shelf.movies(); libraryFrom = server
                search.value?.let { search.value = it.copy(loading = false); setQuery(it.query) }
            }
        }
    }

    private fun setQuery(query: String) {
        val s = search.value ?: return
        fun norm(x: String) = java.text.Normalizer.normalize(x, java.text.Normalizer.Form.NFD).lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim()
        val q = norm(query)
        // Titles starting with it first ("the" aside), then a word starting with it, then anywhere in the title.
        val results = if (q.isEmpty()) emptyList() else (library ?: emptyList()).mapNotNull { m ->
            val t = norm(m.title)
            when {
                t.startsWith(q) || t.removePrefix("the ").startsWith(q) -> 0
                t.split(' ').any { it.startsWith(q) } -> 1
                q in t -> 2
                else -> null
            }?.let { it to m }
        }.sortedWith(compareBy({ it.first }, { it.second.title })).map { it.second }.distinctBy { it.id }
        search.value = s.copy(query = query, results = results, focus = if (s.focus >= results.size) results.size - 1 else s.focus, first = 0)
        // Results blur the room behind them; none, and it's sharp again.
        if (results.isNotEmpty() && !room.searching) { room.searching = true; room.startFocusPull() }
        if (results.isEmpty()) room.searching = false
        loadCovers()
        refresh()
    }

    /** The posters of the results in view (and the next row), as small covers. */
    private fun loadCovers() {
        val s = search.value ?: return
        val server = server
        val from = s.first * SearchState.COLUMNS
        for (movie in s.results.drop(from).take((SearchState.ROWS + 1) * SearchState.COLUMNS)) {
            if (!coversAsked.add(movie.id)) continue
            network.execute {
                val thumb = movie.thumb
                val bitmap = if (server != null && thumb != null) plex.image(server, thumb, 270, 380) else Printing.front(movie)
                bitmap?.let { b -> main.post { searchCovers[movie.id] = b.asImageBitmap() } }
            }
        }
    }

    private fun startTyping() {
        search.value = search.value?.copy(editing = true, focus = -1)
        searchField.isFocusable = true; searchField.isFocusableInTouchMode = true
        searchField.requestFocus()
        searchField.setSelection(searchField.text.length)
        keyboard.showSoftInput(searchField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun stopTyping() {
        val s = search.value
        keyboard.hideSoftInputFromWindow(searchField.windowToken, 0)
        searchField.clearFocus(); searchField.isFocusable = false; searchField.isFocusableInTouchMode = false
        if (s != null && s.editing) search.value = s.copy(editing = false, focus = if (s.results.isNotEmpty()) 0 else -1)
        refresh()
    }

    private fun searchKey(keyCode: Int, ok: Boolean, back: Boolean) {
        val s = search.value ?: return
        if (s.editing) { if (back || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) stopTyping(); return }
        val n = s.results.size
        val cols = SearchState.COLUMNS
        val focus = when {
            s.focus < 0 -> when {
                back -> { closeSearch(); return }
                ok -> { startTyping(); return }
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> if (n > 0) 0 else { closeSearch(); return }
                else -> return
            }
            back -> -1
            ok -> { choose(s.results[s.focus]); return }
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> if (s.focus % cols > 0) s.focus - 1 else return
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> if (s.focus % cols < cols - 1 && s.focus + 1 < n) s.focus + 1 else return
            keyCode == KeyEvent.KEYCODE_DPAD_UP -> if (s.focus >= cols) s.focus - cols else -1
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> when {
                s.focus + cols < n -> s.focus + cols
                s.focus / cols < (n - 1) / cols -> n - 1   // a short last row: its last case
                else -> return
            }
            else -> return
        }
        // Scroll by whole rows so the highlighted case stays in view.
        val row = maxOf(0, focus) / cols
        val first = if (focus < 0) 0 else s.first.coerceIn(row - SearchState.ROWS + 1, row)
        search.value = s.copy(focus = focus, first = first)
        sfx("sfxFlick", flickId, 0.7f, 0.92f + 0.16f * Math.random().toFloat())
        loadCovers()
    }

    /** The picked film goes in the highlighted slot of the top shelf and comes out into your hands. */
    private fun choose(movie: Movie) {
        search.value = null
        val printed = room.shelf.replaceFocused(movie)
        val server = server
        if (!printed && server != null) {
            val logos = library?.any { it.logo != null } ?: false
            network.execute {
                val mips = prepareSpine(server, movie, logos, java.util.concurrent.atomic.AtomicLong(), java.util.concurrent.atomic.AtomicLong())
                if (mips != null) main.post { spineQueue.addLast(movie.id to mips); drainSpines() }
            }
        }
        takeOut(fromSearch = true)
    }

    // --- Overlay ---

    private fun refresh() {
        val shelf = room.shelf
        val held = shelf.held
        val base = when {
            loading.value != null -> OverlayState(keys = emptyList())
            inserting || menuState.value != null || discMovie != null || warningCard.value != null -> OverlayState(keys = emptyList())
            signIn != null -> OverlayState(keys = listOf("BACK" to "Use the sample films"))
            settingsFocus != null -> settingsRows().let { rows ->
                settingsFocus = settingsFocus!!.coerceAtMost(rows.size - 1)  // the Plex row goes once connected
                OverlayState(keys = emptyList(), settings = SettingsPanel(rows.map { it.first }, settingsFocus!!))
            }
            held != null -> OverlayState(keys = emptyList(), action = "Play Movie", centerHints = true, held = if (cardLeaving || !cardShown) null else held, scroll = synopsisScroll,   // ◀ ▶ still turn the case; no legend for it
                cardColor = Printing.palettes[held.id]?.let { Printing.mainColor(it) })
            search.value != null -> OverlayState(keys = emptyList())   // the highlighted title shows in the search bar
            // Browsing the shelf shows only the highlighted title; the D-pad legend returns once a case is in hand.
            room.viewpoint == Viewpoint.SHELF -> OverlayState(title = shelf.focused?.title, keys = emptyList())
            room.viewpoint == Viewpoint.TV -> OverlayState(keys = emptyList())   // the screensaver fills the screen; ▼ or Back leaves, no legend
            gearFocused -> OverlayState(keys = emptyList(), gear = true)   // the lit gear is its own hint
            else -> OverlayState(
                keys = emptyList(), centerHints = true,
                dpad = Dpad(up = if (tvAvailable) "TV" else null, left = "Settings", right = "Shelf", hold = if (server != null) "Shuffle" else null),
                gear = if (room.viewpoint == Viewpoint.ROOM) false else null,
            )
        }
        overlayState.value = base.copy(status = status, signIn = signIn, hintsShown = hintsShown)
    }

    private var cardLeaving = false  // BACK on a held case: the card fades out before the case moves
    private var cardShown = false    // the held case's details card, shown once the case has landed

    // The key hints step aside a few seconds after the last press and come back with the next one.
    private var hintsShown = true
    private val hideHints = Runnable { hintsShown = false; refresh() }
    private fun pokeHints(ms: Long = 4_000) {
        hintsShown = true
        main.removeCallbacks(hideHints)
        main.postDelayed(hideHints, ms)
    }
}
