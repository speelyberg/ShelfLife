package com.shelflife.tv

import android.content.Context
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.Camera
import com.google.android.filament.Colors
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.RenderTarget
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Rendering knobs in one place, so each can be switched per device: the bench takes them as an
 * intent extra (`--es quality "blur=false"`); a settings screen can store them later.
 */
data class Quality(
    val blur: Boolean = true,         // the room goes soft behind a case in your hands
    val smoothCase: Boolean = false,  // 4x MSAA while a case is in your hands (Onn: turning 54 → 22 fps)
    val smoothRoom: Boolean = false,  // 4x MSAA on the room (adds a full-screen resolve)
    val sheen: Int = 2,               // gloss on the held case's covers: 0 off, 1 low, 2 medium, 3 high
    val steady30: Boolean = false,    // draw every other vsync: an even 30 fps instead of 30-60 judder
    val showFps: Boolean = false,     // frame-rate readout in the corner
    val resolution: Int = 1080,       // the 3D drawn at 1080, 900 or 720 lines and scaled up by the display (UI stays sharp)
) {
    companion object {
        fun parse(spec: String?, base: Quality = Quality()): Quality {
            var q = base
            spec?.split(',')?.map { it.split('=').map(String::trim) }?.filter { it.size == 2 }?.forEach { (k, v) ->
                q = when (k) {
                    "blur" -> q.copy(blur = v.toBoolean())
                    "smoothCase" -> q.copy(smoothCase = v.toBoolean())
                    "smoothRoom" -> q.copy(smoothRoom = v.toBoolean())
                    "steady30" -> q.copy(steady30 = v.toBoolean())
                    "showFps" -> q.copy(showFps = v.toBoolean())
                    "sheen" -> q.copy(sheen = v.toInt().coerceIn(0, 3))
                    "resolution" -> q.copy(resolution = v.toInt().coerceIn(360, 1080))
                    else -> q
                }
            }
            return q
        }
    }
}

enum class Viewpoint { ROOM, SHELF, TV, DECK, TV_NEAR }

/**
 * Lit materials for what's in your hands (the disc, the open case), compiled for only what they use
 * (work/materials/hand_*.mat): gltfio's lit ubershader there held the Onn's Mali-G31 to ~12 fps.
 * Without the compiled .filamat in assets the ubershader stays.
 */
class HandMaterials(private val engine: Engine, context: Context) {
    private fun load(context: Context, name: String) = runCatching {
        val b = context.assets.open(name).use { it.readBytes() }
        com.google.android.filament.Material.Builder().payload(ByteBuffer.allocateDirect(b.size).put(b).flip(), b.size).build(engine)
    }.getOrNull()
    private val solid = load(context, "hand_solid.filamat")
    private val layer = load(context, "hand_layer.filamat")
    private val depth = load(context, "hand_depth.filamat")
    /** Fills depth only: the case's plastic while the baked insert clip draws it. */
    val depthOnly = depth?.createInstance()
    private val made = ArrayList<com.google.android.filament.MaterialInstance>()
    // A see-through layer shows nothing until its own texture is set.
    private val clear = Texture.Builder().width(1).height(1).levels(1).sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.RGBA8).build(engine)
        .also { it.setImage(engine, 0, Texture.PixelBufferDescriptor(ByteBuffer.allocateDirect(4), Texture.Format.RGBA, Texture.Type.UBYTE)) }

    /** Gives [entity] the cheap material with the glb's own values (copied here); returns the instance it draws with. */
    fun apply(entity: Int, rgba: FloatArray, metallic: Float, roughness: Float, layered: Boolean = false): com.google.android.filament.MaterialInstance? {
        val rm = engine.renderableManager
        if (entity == 0 || !rm.hasComponent(entity)) return null
        val ri = rm.getInstance(entity)
        val m = (if (layered) layer else solid)?.createInstance() ?: return rm.getMaterialInstanceAt(ri, 0)
        m.setParameter("baseColorFactor", rgba[0], rgba[1], rgba[2], rgba[3])
        if (layered) m.setParameter("baseColorMap", clear, TextureSampler())  // unlit: no metal or roughness
        else { m.setParameter("metallicFactor", metallic); m.setParameter("roughnessFactor", roughness) }
        rm.setMaterialInstanceAt(ri, 0, m)
        made += m
        return m
    }

    fun destroy() {
        made.forEach { engine.destroyMaterialInstance(it) }
        depthOnly?.let { engine.destroyMaterialInstance(it) }
        listOfNotNull(solid, layer, depth).forEach { engine.destroyMaterial(it) }
        engine.destroyTexture(clear)
    }
}

/** Filament scene for the living room. Coordinates match the Mac app (metres, Y up). */
class RoomRenderer(context: Context, private val surfaceView: SurfaceView, initialQuality: Quality = Quality()) : Choreographer.FrameCallback {
    init { Filament.init(); Gltfio.init() }

    private val engine = Engine.create()
    private val renderer: Renderer = engine.createRenderer()
    private val scene: Scene = engine.createScene()
    private val view: View = engine.createView()
    private val camera: Camera = engine.createCamera(EntityManager.get().create())
    private var width = 1920; private var height = 1080
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val displayHelper = DisplayHelper(context)
    private var swapChain: SwapChain? = null
    private val choreographer = Choreographer.getInstance()
    private val assetLoader = AssetLoader(engine, UbershaderProvider(engine), EntityManager.get())
    private val resourceLoader = ResourceLoader(engine)
    private val room: FilamentAsset
    val shelf: Shelf
    val deck: Deck
    // The baked insert clip (see "The disc coming out of the case" below).
    private class ClipFrame(val x: Int, val y: Int, val w: Int, val h: Int, val texture: Texture)
    private class ClipLayer(val frames: List<ClipFrame>, val index: IntArray, val priority: Int) {
        var quad: FilamentAsset? = null; var material: com.google.android.filament.MaterialInstance? = null; var shown = -1
    }
    private class Clip(val width: Int, val height: Int, val layers: List<ClipLayer>)
    private var clip: Clip? = null
    private var clipMat: com.google.android.filament.Material? = null
    private var clipWarm = 0
    private var insertStart = 0L
    private val hand: HandMaterials
    private val cameraMatrix = DoubleArray(16)
    private val roomGrey = doubleArrayOf(0.45, 0.45, 0.44, 1.0)  // the clear colour, seen through the open hallway
    private val camera32 = FloatArray(16)  // the same, as floats for the shelf and deck (reused: no garbage per frame)
    // The TV's glass carries a soft room reflection that fades as you get close (gone at full-screen TV view).
    private var screenGlass: com.google.android.filament.MaterialInstance? = null
    private var sheenTexture: com.google.android.filament.Texture? = null
    private var sheenLevel = -1f
    // Background blur while a case is held: [blurT] runs 0..1 in time, [blur] is it eased (smoothstep).
    private var blur = 0f
    private var blurT = 0f
    private var blurArmed = false   // the focus pull has been started (with the case's flight)
    private var blurUsed = false    // a pull has run since the last snapshot
    /** Takes effect at once, so it can be switched while the app runs. */
    var quality: Quality = initialQuality
        set(value) { field = value; applyQuality() }
    private var lastFrameNanos = 0L
    private val snapColor = Texture.Builder().width(SNAP_W).height(SNAP_H).levels(1).sampler(Texture.Sampler.SAMPLER_2D)
        .format(Texture.InternalFormat.RGBA8).usage(Texture.Usage.COLOR_ATTACHMENT or Texture.Usage.SAMPLEABLE or Texture.Usage.BLIT_SRC).build(engine)
    private val snapDepth = Texture.Builder().width(SNAP_W).height(SNAP_H).levels(1).sampler(Texture.Sampler.SAMPLER_2D)
        .format(Texture.InternalFormat.DEPTH24).usage(Texture.Usage.DEPTH_ATTACHMENT).build(engine)
    private val snapTarget = RenderTarget.Builder().texture(RenderTarget.AttachmentPoint.COLOR, snapColor)
        .texture(RenderTarget.AttachmentPoint.DEPTH, snapDepth).build(engine)
    private val snapView: View = engine.createView()
    private var snapState = SNAP_NONE
    private var snapRun = 0
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var backdrop: FilamentAsset
    private lateinit var backdropMaterial: com.google.android.filament.MaterialInstance
    private val levelTextures = arrayOfNulls<Texture>(BLUR_LEVELS.size)
    private lateinit var backdropMat: com.google.android.filament.Material
    private var warmFrame = false  // this frame draws the next blur image offscreen, to warm it up
    private var shownLevels = intArrayOf(-1)  // which blur image the quad shows
    private var hideForSnapshot: IntArray? = null  // entities left out of the next snapshot (the case about to be lifted)
    private val focusWaits = ArrayList<() -> Unit>()
    private var warmLevel = BLUR_LEVELS.size
    private lateinit var lightmap: Texture
    /** The settings' frame-rate readout: frames actually drawn over the last half second. */
    val fpsText = androidx.compose.runtime.mutableStateOf<String?>(null)
    private var fpsFrames = 0; private var fpsSince = 0L; private var fpsGpu = 0.0; private var fpsGpuN = 0
    private var oddVsync = false

    var viewpoint = Viewpoint.ROOM; private set
    var shelfIndex = 3; private set

    // Orbit camera: eased from `from` to `to` over `duration` seconds.
    private data class Orbit(val tx: Float, val ty: Float, val tz: Float, val yaw: Float, val pitch: Float, val distance: Float)
    private var from = orbit(Viewpoint.ROOM)
    private var to = from
    private var startNanos = 0L
    private var duration = 0.75f
    val screenScale = androidx.compose.runtime.mutableFloatStateOf(1f)
    private var gentle = false

    init {
        view.scene = scene
        view.camera = camera
        // Exposure for an evening room lit by a ceiling lamp and some window light.
        camera.setExposure(2.8f, 1f / 60f, 800f)
        // No post-processing: on the Onn's Mali GPU Filament's post chain alone cost ~15 ms a frame
        // (measured with an empty scene). Everything is drawn straight to the screen in display
        // colour space instead: textures are plain RGBA8 (not sRGB) and material colours are
        // display-encoded (work/glb_gamma.py), so values reach the screen as they were printed.
        // The room was baked and tone-mapped in Blender, so nothing is lost there.
        view.isPostProcessingEnabled = false
        view.isShadowingEnabled = false
        view.isScreenSpaceRefractionEnabled = false
        // Seen through the open hallway; matches the Mac app's grey backdrop.
        renderer.clearOptions = renderer.clearOptions.apply { clear = true; clearColor = roomGrey }

        val bytes = context.assets.open("room.glb").use { it.readBytes() }
        room = assetLoader.createAsset(ByteBuffer.allocateDirect(bytes.size).put(bytes).flip())!!
        resourceLoader.loadResources(room)
        room.releaseSourceData()
        scene.addEntities(room.entities)
        // The baked lightmap ships beside the glb so it can be a plain (display-space) texture.
        val tvScreen = room.getFirstEntityByName("TVScreen")
        lightmap = compressedLightmap(context) ?: plainTexture(android.graphics.BitmapFactory.decodeStream(context.assets.open("room_lightmap.jpg")), mipmaps = true)
        room.entities.filter { it != tvScreen && engine.renderableManager.hasComponent(it) }.forEach { e ->
            engine.renderableManager.let { rm -> rm.getMaterialInstanceAt(rm.getInstance(e), 0) }
                .setParameter("baseColorMap", lightmap, TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE))
        }
        addLights()
        room.getFirstEntityByName("TVScreen").takeIf { it != 0 }?.let { e ->
            val rm = engine.renderableManager
            if (rm.hasComponent(e)) {
                val bmp = Printing.screenSheen()
                val tex = plainTexture(bmp, mipmaps = false)
                sheenTexture = tex
                screenGlass = rm.getMaterialInstanceAt(rm.getInstance(e), 0).also {
                    it.setParameter("baseColorMap", tex, com.google.android.filament.TextureSampler(com.google.android.filament.TextureSampler.MinFilter.LINEAR, com.google.android.filament.TextureSampler.MagFilter.LINEAR, com.google.android.filament.TextureSampler.WrapMode.CLAMP_TO_EDGE))
                }
            }
        }
        hand = HandMaterials(engine, context)
        shelf = Shelf(engine, scene, assetLoader, resourceLoader, context.assets.open("dvd_case.glb").use { it.readBytes() },
            context.assets.open("dvd_case_hero.glb").use { it.readBytes() }, hand) { requestRender() }
        shelf.stock(Movie.samples)
        deck = Deck(engine, scene, assetLoader, resourceLoader, context.assets.open("disc.glb").use { it.readBytes() }, context.assets.open("tray.glb").use { it.readBytes() }, hand)
        // The blur's quad: drawn after the room (channel 1) and before what's in your hands (channel 2), ignoring depth.
        val quad = context.assets.open("backdrop.glb").use { it.readBytes() }
        fun quadAsset(priority: Int): Pair<FilamentAsset, com.google.android.filament.MaterialInstance> {
            val a = assetLoader.createAsset(ByteBuffer.allocateDirect(quad.size).put(quad).flip())!!
            resourceLoader.loadResources(a); a.releaseSourceData(); scene.addEntities(a.entities)
            val rm = engine.renderableManager
            val e = a.entities.first { rm.hasComponent(it) }
            rm.setChannel(rm.getInstance(e), 1); rm.setPriority(rm.getInstance(e), priority); rm.setLayerMask(rm.getInstance(e), 0xff, 0)
            val m = rm.getMaterialInstanceAt(rm.getInstance(e), 0)
            m.setDepthCulling(false); m.setDepthWrite(false)
            return a to m
        }
        quadAsset(2).let { (a, _) -> backdrop = a }
        // Its own tiny material (work/materials/backdrop.mat): one texture, opaque. The gltfio
        // ubershader cost the Onn's Mali-G31 most of a frame for this one full-screen quad.
        val mat = context.assets.open("backdrop.filamat").use { it.readBytes() }
        backdropMat = com.google.android.filament.Material.Builder().payload(ByteBuffer.allocateDirect(mat.size).put(mat).flip(), mat.size).build(engine)
        backdropMaterial = backdropMat.createInstance()
        engine.renderableManager.let { rm -> backdrop.entities.filter { rm.hasComponent(it) }.forEach { rm.setMaterialInstanceAt(rm.getInstance(it), 0, backdropMaterial) } }
        clip = loadClip(context)
        clip?.let { c ->
            val bytes = context.assets.open("insert_clip.filamat").use { it.readBytes() }
            val mat = com.google.android.filament.Material.Builder().payload(ByteBuffer.allocateDirect(bytes.size).put(bytes).flip(), bytes.size).build(engine)
            clipMat = mat
            for (layer in c.layers) quadAsset(layer.priority).let { (a, _) ->  // the case first, the disc over it
                val m = mat.createInstance()
                layer.quad = a; layer.material = m
                engine.renderableManager.let { rm -> a.entities.filter { rm.hasComponent(it) }.forEach { rm.setMaterialInstanceAt(rm.getInstance(it), 0, m) } }
            }
        }
        snapView.scene = scene
        snapView.camera = camera
        snapView.renderTarget = snapTarget
        snapView.viewport = Viewport(0, 0, SNAP_W, SNAP_H)
        snapView.isPostProcessingEnabled = false
        snapView.isShadowingEnabled = false
        snapView.setVisibleLayers(0xff, ROOM_LAYER)  // the room as you see it, without the quad

        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface)
                displayHelper.attach(renderer, surfaceView.display)
            }
            override fun onDetachedFromSurface() {
                displayHelper.detach()
                swapChain?.let { engine.destroySwapChain(it); engine.flushAndWait() }
                swapChain = null
            }
            override fun onResized(width: Int, height: Int) {
                this@RoomRenderer.width = width; this@RoomRenderer.height = height
                view.viewport = Viewport(0, 0, width, height)
                camera.setProjection(49.0, width.toDouble() / height, 0.015, 35.0, Camera.Fov.VERTICAL)
            }
        }
        applyQuality()
        uiHelper.attachTo(surfaceView)
    }

    private fun addLights() {
        // Soft, even fill standing in for light bouncing around the white room (1-band spherical harmonics).
        val ambient = IndirectLight.Builder().irradiance(1, floatArrayOf(1f, 0.98f, 0.95f)).intensity(110f).build(engine)
        scene.indirectLight = ambient
        // The disc is the one lit object in the room: a warm key from the window side so its metal,
        // gloss and rainbow catch the light as it moves (everything else here is baked or unlit).
        val discKey = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 0.93f, 0.82f).intensity(220f).direction(-0.55f, -0.65f, -0.5f).castShadows(false)
            .sunAngularRadius(2.5f).sunHaloSize(0f).build(engine, discKey)
        scene.addEntity(discKey)
        // Everything else is baked or unlit. Only the detailed case in your hands is lit live: a soft,
        // warm key from the window side (right) so its bevels and plastic read as a real object.
        val key = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1f, 0.9f, 0.78f).intensity(60f).direction(-0.6f, -0.35f, -0.7f).castShadows(false)
            .lightChannel(0, false).lightChannel(HAND_LIGHTS, true)
            .build(engine, key)
        scene.addEntity(key)
        // No ceiling lamp here: the room's own lighting is baked in Blender, and a live point light
        // only put a glare spot on the TV glass. The window light and ambient fill light the DVD cases.
    }

    private fun orbit(point: Viewpoint) = when (point) {
        // Framed like the reference: left bookcase to DVD tower, console feet to the tower's top.
        // 2.4 m tall at the TV's plane (z 0.48) needs the eye 2.63 m in front of it.
        Viewpoint.ROOM -> Orbit(-0.05f, 1.1f, 0.12f, 0f, 0f, 2.99f)
        // Close enough to read the spines: about 1¾ shelves fill the screen.
        // Searching, the camera rises past the top shelf, leaving room above it for the search bar.
        Viewpoint.SHELF -> Orbit(1.275f, 0.1745f + shelfIndex * 0.26f + (if (searchLift) SEARCH_LIFT else 0f), 0.274f, -0.06f, 0f, 0.5f)
        // Straight on, close enough that the virtual screen (1.459 x 0.818 m, 16:9) fills the
        // real one: half-height 0.409 m / tan(24.5 deg) = 0.8975 m.
        Viewpoint.TV -> Orbit(0f, 1.14f, 0.48f, 0f, 0f, 0.8975f)
        // A step back from the TV, still dead centre: the disc menu shows on its screen from here.
        Viewpoint.TV_NEAR -> Orbit(0f, 1.14f, 0.48f, 0f, 0f, 1.6f)
        // Looking down at the deck from just right of it, for the disc going in.
        Viewpoint.DECK -> Orbit(-0.80f, 0.68f, 0.58f, 0.22f, 0.42f, 0.8f)
    }

    /** Puts the camera at [point] at once (no move): for picking up where the app left off. */
    fun jumpTo(point: Viewpoint, row: Int) {
        shelfIndex = row.coerceIn(0, 7)
        viewpoint = point
        to = orbit(point); from = to; startNanos = 0L
        blur = 0f; blurT = 0f
        shelf.showHighlight(point == Viewpoint.SHELF)
        requestRender()
    }

    /** [gentle]: a softer start and landing (quintic ease), used for the push into the TV. */
    fun go(point: Viewpoint, seconds: Float = 0.75f, gentle: Boolean = false): Boolean {
        from = current(System.nanoTime())  // where the camera is now, measured with the old move's timing
        // The blurred snapshot only matches a still camera.
        blur = 0f; blurT = 0f; blurArmed = false; snapState = SNAP_NONE; snapRun++
        duration = seconds
        this.gentle = gentle
        requestRender()
        viewpoint = point
        shelf.showHighlight(point == Viewpoint.SHELF)  // the picked-out case slides home when you step back
        to = orbit(point)
        startNanos = System.nanoTime()
        return true
    }

    /**
     * Behind the loading screen: the detailed case (and whatever the caller puts in the held scene) is
     * drawn with the blur on, so those shaders compile now instead of stalling the first pick-up.
     */
    var warming = false
        set(value) { field = value; shelf.warmUp(value); requestRender() }
    private val caseInHand get() = shelf.held != null || warming
    private val blurWanted get() = caseInHand || searching
    private val blurTarget get() = if (blurWanted && quality.blur && snapState == SNAP_READY && (blurArmed || warming)) 1f else 0f

    /**
     * Before a case comes off the shelf: snapshot the room now (without that case) and blur it while
     * the cover downloads, so the blur is ready the moment the case lifts.
     */
    fun prepareFocusPull(hide: IntArray) {
        if (!quality.blur || cameraMoving) return
        snapRun++; snapState = SNAP_REQUESTED; hideForSnapshot = hide; blurUsed = false
        requestRender()
    }
    /** Runs [then] once the blur levels are ready (at once with the blur off), or after [timeoutMs] regardless. */
    fun whenFocusReady(timeoutMs: Long, then: () -> Unit) {
        if (!quality.blur || snapState == SNAP_READY) { then(); return }
        var done = false
        val once = { if (!done) { done = true; then() } }
        focusWaits += once
        main.postDelayed(once, timeoutMs)
    }
    /** Starts the blur, timed to the case's 0.6 s flight to your hands (same smoothstep easing). */
    fun startFocusPull() { blurArmed = true; lastFrameNanos = 0L; requestRender() }

    /**
     * The TV picture's corners on screen (pixels, top-left origin: top-left, top-right,
     * bottom-right, bottom-left) as of the last frame drawn, so an overlay pinned to it moves with
     * the picture actually on screen; null when part of it is behind the camera.
     */
    var tvQuad: FloatArray? = null; private set

    /**
     * The DVD tower's top on screen, in view pixels: left x, right x (the case rows' ends), y (the top's
     * front edge). Updated as frames are drawn, for the search bar laid above the tower.
     */
    val shelfTop = androidx.compose.runtime.mutableStateOf<FloatArray?>(null)
    // The rows run x 1.118..1.433; the tower's top is 2.18 m up, its front edge at z 0.325 (work/build_room.py).
    private val shelfTopEdge = doubleArrayOf(1.1184, 2.18, 0.325, 1.4328, 2.18, 0.325)

    private fun projectTv(eye: DoubleArray, tx: Double, ty: Double, tz: Double): FloatArray? {
        // The picture: 1.459 x 0.818 m centred at (0, 1.14, 0.48), facing +Z.
        return project(eye, tx, ty, tz, doubleArrayOf(-0.7295, 1.549, 0.48, 0.7295, 1.549, 0.48, 0.7295, 0.731, 0.48, -0.7295, 0.731, 0.48))
    }

    /** World points (x, y, z triples) to view pixels (x, y pairs, top-left origin); null if any is behind the camera. */
    private fun project(eye: DoubleArray, tx: Double, ty: Double, tz: Double, points: DoubleArray): FloatArray? {
        // Camera basis from the same lookAt the frame uses (up = +Y).
        var fx = tx - eye[0]; var fy = ty - eye[1]; var fz = tz - eye[2]
        val fl = kotlin.math.sqrt(fx * fx + fy * fy + fz * fz); fx /= fl; fy /= fl; fz /= fl
        var rx = -fz; var rz = fx; val rl = kotlin.math.sqrt(rx * rx + rz * rz); rx /= rl; rz /= rl  // f × up
        val ux = -rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy                                // r × f
        val tanY = kotlin.math.tan(Math.toRadians(24.5)); val tanX = tanY * width / height
        // In the screen's own pixels (the 3D may be drawn smaller and scaled up), for views laid over it.
        val vw = surfaceView.width.takeIf { it > 0 } ?: width; val vh = surfaceView.height.takeIf { it > 0 } ?: height
        val n = points.size / 3
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val px = points[i * 3] - eye[0]; val py = points[i * 3 + 1] - eye[1]; val pz = points[i * 3 + 2] - eye[2]
            val depth = px * fx + py * fy + pz * fz
            if (depth < 0.02) return null
            out[i * 2] = (((px * rx + pz * rz) / depth / tanX + 1) / 2 * vw).toFloat()
            out[i * 2 + 1] = ((1 - (px * ux + py * uy + pz * uz) / depth / tanY) / 2 * vh).toFloat()
        }
        return out
    }

    /** Nothing is drawn until the loading screen is up, so the room never flashes before it. */
    var holding = true
        set(value) { field = value; if (!value) requestRender() }
    val shelfRow get() = shelfIndex
    /** The camera raised above the top shelf (true) or back on it, for the search bar. */
    var searchLift = false; private set
    fun liftForSearch(up: Boolean) { if (searchLift != up) { searchLift = up; go(Viewpoint.SHELF, 0.5f) } }
    /** Searching blurs the room like a case in your hands does: [prepareFocusPull], then [startFocusPull]. */
    var searching = false
        set(value) { field = value; requestRender() }

    fun moveShelf(step: Int): Boolean {
        if (viewpoint != Viewpoint.SHELF) return false
        shelfIndex = min(7, max(0, shelfIndex + step))
        shelf.focusRow(shelfIndex)
        return go(Viewpoint.SHELF)
    }

    private fun current(now: Long): Orbit {
        val t = min(1f, (now - startNanos) / 1e9f / duration)
        val e = if (gentle) t * t * t * (t * (6 * t - 15) + 10) else t * t * (3 - 2 * t)  // ease in-out
        fun mix(a: Float, b: Float) = a + (b - a) * e
        return Orbit(mix(from.tx, to.tx), mix(from.ty, to.ty), mix(from.tz, to.tz), mix(from.yaw, to.yaw), mix(from.pitch, to.pitch), mix(from.distance, to.distance))
    }

    // Render on demand: a still room is drawn once, not 60 times a second, so the TV box's GPU
    // is free whenever nothing moves (and the remote's next press is answered at once).
    private var pendingFrames = 3
    var framesDrawn = 0; private set
    fun requestRender() { pendingFrames = maxOf(pendingFrames, 2) }
    private val cameraMoving get() = (System.nanoTime() - startNanos) / 1e9f < duration + 0.05f

    // Frame timing, logged every 5 s while drawing: adb logcat -s ShelfLifePerf
    private var perfFrames = 0; private var perfNanos = 0L; private var perfSince = 0L

    // Per-phase pacing for the bench tour (MainActivity sets [phase]): gaps between drawn frames while
    // something moves, frames Filament skipped (GPU behind), CPU and GPU time per frame.
    private val gaps = ArrayList<Float>()
    private var lastDrawn = 0L; private var skips = 0; private var skipRun = 0; private var cpuSum = 0.0
    private var gpuSum = 0.0; private var gpuCount = 0; private var lastFrameId = -1
    private val frameInfo = arrayOf(Renderer.FrameInfo())
    val benchLines = ArrayList<String>()
    var phase: String? = null
        set(value) {
            field?.let { name ->
                val g = gaps.sorted()
                fun pct(p: Float) = if (g.isEmpty()) 0f else g[min(g.size - 1, (g.size * p).toInt())]
                val vsync = 1000f / (surfaceView.display?.refreshRate ?: 60f)
                val line = "%-10s frames=%3d fps=%4.1f p50=%4.1f p95=%5.1f max=%5.1f janky=%2d skipped=%2d cpu=%4.1f gpu=%s".format(
                    name, g.size + 1, if (g.isEmpty()) 0f else 1000f * g.size / g.sum(), pct(0.5f), pct(0.95f), g.lastOrNull() ?: 0f,
                    g.count { it > vsync * 1.5f }, skips, cpuSum / max(1, g.size + 1), if (gpuCount == 0) "n/a" else "%.1f".format(gpuSum / gpuCount))
                benchLines += line
                android.util.Log.i("ShelfLifePerf", "BENCH $line")
            }
            field = value; gaps.clear(); skips = 0; skipRun = 0; cpuSum = 0.0; gpuSum = 0.0; gpuCount = 0; lastDrawn = 0L
        }

    override fun doFrame(frameTimeNanos: Long) {
        choreographer.postFrameCallback(this)
        if (gpuWaits.isNotEmpty()) checkGpuWaits()
        if (holding) return
        if (quality.showFps) updateFps(frameTimeNanos)
        val busy = cameraMoving || shelf.animating || deck.animating || blurT != blurTarget || insertStart != 0L || bakeDone != null || clipWarm < clipWarmCount
        if (busy) pendingFrames = 2  // after any movement, two settled frames must reach the screen
        else if (pendingFrames <= 0) { lastDrawn = 0L; return }
        if (quality.steady30) { oddVsync = !oddVsync; if (oddVsync) return }  // every other vsync: even pacing
        val started = System.nanoTime()
        try {
            // A frame Filament skips (GPU still busy) is retried, so the last pose always gets shown.
            val drawn = renderFrame(frameTimeNanos)
            if (drawn && !busy) pendingFrames--
            val gpu = if (drawn) newGpuMs() else null
            if (drawn) { fpsFrames++; gpu?.let { fpsGpu += it; fpsGpuN++ } }
            if (phase != null) {
                if (!drawn) { skips++; skipRun++ }
                else {
                    // Any long hold-up gets its own line, with the time, to line up against the rest of the log.
                    if (lastDrawn != 0L && frameTimeNanos - lastDrawn > 100_000_000L)
                        android.util.Log.i("ShelfLifePerf", "gap %.0f ms in %s, %d frames skipped".format((frameTimeNanos - lastDrawn) / 1e6, phase, skipRun))
                    skipRun = 0
                    if (lastDrawn != 0L && frameTimeNanos - lastDrawn < 250_000_000L) gaps += (frameTimeNanos - lastDrawn) / 1e6f
                    lastDrawn = frameTimeNanos
                    cpuSum += (System.nanoTime() - started) / 1e6
                    gpu?.let { gpuSum += it; gpuCount++ }
                }
            }
        } finally {
            val ms = (System.nanoTime() - started) / 1e6
            if (ms > 16) android.util.Log.i("ShelfLifePerf", "3D frame took ${"%.0f".format(ms)} ms")
        }
        perfFrames++; perfNanos += System.nanoTime() - started
        if (frameTimeNanos - perfSince > 5_000_000_000L) {
            if (perfSince != 0L) android.util.Log.i("ShelfLifePerf", "drew $perfFrames frames in 5 s, ${perfNanos / maxOf(1, perfFrames) / 1_000_000.0} ms CPU each")
            perfSince = frameTimeNanos; perfFrames = 0; perfNanos = 0
        }
    }

    /** GPU time of the latest finished frame not yet counted, when the driver reports one. */
    private fun newGpuMs(): Double? {
        if (renderer.getFrameInfoHistory(frameInfo) == 0) return null
        val f = frameInfo[0]
        if (f.frameId == lastFrameId || f.gpuFrameDuration <= 0) return null
        lastFrameId = f.frameId
        return f.gpuFrameDuration / 1e6
    }

    private fun updateFps(now: Long) {
        if (fpsSince == 0L) { fpsSince = now; return }
        val span = now - fpsSince
        if (span < 500_000_000L) return
        fpsText.value = if (fpsFrames == 0) "still" else "%.0f fps".format(fpsFrames * 1e9 / span) + if (fpsGpuN > 0) "  ·  GPU %.0f ms".format(fpsGpu / fpsGpuN) else ""
        fpsSince = now; fpsFrames = 0; fpsGpu = 0.0; fpsGpuN = 0
    }

    private fun renderFrame(frameTimeNanos: Long): Boolean {
        val o = current(frameTimeNanos)
        val eyeX = o.tx + sin(o.yaw) * cos(o.pitch) * o.distance
        val eyeY = max(0.24f, o.ty + sin(o.pitch) * o.distance)
        val eyeZ = o.tz + cos(o.yaw) * cos(o.pitch) * o.distance
        camera.lookAt(eyeX.toDouble(), eyeY.toDouble(), eyeZ.toDouble(), o.tx.toDouble(), o.ty.toDouble(), o.tz.toDouble(), 0.0, 1.0, 0.0)
        val eye = doubleArrayOf(eyeX.toDouble(), eyeY.toDouble(), eyeZ.toDouble())
        val quad = projectTv(eye, o.tx.toDouble(), o.ty.toDouble(), o.tz.toDouble())
        val top = project(eye, o.tx.toDouble(), o.ty.toDouble(), o.tz.toDouble(), shelfTopEdge)?.let { floatArrayOf(it[0], it[2], (it[1] + it[3]) / 2) }
        val was = shelfTop.value
        if ((top == null) != (was == null) || (top != null && was != null && top.indices.any { kotlin.math.abs(top[it] - was[it]) > 0.5f })) shelfTop.value = top
        camera.getModelMatrix(cameraMatrix)
        // Straight-on at the TV, its screen covers exactly 0.8975 / distance of the view; the menu
        // overlay is scaled by this so it sits on the virtual screen during the zoom.
        val straightOn = kotlin.math.abs(o.tx) < 0.001f && kotlin.math.abs(o.ty - 1.14f) < 0.001f && kotlin.math.abs(o.tz - 0.48f) < 0.001f &&
            kotlin.math.abs(o.yaw) < 0.001f && kotlin.math.abs(o.pitch) < 0.001f
        screenScale.floatValue = if (straightOn) min(1f, 0.8975f / o.distance) else 1f
        // Full reflection from the room view (≥2.6 m from the screen), none by 1.2 m (TV view is 0.9 m).
        val d = kotlin.math.sqrt((eyeX * eyeX + (eyeY - 1.14f) * (eyeY - 1.14f) + (eyeZ - 0.48f) * (eyeZ - 0.48f)).toDouble()).toFloat()
        val t = ((d - 1.2f) / 1.4f).coerceIn(0f, 1f)
        val level = t * t * (3 - 2 * t)
        if (kotlin.math.abs(level - sheenLevel) > 0.005f) {
            sheenLevel = level
            screenGlass?.setParameter("baseColorFactor", com.google.android.filament.Colors.RgbaType.LINEAR, level, level, level, 1f)
        }
        for (i in 0 until 16) camera32[i] = cameraMatrix[i].toFloat()
        shelf.update(frameTimeNanos, camera32)
        updateBlur(frameTimeNanos)
        placeBackdrop()
        insertStep(frameTimeNanos)
        deck.update(frameTimeNanos, camera32)
        val chain = swapChain ?: return false
        if (!uiHelper.isReadyToRender) return false
        val t0 = System.nanoTime()
        if (!renderer.beginFrame(chain, frameTimeNanos)) return false
        val t1 = System.nanoTime()
        if (quality.blur && !cameraMoving && (snapState == SNAP_REQUESTED || (blurWanted && snapState == SNAP_NONE))) snapshot()
        renderer.render(view)
        if (warmFrame) { snapView.setVisibleLayers(0xff, BACKDROP_LAYER); renderer.render(snapView); snapView.setVisibleLayers(0xff, ROOM_LAYER) }
        warmClip()
        if (bakeDone != null && !bakePending && framesDrawn > 0) bakeCapture()
        renderer.endFrame()
        tvQuad = quad
        if (nextFrame.isNotEmpty()) nextFrame.toList().also { nextFrame.clear() }.forEach { it() }
        val t2 = System.nanoTime()
        if (t2 - t0 > 30_000_000L) android.util.Log.i("ShelfLifePerf", "slow frame: begin %.0f render %.0f ms".format((t1 - t0) / 1e6, (t2 - t1) / 1e6))
        framesDrawn++
        return true
    }

    /**
     * The focus pull: in over 0.6 s with the case flying to your hands, out over 0.5 s as it goes
     * back, both smoothstep-eased like the case's own move.
     */
    private fun updateBlur(now: Long) {
        val dt = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / 1e9f).coerceAtMost(0.05f)
        lastFrameNanos = now
        if (!blurWanted) blurArmed = false
        val target = blurTarget
        if (blurT != target) blurT = if (target > blurT) min(target, blurT + dt / 0.6f) else max(target, blurT - dt / 0.5f)
        blur = blurT * blurT * (3 - 2 * blurT)
        if (blur > 0f) blurUsed = true
        // A finished pull drops its snapshot; one prepared for the next pick-up is kept.
        if (blur == 0f && !blurWanted && blurUsed && snapState != SNAP_NONE) { snapState = SNAP_NONE; snapRun++; blurUsed = false }
    }

    // The blurred room behind a case in your hands: drawn once into a small target, read back,
    // blurred on a worker thread and shown on a quad just in front of the camera, fading in over
    // the sharp room. Once fully in, the room itself isn't drawn at all. (A depth-of-field blur
    // every frame cost the Mali GPU more than everything else together.)
    private companion object {
        const val SNAP_W = 640; const val SNAP_H = 360
        const val SEARCH_LIFT = 0.1f  // m the camera rises above the top shelf for the search bar
        const val SNAP_NONE = 0; const val SNAP_PENDING = 1; const val SNAP_READY = 2; const val SNAP_REQUESTED = 3
        // The focus pull's images, sharpest first: the snapshot itself, then box blurs (radius, passes)
        // at 640 px wide in even steps of softness (σ ≈ 0, 0.8, 1.4, 2.0, 2.8, 3.7, 4.5, 5.5, 6.5 px).
        // One full-screen image per frame keeps the Onn's GPU under 16 ms; the steps are small enough
        // to read as one continuous pull.
        val BLUR_LEVELS = arrayOf(0 to 0, 1 to 1, 1 to 3, 2 to 2, 3 to 2, 4 to 2, 4 to 3, 5 to 3, 6 to 3)
        const val ROOM_LAYER = 0x1; const val BACKDROP_LAYER = 0x2; const val HAND_LAYER = 0x4
        const val HAND_LIGHTS = 1  // light channel of the warm key on whatever is in your hands
    }

    private fun snapshot() {
        snapState = SNAP_PENDING
        val run = snapRun
        // The case about to be lifted is left out: in the blur it's already in your hands.
        val rm = engine.renderableManager
        val hidden = hideForSnapshot?.filter { rm.hasComponent(it) }?.map { it to rm.getLayerMask(rm.getInstance(it)) }.orEmpty()
        hidden.forEach { (e, _) -> rm.setLayerMask(rm.getInstance(e), 0xff, 0) }
        renderer.render(snapView)
        hidden.forEach { (e, mask) -> rm.setLayerMask(rm.getInstance(e), 0xff, mask) }
        hideForSnapshot = null
        val pixels = ByteBuffer.allocateDirect(SNAP_W * SNAP_H * 4)
        renderer.readPixels(snapTarget, 0, 0, SNAP_W, SNAP_H, Texture.PixelBufferDescriptor(pixels, Texture.Format.RGBA, Texture.Type.UBYTE, 1, 0, 0, 0, main, Runnable {
            worker.execute {
                val raw = android.graphics.Bitmap.createBitmap(SNAP_W, SNAP_H, android.graphics.Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(pixels.rewind()) }
                val levels = BLUR_LEVELS.map { (r, passes) -> Printing.blurred(raw, radius = r, flip = true, passes = passes) }  // GL rows come bottom-up
                main.post {
                    if (run != snapRun) return@post
                    levels.forEachIndexed { i, bmp -> levelTextures[i]?.let { engine.destroyTexture(it) }; levelTextures[i] = plainTexture(bmp, mipmaps = false) }
                    shownLevels = intArrayOf(-1)
                    warmLevel = 0  // each level is drawn once, invisibly, before the pull (see placeBackdrop)
                    snapState = SNAP_READY
                    engine.flush()
                    focusWaits.toList().also { focusWaits.clear() }.forEach { it() }
                    requestRender()
                }
            }
        }))
    }

    /**
     * Shows the blur on one quad just past the near plane: the image for this point of the pull
     * (blur 0 = the room itself; above 0 the snapshot, then softer and softer). It covers the screen,
     * so the room isn't drawn at all during the pull.
     */
    private fun placeBackdrop() {
        val n = BLUR_LEVELS.size
        val level = if (snapState == SNAP_READY && blur > 0f) kotlin.math.round(blur * (n - 1)).toInt().coerceIn(0, n - 1) else -1
        // A texture's first draw stalls the GPU (Mali and the emulator alike), which would drop frames
        // mid-pull. So while the cover downloads, each image is drawn once offscreen (see renderFrame).
        warmFrame = blur == 0f && snapState == SNAP_READY && warmLevel < n
        if (warmFrame) { showQuad(backdrop, backdropMaterial, 0, warmLevel, 1f); warmLevel++; requestRender() }
        else showQuad(backdrop, backdropMaterial, 0, level, 1f)
        val roomHidden = level >= 0 && !cameraMoving && !warmFrame
        view.setVisibleLayers(0x7, when { warmFrame -> ROOM_LAYER or HAND_LAYER; roomHidden -> BACKDROP_LAYER or HAND_LAYER; else -> 0x7 })
        // Edge smoothing on the case costs little once the room isn't drawn behind it (switched only at full blur, never mid-pull).
        msaa(quality.smoothRoom || (quality.smoothCase && blur >= 1f && !cameraMoving))
        if (shownLevels[0] < 0) return
        val z = 0.05; val sy = 2 * z * kotlin.math.tan(Math.toRadians(24.5)); val sx = sy * width / height
        val c = cameraMatrix
        val m = FloatArray(16) { i ->
            when (i / 4) {
                0 -> (c[i] * sx).toFloat()
                1 -> (c[i] * sy).toFloat()
                2 -> c[i].toFloat()
                else -> (c[i] - c[8 + i % 4] * z).toFloat()
            }
        }
        m[15] = 1f
        engine.transformManager.let { tm -> tm.setTransform(tm.getInstance(backdrop.root), m) }
    }

    // --- The disc coming out of the case ---
    //
    // The open case and the disc (silver and rainbow) cost the Onn's Mali-G31 most of a frame to shade, so
    // that stretch ([InsertTimeline]) is baked once on the box itself (same renderer, same pixels) into ASTC
    // frames, each cropped to what it shows, in two layers: the case (stored once while it stands still)
    // and the disc (baked with the case filling depth, so whatever the lid hides is left out of it).
    // Played back, each layer is one quad over the blurred room; the disc's label (it's per film) and the
    // covers on the lid stay live, placed from the timeline at the frame's own time, and the plastic fills
    // depth only so the lid still hides them.

    /**
     * assets/insert_clip.bin (work/make_insert_clip.py): "SLC2", frame count, fps, full width, height, then
     * per layer (case, disc): its distinct frames (count; each x, y top-left, width, height in whole 6x6
     * blocks, byte count, ASTC blocks) and, per timeline frame, which of them shows.
     * Null without it, if the GPU can't read ASTC, or without the depth-only material: the disc is drawn live.
     */
    private fun loadClip(context: Context): Clip? = runCatching {
        if (hand.depthOnly == null || !Texture.isTextureFormatSupported(engine, Texture.InternalFormat.RGBA_ASTC_6x6)) return null
        val bytes = context.assets.open("insert_clip.bin").use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (buf.int != 0x32434C53) return null  // "SLC2"
        val count = buf.int; val fps = buf.int; val w = buf.int; val h = buf.int
        if (fps != InsertTimeline.FPS || count != InsertTimeline.FRAMES) { android.util.Log.w("ShelfLifePerf", "insert clip is out of date (rebake it); drawing the disc live"); return null }
        val layers = List(2) { layer ->
            val frames = List(buf.int) {
                val x = buf.int; val y = buf.int; val fw = buf.int; val fh = buf.int; val size = buf.int
                val data = ByteBuffer.allocateDirect(size).also { d -> d.put(bytes, buf.position(), size); d.flip() }
                buf.position(buf.position() + size)
                val tex = Texture.Builder().width(fw).height(fh).levels(1).sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.RGBA_ASTC_6x6).build(engine)
                tex.setImage(engine, 0, Texture.PixelBufferDescriptor(data, Texture.CompressedType.RGBA_ASTC_6x6, size))
                ClipFrame(x, y, fw, fh, tex)
            }
            ClipLayer(frames, IntArray(count) { buf.int }, priority = 3 + layer)
        }
        android.util.Log.i("ShelfLifePerf", "insert clip: $count frames (${layers[0].frames.size} case, ${layers[1].frames.size} disc), ${bytes.size / 1024} KB")
        Clip(w, h, layers)
    }.getOrElse { android.util.Log.w("ShelfLifePerf", "no insert clip (${it.message}); drawing the disc live"); null }

    /** The disc starts coming out now (MainActivity's insertDisc); [endInsert] when the view turns to the player. */
    fun startInsert() {
        insertStart = System.nanoTime()
        if (clip != null) { shelf.insertMode(clip = true, bake = false); deck.discLayers(body = false, label = true) }
        requestRender()
    }
    fun endInsert() {
        if (insertStart == 0L) return
        insertStart = 0L
        showClipFrame(-1)
        if (clip != null) { shelf.insertMode(clip = false, bake = false); deck.discLayers(body = true, label = true) }
    }

    /** Lid and disc for this frame: at the shown clip frame's own time, so the live label stays on it. */
    private fun insertStep(now: Long) {
        val t = when { bakeDone != null -> bakeFrame / 2f / InsertTimeline.FPS; insertStart != 0L -> (now - insertStart) / 1e9f; else -> return }
        val frame = if (clip != null || bakeDone != null) min(InsertTimeline.FRAMES - 1, (t * InsertTimeline.FPS).toInt()) else -1
        val at = if (frame >= 0) frame.toFloat() / InsertTimeline.FPS else t
        shelf.holdLid(InsertTimeline.lid(at))
        deck.showDisc(InsertTimeline.disc(at, shelf))
        if (bakeDone == null && frame >= 0) showClipFrame(frame)
    }

    /** Timeline frame [index] on the layers' quads, each sized and placed over the part of the screen it was cropped from; -1 hides them. */
    private fun showClipFrame(index: Int) {
        val clip = clip ?: return
        for (layer in clip.layers) showLayerFrame(clip, layer, if (index >= 0) layer.index[index] else -1)
    }
    private fun showLayerFrame(clip: Clip, layer: ClipLayer, frame: Int) {
        val quad = layer.quad ?: return
        val rm = engine.renderableManager
        if (frame != layer.shown) {
            layer.shown = frame
            for (e in quad.entities) if (rm.hasComponent(e)) rm.setLayerMask(rm.getInstance(e), 0xff, if (frame >= 0) HAND_LAYER else 0)
            layer.frames.getOrNull(frame)?.let { layer.material?.setParameter("image", it.texture, TextureSampler(TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)) }
        }
        val f = layer.frames.getOrNull(frame) ?: return
        val z = 0.05; val sy = 2 * z * kotlin.math.tan(Math.toRadians(24.5)); val sx = sy * width / height
        val ox = sx * ((f.x + f.w / 2.0) / clip.width - 0.5); val oy = sy * (0.5 - (f.y + f.h / 2.0) / clip.height)
        val qx = sx * f.w / clip.width; val qy = sy * f.h / clip.height
        val c = cameraMatrix
        val m = FloatArray(16) { i ->
            when (i / 4) {
                0 -> (c[i] * qx).toFloat()
                1 -> (c[i] * qy).toFloat()
                2 -> c[i].toFloat()
                else -> (c[i] - c[8 + i % 4] * z + c[i % 4] * ox + c[4 + i % 4] * oy).toFloat()
            }
        }
        m[15] = 1f
        engine.transformManager.let { tm -> tm.setTransform(tm.getInstance(quad.root), m) }
    }

    /** Behind the loading curtain: each clip frame drawn once offscreen (a texture's first draw stalls the Mali). */
    private fun warmClip() {
        val clip = clip ?: return
        if (clipWarm >= clipWarmCount || framesDrawn < 2 || insertStart != 0L) return
        val i = clipWarm++
        for (layer in clip.layers) showLayerFrame(clip, layer, if (i < layer.frames.size) i else -1)
        snapView.setVisibleLayers(0xff, HAND_LAYER); renderer.render(snapView); snapView.setVisibleLayers(0xff, ROOM_LAYER)
        if (clipWarm == clipWarmCount) showClipFrame(-1)
    }
    private val clipWarmCount get() = clip?.layers?.maxOf { it.frames.size } ?: 0

    // Baking: `am start -n com.shelflife.tv/.MainActivity --ez bakeInsert true` with a case in your hands.
    // Two passes a timeline frame: the case alone, then the disc with the case filling depth.
    private var bakeDone: ((java.io.File) -> Unit)? = null
    private var bakeDir: java.io.File? = null
    private var bakeFrame = 0  // pass: timeline frame × 2, + 1 for the disc
    private var bakePending = false
    private var bakeView: View? = null
    private var bakeTarget: RenderTarget? = null
    private var bakeTextures = emptyList<Texture>()
    private var bakeBuffer: ByteBuffer? = null

    /** Renders the timeline into PNGs in [dir]: c… the case, d… the disc (premultiplied RGBA, cropped, rows as read back: top-down). */
    fun bakeInsert(dir: java.io.File, done: (java.io.File) -> Unit) {
        dir.mkdirs(); dir.listFiles()?.forEach { it.delete() }
        val color = Texture.Builder().width(width).height(height).levels(1).sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.RGBA8)
            .usage(Texture.Usage.COLOR_ATTACHMENT or Texture.Usage.SAMPLEABLE or Texture.Usage.BLIT_SRC).build(engine)
        val depth = Texture.Builder().width(width).height(height).levels(1).sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.DEPTH24).usage(Texture.Usage.DEPTH_ATTACHMENT).build(engine)
        bakeTextures = listOf(color, depth)
        bakeTarget = RenderTarget.Builder().texture(RenderTarget.AttachmentPoint.COLOR, color).texture(RenderTarget.AttachmentPoint.DEPTH, depth).build(engine)
        bakeView = engine.createView().apply {
            scene = this@RoomRenderer.scene; camera = this@RoomRenderer.camera; renderTarget = bakeTarget
            viewport = Viewport(0, 0, width, height); isPostProcessingEnabled = false; isShadowingEnabled = false
            setVisibleLayers(0xff, HAND_LAYER)
        }
        bakeBuffer = ByteBuffer.allocateDirect(width * height * 4)
        deck.inHand(true)  // lit and drawn like the disc in the insert
        bakeDir = dir; bakeFrame = 0; bakePending = false; bakeDone = done
        requestRender()
    }

    private fun bakeCapture() {
        val view = bakeView ?: return; val buf = bakeBuffer ?: return; val dir = bakeDir ?: return
        val w = width; val h = height; val frame = bakeFrame / 2; val disc = bakeFrame % 2 == 1
        // The case alone, covers left out; or the disc (no label) with the case filling depth only.
        shelf.insertMode(clip = disc, bake = true); deck.discLayers(body = disc, label = false)
        bakePending = true
        // Cleared to transparent (the clip goes over the blurred room); the room's grey otherwise.
        val room = renderer.clearOptions
        renderer.clearOptions = renderer.clearOptions.apply { clear = true; clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0) }
        renderer.render(view)
        renderer.clearOptions = room.apply { clearColor = roomGrey }
        renderer.readPixels(bakeTarget!!, 0, 0, w, h, Texture.PixelBufferDescriptor(buf.rewind(), Texture.Format.RGBA, Texture.Type.UBYTE, 1, 0, 0, 0, main, Runnable {
            worker.execute {
                // Crop to what has any alpha.
                var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
                for (y in 0 until h) for (x in 0 until w) if (buf.get((y * w + x) * 4 + 3).toInt() != 0) {
                    if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                }
                if (x1 < 0) { x0 = 0; y0 = 0; x1 = 0; y1 = 0 }
                val cw = x1 - x0 + 1; val ch = y1 - y0 + 1
                val crop = ByteBuffer.allocateDirect(cw * ch * 4)
                for (y in y0..y1) { buf.limit((y * w + x1 + 1) * 4); buf.position((y * w + x0) * 4); crop.put(buf); buf.limit(buf.capacity()) }
                crop.rewind()
                // Not premultiplied as far as Android knows, so the PNG keeps the values exactly as rendered.
                val bmp = android.graphics.Bitmap.createBitmap(cw, ch, android.graphics.Bitmap.Config.ARGB_8888).apply { isPremultiplied = false; copyPixelsFromBuffer(crop) }
                java.io.File(dir, "%s%03d_%d_%d_%d_%d_%d_%d.png".format(if (disc) "d" else "c", frame, x0, y0, cw, ch, w, h)).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                main.post {
                    bakePending = false
                    if (++bakeFrame < InsertTimeline.FRAMES * 2) { requestRender(); return@post }
                    shelf.insertMode(clip = false, bake = false); deck.discLayers(body = true, label = true)
                    deck.inHand(false); deck.hideDisc(); shelf.holdLid(0f)
                    engine.destroyView(view); engine.destroyRenderTarget(bakeTarget!!); bakeTextures.forEach { engine.destroyTexture(it) }
                    bakeView = null; bakeTarget = null; bakeBuffer = null
                    bakeDone?.invoke(dir); bakeDone = null
                }
            }
        }))
    }

    private fun showQuad(asset: FilamentAsset, material: com.google.android.filament.MaterialInstance, slot: Int, level: Int, alpha: Float) {
        val rm = engine.renderableManager
        if (level != shownLevels[slot]) {
            shownLevels[slot] = level
            for (e in asset.entities) if (rm.hasComponent(e)) rm.setLayerMask(rm.getInstance(e), 0xff, if (level >= 0) BACKDROP_LAYER else 0)
            levelTextures.getOrNull(level)?.let { material.setParameter("image", it, TextureSampler(TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)) }
        }
    }

    private var msaaOn: Boolean? = null
    private fun msaa(on: Boolean) {
        if (on == msaaOn) return
        msaaOn = on
        view.multiSampleAntiAliasingOptions = view.multiSampleAntiAliasingOptions.apply { enabled = on; sampleCount = 4 }
    }
    /**
     * The lightmap as ASTC 6x6 (work/make_lightmap_astc.py): the GPU reads 1/9 of the bytes for every
     * pixel of the room, and it takes 2.4 MB instead of 22 MB. Null when the GPU can't decode ASTC
     * (the JPEG is used then).
     */
    private fun compressedLightmap(context: Context): Texture? = runCatching {
        if (!Texture.isTextureFormatSupported(engine, Texture.InternalFormat.RGBA_ASTC_6x6)) { android.util.Log.i("ShelfLifePerf", "lightmap: no ASTC on this GPU, using the JPEG"); return null }
        val bytes = context.assets.open("room_lightmap.astc").use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (buf.int != 0x36414C53) return null  // "SLA6"
        val levels = buf.int
        val w0 = buf.getInt(8); val h0 = buf.getInt(12)
        val tex = Texture.Builder().width(w0).height(h0).levels(levels).sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.RGBA_ASTC_6x6).build(engine)
        for (level in 0 until levels) {
            buf.int; buf.int; val size = buf.int
            val data = ByteBuffer.allocateDirect(size).also { it.put(bytes, buf.position(), size); it.flip() }
            buf.position(buf.position() + size)
            tex.setImage(engine, level, Texture.PixelBufferDescriptor(data, Texture.CompressedType.RGBA_ASTC_6x6, size))
        }
        android.util.Log.i("ShelfLifePerf", "lightmap: ASTC 6x6, $levels levels")
        tex
    }.getOrElse { android.util.Log.w("ShelfLifePerf", "ASTC lightmap failed (${it.message}); using the JPEG"); null }

    /** A plain RGBA8 texture: the bitmap's values go to the screen unchanged (see the post-processing note). */
    private fun plainTexture(bitmap: android.graphics.Bitmap, mipmaps: Boolean): Texture =
        Texture.Builder().width(bitmap.width).height(bitmap.height).levels(if (mipmaps) 0xff else 1).sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.RGBA8).usage(if (mipmaps) Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE else Texture.Usage.DEFAULT).build(engine).also {
                com.google.android.filament.android.TextureHelper.setBitmap(engine, it, 0, bitmap)
                if (mipmaps) it.generateMipmaps(engine)
            }

    private fun applyQuality() {
        shelf.sheen = quality.sheen
        uiHelper.setDesiredSize(quality.resolution * 16 / 9, quality.resolution)  // the display's scaler fills the screen
        msaaOn = null
        if (!quality.showFps) fpsText.value = null
        requestRender()
    }

    /** Sends queued texture uploads to the GPU now, rather than with the next (animated) frame. */
    fun flush() = engine.flush()

    /**
     * Runs [then] once the GPU has finished everything queued so far (texture uploads and their
     * mipmaps), checked once per frame; after [timeoutMs] it runs anyway.
     */
    fun afterGpu(timeoutMs: Long = 800, afterNextFrame: Boolean = false, then: () -> Unit) {
        if (afterNextFrame) { nextFrame += { afterGpu(timeoutMs, false, then) }; requestRender(); return }
        val fence = engine.createFence()
        engine.flush()
        gpuWaits += GpuWait(fence, System.nanoTime(), timeoutMs * 1_000_000, then)
    }
    private class GpuWait(val fence: com.google.android.filament.Fence, val since: Long, val timeout: Long, val then: () -> Unit)
    private val gpuWaits = ArrayList<GpuWait>()
    private val nextFrame = ArrayList<() -> Unit>()
    private fun checkGpuWaits() {
        val now = System.nanoTime()
        val done = gpuWaits.filter { it.fence.wait(com.google.android.filament.Fence.Mode.DONT_FLUSH, 0) == com.google.android.filament.Fence.FenceStatus.CONDITION_SATISFIED || now - it.since > it.timeout }
        gpuWaits -= done.toSet()
        for (w in done) {
            engine.destroyFence(w.fence)
            android.util.Log.i("ShelfLifePerf", "GPU caught up in ${(now - w.since) / 1_000_000} ms")
            w.then()
        }
    }

    // Exactly one frame loop: posting again while one runs would draw every frame twice.
    fun resume() { requestRender(); choreographer.removeFrameCallback(this); choreographer.postFrameCallback(this) }
    fun pause() = choreographer.removeFrameCallback(this)

    fun destroy() {
        pause()
        uiHelper.detach()
        sheenTexture?.let { engine.destroyTexture(it) }
        levelTextures.forEach { it?.let { t -> engine.destroyTexture(t) } }
        engine.destroyTexture(lightmap)
        engine.destroyView(snapView)
        engine.destroyRenderTarget(snapTarget)
        engine.destroyTexture(snapColor)
        engine.destroyTexture(snapDepth)
        assetLoader.destroyAsset(backdrop)
        clip?.layers?.forEach { l -> l.quad?.let { assetLoader.destroyAsset(it) }; l.material?.let { engine.destroyMaterialInstance(it) }; l.frames.forEach { engine.destroyTexture(it.texture) } }
        clipMat?.let { engine.destroyMaterial(it) }
        engine.destroyMaterialInstance(backdropMaterial)
        engine.destroyMaterial(backdropMat)
        worker.shutdown()
        shelf.destroy(assetLoader)
        deck.destroy()
        hand.destroy()
        assetLoader.destroyAsset(room)
        resourceLoader.destroy()
        assetLoader.destroy()
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(camera.entity)
        engine.destroy()
    }
}
