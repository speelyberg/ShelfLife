package com.shelflife.tv

import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.filament.Colors
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.android.TextureHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.FilamentInstance
import com.google.android.filament.gltfio.ResourceLoader
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.min

/**
 * The tall DVD tower: 8 rows x 18 cases. One case model is instanced 144 times; each case gets
 * its own copy of the sleeve materials so its spine can carry its own film's printing.
 */
class Shelf(private val engine: Engine, private val scene: Scene, loader: AssetLoader, resources: ResourceLoader, caseModel: ByteArray, heroModel: ByteArray, hand: HandMaterials, private val onChange: () -> Unit) {
    companion object { const val ROWS = 8; const val COLUMNS = 18 }

    private inner class Case(val instance: FilamentInstance, val slot: Int) {
        val sleeves = HashMap<String, MaterialInstance>()
        val coverEntities = ArrayList<Int>()  // front + back: only drawn while the case is in your hands
        var movie: Movie? = null
        // Standing on the board: board tops are at 0.0795 + 0.26 per row, the case is 0.19 tall.
        val shelfPose get() = Pose(1.127f + (slot % COLUMNS) * 0.0172f, 0.1745f + (slot / COLUMNS) * 0.26f, 0.274f, Pose.yaw((PI / 2).toFloat()))
        var from = Pose(0f, 0f, 0f, Pose.yaw(0f)); var to = from; var start = 0L; var seconds = 0.4f
        var incoming: Movie? = null   // restocking: the film this slot gets next
        var restockStep = 0           // 0 idle, 1 sliding out, 2 sliding back in
    }

    private val sampler = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
    private val asset: FilamentAsset
    private val cases: List<Case>
    private val textures = HashMap<String, Texture>()
    private val white = texture(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) })
    private var now = 0L

    var focus = 3 * COLUMNS + 8; private set
    /** Called when the highlighted case changes while the shelf is in view (the flick sound). */
    var onFocusMoved: (() -> Unit)? = null
    private var highlightShown = false
    var held: Movie? = null; private set
    private var heldCase: Case? = null
    private var spin = 0f
    private var lastCamera = FloatArray(16)

    // The case in your hands is a separate, detailed, lit model (bevels, seam, latches). Only one
    // exists, so the shelf's 144 cheap boxes keep the TV box fast.
    private val hero: FilamentAsset
    private val heroSleeves = HashMap<String, MaterialInstance>()
    private var heroOf: Case? = null
    private var heroMovie: String? = null  // whose spine the detailed case carries
    private val glossEntities: IntArray
    private val plastic = ArrayList<Pair<Int, MaterialInstance>>()
    private val depthOnly: MaterialInstance?
    private val glossFront: MaterialInstance?
    private val glossBack: MaterialInstance?

    // The detailed case's lid swings open on its spine hinge (local Y axis through the node origin).
    private var heroLid = 0
    private val heroLidBase = FloatArray(16)
    private var lidFrom = 0f; private var lidTo = 0f; private var lidStart = 0L; private var lidSeconds = 0.5f; private var lidAngle = 0f

    init {
        val instances = arrayOfNulls<FilamentInstance>(ROWS * COLUMNS)
        asset = loader.createInstancedAsset(ByteBuffer.allocateDirect(caseModel.size).put(caseModel).flip(), instances)!!
        resources.loadResources(asset)
        asset.releaseSourceData()
        scene.addEntities(asset.entities)
        val rm = engine.renderableManager
        cases = instances.mapIndexed { slot, instance ->
            Case(instance!!, slot).also { case ->
                for (entity in instance.entities) {
                    val name = asset.getName(entity) ?: continue
                    if (!name.startsWith("cover-") || !rm.hasComponent(entity)) continue
                    val ri = rm.getInstance(entity)
                    val own = MaterialInstance.duplicate(rm.getMaterialInstanceAt(ri, 0), "$name-$slot")
                    rm.setMaterialInstanceAt(ri, 0, own)
                    case.sleeves[name] = own
                    if (name != "cover-spine") case.coverEntities += entity
                }
                showCovers(case, false)  // on the shelf only the spine faces you: half the draw calls
                case.from = case.shelfPose; case.to = case.shelfPose
                place(case, case.shelfPose)
            }
        }
        hero = loader.createAsset(ByteBuffer.allocateDirect(heroModel.size).put(heroModel).flip())!!
        resources.loadResources(hero)
        hero.releaseSourceData()
        // Same scene as the room, but drawn after it and the blurred backdrop (channel 2), lit only by
        // the warm key (light channel 1), and on its own layer so the backdrop snapshot leaves it out.
        scene.addEntities(hero.entities)
        for (e in hero.entities) if (rm.hasComponent(e)) rm.getInstance(e).let { ri ->
            rm.setChannel(ri, 2); rm.setLightChannel(ri, 0, false); rm.setLightChannel(ri, 1, true)
        }
        // Its plastic (values from dvd_case_hero.glb's Hero_plastic and Hero_inside).
        for (name in listOf("case-base", "case-lid")) hand.apply(hero.getFirstEntityByName(name), floatArrayOf(0.09985f, 0.09985f, 0.10613f, 1f), 0f, 0.3f)
            ?.let { plastic += hero.getFirstEntityByName(name) to it }
        hand.apply(hero.getFirstEntityByName("case-hub"), floatArrayOf(0.17184f, 0.17184f, 0.18281f, 1f), 0f, 0.45f)
            ?.let { plastic += hero.getFirstEntityByName("case-hub") to it }
        depthOnly = hand.depthOnly
        // Drawn first among what's in your hands: filling depth only (insertMode), it hides only what's drawn after it.
        for ((e, _) in plastic) rm.setPriority(rm.getInstance(e), 0)
        for (entity in hero.entities) {
            val name = hero.getName(entity) ?: continue
            if (name.startsWith("cover-") && rm.hasComponent(entity)) heroSleeves[name] = rm.getMaterialInstanceAt(rm.getInstance(entity), 0)
        }
        glossEntities = listOf("gloss-front", "gloss-back").map { hero.getFirstEntityByName(it) }.filter { it != 0 && rm.hasComponent(it) }.toIntArray()
        fun glossMaterial(name: String) = hero.getFirstEntityByName(name).takeIf { it != 0 && rm.hasComponent(it) }
            ?.let { rm.getMaterialInstanceAt(rm.getInstance(it), 0) }  // gltfio's ubershader always has baseColorUvMatrix
        glossFront = glossMaterial("gloss-front"); glossBack = glossMaterial("gloss-back")
        val band = texture(Printing.glossBand())
        val clamp = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
        listOfNotNull(glossFront, glossBack).forEach { it.setParameter("baseColorMap", band, clamp) }
        if (glossFront == null) android.util.Log.w("ShelfLife", "cover sheen unavailable: no gloss layer in the case model")
        showHero(false)
        heroLid = hero.getFirstEntityByName("case-lid")
        if (heroLid != 0) engine.transformManager.let { tm -> tm.getTransform(tm.getInstance(heroLid), heroLidBase) }
    }

    /** The lid at [angle] this frame: the disc going in drives it from [InsertTimeline]. */
    fun holdLid(angle: Float) { lidStart = 0L; setLid(angle) }

    /**
     * While the baked insert clip shows the case ([clip]): its plastic only fills depth, so the live label
     * and covers still go behind the lid. Baking that clip ([bake]): the plastic alone, covers left out.
     */
    fun insertMode(clip: Boolean, bake: Boolean) {
        val rm = engine.renderableManager
        for ((e, own) in plastic) rm.setMaterialInstanceAt(rm.getInstance(e), 0, if (clip) depthOnly ?: own else own)
        if (bake) for (e in hero.entities) { val n = hero.getName(e) ?: continue; if ((n.startsWith("cover-") || n.startsWith("gloss-")) && rm.hasComponent(e)) rm.setLayerMask(rm.getInstance(e), 0xff, 0) }
        else if (heroOf != null) showHero(true)
    }

    /** Opens the case in your hands (lid swings toward you, about 145°). */
    fun openLid(seconds: Float) = animateLid(-2.5f, seconds)
    fun closeLid(seconds: Float) = animateLid(0f, seconds)
    private fun animateLid(target: Float, seconds: Float) {
        now = System.nanoTime()
        lidFrom = lidAngle; lidTo = target; lidSeconds = seconds; lidStart = now
        onChange()
    }
    private fun setLid(angle: Float) {
        lidAngle = angle
        if (heroLid == 0) return
        val c = kotlin.math.cos(angle); val s = kotlin.math.sin(angle)
        val r = floatArrayOf(c, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, c, 0f, 0f, 0f, 0f, 1f)
        val m = FloatArray(16) { i -> (0 until 4).sumOf { k -> (heroLidBase[k * 4 + i % 4] * r[(i / 4) * 4 + k]).toDouble() }.toFloat() }
        engine.transformManager.let { tm -> tm.setTransform(tm.getInstance(heroLid), m) }
    }

    /** Where the disc sits inside the case in your hands (on the hub, label toward the lid). */
    fun discInCase(): Pose {
        val tm = engine.transformManager
        val w = FloatArray(16).also { tm.getWorldTransform(tm.getInstance(hero.root), it) }
        val z = 0.0022f
        return Pose(w[12] + w[8] * z, w[13] + w[9] * z, w[14] + w[10] * z, Pose.rotation(w), 1f)
    }

    private fun setVisible(entities: IntArray, show: Boolean, layer: Int = 0x1) {
        val rm = engine.renderableManager
        for (e in entities) if (rm.hasComponent(e)) rm.setLayerMask(rm.getInstance(e), 0xff, if (show) layer else 0)
    }
    private fun showHero(show: Boolean) {
        setVisible(hero.entities, show, layer = 0x4)
        setVisible(glossEntities, show && sheen > 0, layer = 0x4)
    }

    // The covers' sheen: a gloss layer over each insert of the case in your hands (not the plastic).
    // Its highlight slides across as the case turns relative to you; [sheen] sets its strength, and
    // at 0 the layers aren't drawn at all.
    var sheen = 2
        set(value) { field = value.coerceIn(0, 3); setVisible(glossEntities, heroOf != null && field > 0, layer = 0x4); lastGloss = Float.NaN; onChange() }
    private var lastGloss = Float.NaN
    private val uvMatrix = FloatArray(9)

    private fun updateGloss() {
        if (heroOf == null || sheen == 0 || glossFront == null || glossBack == null) return
        val tm = engine.transformManager
        val w = FloatArray(16).also { tm.getWorldTransform(tm.getInstance(hero.root), it) }
        val c = lastCamera
        // How far the case has turned from facing you: its right axis against the camera's backward axis
        // (0 square on, ±1 edge on), so where the case sits on screen doesn't move the streak.
        val p = (w[0] * c[8] + w[1] * c[9] + w[2] * c[10]).coerceIn(-1f, 1f)
        if (kotlin.math.abs(p - lastGloss) < 0.002f) return
        lastGloss = p
        val strength = floatArrayOf(0f, 0.3f, 0.55f, 0.85f)[sheen]  // peak opacity ≈ 0.2 / 0.4 / 0.6
        // u' = u/2 + offset over a texture twice the cover's width: offset 0.25 centres the streak.
        fun set(m: MaterialInstance, offset: Float) {
            // The offset goes in both slots a translation can live in, so it holds whichever way round the
            // shader multiplies (M·uv or uv·M); the other slot then only touches the unused third component.
            uvMatrix[0] = 0.5f; uvMatrix[4] = 1f; uvMatrix[8] = 1f; uvMatrix[2] = offset; uvMatrix[6] = offset
            m.setParameter("baseColorUvMatrix", MaterialInstance.FloatElement.MAT3, uvMatrix, 0, 1)
            m.setParameter("baseColorFactor", Colors.RgbaType.LINEAR, 1f, 1f, 1f, strength)
        }
        // Square on, the streak crosses the cover a third of the way in; turning sweeps it across and off.
        set(glossFront, 0.33f + 0.9f * p)
        set(glossBack, 0.33f - 0.9f * p)
    }

    /**
     * Fills every slot, repeating titles when the library is smaller than the shelf. With [print]
     * false (a Plex shelf, whose spines are printed off the main thread and arrive via [setSpine])
     * each spine is a flat colour until its own arrives.
     */
    fun stock(movies: List<Movie>, print: Boolean = true) {
        if (movies.isEmpty()) return
        if (restocking) finishRestockNow()
        // Nothing may stay bound to a destroyed texture (Filament aborts): the detailed case lets go first.
        heroSleeves.values.forEach { it.setParameter("baseColorMap", white, sampler) }
        heroMovie = null
        textures.values.forEach { if (it !== white) engine.destroyTexture(it) }
        textures.clear()
        for (case in cases) {
            val movie = movies[case.slot % movies.size]
            case.movie = movie
            case.sleeves["cover-spine"]?.apply {
                if (print) {
                    setParameter("baseColorMap", textures.getOrPut(movie.id + "-spine") { texture(Printing.spine(movie)) }, sampler)
                    setParameter("baseColorFactor", Colors.RgbaType.LINEAR, 1f, 1f, 1f, 1f)
                } else {
                    val c = movie.color
                    setParameter("baseColorMap", white, sampler)
                    setParameter("baseColorFactor", Colors.RgbaType.LINEAR, Color.red(c) / 255f * 0.45f, Color.green(c) / 255f * 0.45f, Color.blue(c) / 255f * 0.45f, 1f)
                }
            }
            plainSleeves(case)
        }
        if (highlightShown) { dressCovers(cases[focus]); animate(cases[focus], pulled(cases[focus]), 0.18f) }
        onChange()
    }

    // --- Restocking (shuffle with the room in view) ---
    //
    // Every slot gets its new film in turn, in random order across the whole shelf: the case slides out
    // toward you, takes the new spine at the far point, and slides back in. All the new spines are
    // uploaded first, with the shelf standing still: on the Onn's GPU an upload during the animation
    // froze it for up to 0.6 s at a time.
    private val retiring = HashMap<String, Texture>()
    private var restockLeft = 0
    private val restockRandom = java.util.Random()
    val restocking get() = restockLeft > 0

    fun beginRestock(movies: List<Movie>) {
        if (movies.isEmpty()) return
        if (restocking) finishRestockNow()
        // Old textures stay alive on the cases still showing them: each old spine is freed as its film's
        // last case swaps (freeing all ~150 at the end stalled the GPU driver), the rest at the end.
        heroSleeves.values.forEach { it.setParameter("baseColorMap", white, sampler) }
        heroMovie = null
        retiring += textures.filterValues { it !== white }
        textures.clear()
        for (case in cases) { plainSleeves(case); case.incoming = movies[case.slot % movies.size]; case.restockStep = 0 }
        restockLeft = cases.size
        onChange()
    }

    /** Out of its slot and tipped toward you, top first, lifted clear of the board: from across the room a
     *  straight pull toward the camera doesn't show, a tip does. */
    private fun outPose(case: Case) = case.shelfPose.let { it.copy(y = it.y + 0.02f, z = it.z + 0.11f, q = Pose.multiply(Pose.pitch(-0.4f), it.q)) }

    /** While a case is out of the shelf its sides show: its insert, in the film's main cover colour. */
    private fun coverColour(case: Case, movie: Movie?) {
        val c = movie?.let { m -> Printing.palettes[m.id]?.let { Printing.mainColor(it) } ?: m.color } ?: return
        for (name in listOf("cover-front", "cover-back")) case.sleeves[name]?.apply {
            setParameter("baseColorMap", white, sampler)
            setParameter("baseColorFactor", Colors.RgbaType.LINEAR, Color.red(c) / 255f * 0.8f, Color.green(c) / 255f * 0.8f, Color.blue(c) / 255f * 0.8f, 1f)
        }
    }

    /** Out it slides (after a random wait), to swap at the far point. */
    private fun startSwap(case: Case) {
        if (case.restockStep != 0 || case.incoming == null) return
        case.restockStep = 1
        coverColour(case, case.movie)
        showCovers(case, true)
        animate(case, outPose(case), 0.24f, delay = restockRandom.nextFloat() * 1.5f)
    }

    private fun swapAtFarPoint(case: Case) {
        val movie = case.incoming ?: return
        val oldId = case.movie?.id
        case.movie = movie; case.incoming = null
        case.sleeves["cover-spine"]?.apply {
            setParameter("baseColorMap", textures[movie.id + "-spine"] ?: white, sampler)
            setParameter("baseColorFactor", Colors.RgbaType.LINEAR, 1f, 1f, 1f, 1f)
        }
        if (oldId != null && cases.none { it.movie?.id == oldId }) retiring.remove("$oldId-spine")?.let { engine.destroyTexture(it) }
        coverColour(case, movie)
        case.restockStep = 2
        animate(case, case.shelfPose, 0.32f)
    }

    private fun swapLanded(case: Case) {
        case.restockStep = 0
        showCovers(case, false); plainSleeves(case)  // back in the shelf, only the spine shows
        if (--restockLeft == 0) {
            if (retiring.isNotEmpty()) android.util.Log.i("ShelfLifePerf", "restock landed: freeing the last ${retiring.size} old textures")
            retiring.values.forEach { engine.destroyTexture(it) }; retiring.clear()
            if (highlightShown && held == null) dressCovers(cases[focus])
        }
    }

    /** Every new spine is uploaded (a failed one swaps in plain): the cases restock. */
    fun flushRestock() {
        if (!restocking) return
        android.util.Log.i("ShelfLifePerf", "restock: spines uploaded, cases move")
        for (case in cases) if (case.incoming != null && case.restockStep == 0) startSwap(case)
    }

    /** Ends a restock at once (a new one starting, or the app leaving the room): every case swaps in place. */
    fun finishRestockNow() {
        for (case in cases) {
            if (case.incoming != null) swapAtFarPoint(case)
            case.start = 0L; case.restockStep = 0; place(case, case.shelfPose); case.from = case.shelfPose; case.to = case.shelfPose
            showCovers(case, false); plainSleeves(case)
        }
        restockLeft = 0
        retiring.values.forEach { engine.destroyTexture(it) }; retiring.clear()
        onChange()
    }

    /** Reprints one film's spine (every case of it) once its poster colours arrive. */
    fun setSpine(movieId: String, mips: Printing.Mips) {
        val key = "$movieId-spine"
        val old = textures[key]
        val texture = spineTexture(mips)
        textures[key] = texture
        // Every case of this film takes the new spine and goes plain (it may still hold the shelf
        // sleeves destroyed below); the picked-out one is dressed again from the poster palette.
        for (case in cases) if (case.movie?.id == movieId) {
            case.sleeves["cover-spine"]?.apply {
                setParameter("baseColorMap", texture, sampler)
                setParameter("baseColorFactor", Colors.RgbaType.LINEAR, 1f, 1f, 1f, 1f)
            }
            plainSleeves(case)
        }
        if (heroMovie == movieId) heroSleeves["cover-spine"]?.setParameter("baseColorMap", texture, sampler)
        listOf("-shelf-front", "-shelf-back").forEach { k -> textures.remove(movieId + k)?.let { engine.destroyTexture(it) } }
        if (cases[focus].movie?.id == movieId && highlightShown && held == null) dressCovers(cases[focus])
        // A restock's uploads happen with the shelf standing still: the driver is only kicked, not a frame
        // drawn, so the GPU spends that time on the uploads (the cases wait on them).
        if (restocking) engine.flush() else onChange()
        old?.let { engine.destroyTexture(it) }
    }

    /** Puts the real poster on the front of the case you're holding. */
    fun setFront(movieId: String, bitmap: Bitmap) = setHeroSleeve("cover-front", "$movieId-poster", movieId, bitmap)
    /** The back: a blurred, lightened wash of the poster's colours behind the synopsis. */
    fun setBack(movieId: String, bitmap: Bitmap) = setHeroSleeve("cover-back", "$movieId-poster-back", movieId, bitmap)

    private fun setHeroSleeve(sleeve: String, key: String, movieId: String, bitmap: Bitmap) {
        textures[key]?.let { engine.destroyTexture(it) }
        val texture = texture(bitmap)
        textures[key] = texture
        if (held?.id == movieId) heroSleeves[sleeve]?.setParameter("baseColorMap", texture, sampler)
        onChange()
    }

    fun hasPoster(movieId: String) = textures.containsKey("$movieId-poster")

    /** The detailed case in front of the camera with blank sleeves, for [RoomRenderer.warming]. */
    fun warmUp(on: Boolean) {
        if (held != null) return
        heroSleeves.values.forEach { it.setParameter("baseColorMap", white, sampler) }
        heroMovie = null
        showHero(on)
        if (on) engine.transformManager.let { tm -> tm.setTransform(tm.getInstance(hero.root), handPose().matrix()) }
        onChange()
    }

    /** Slides the highlighted case home (leaving the shelf) or back out (returning). */
    fun showHighlight(show: Boolean) {
        highlightShown = show
        if (held != null) return
        if (show) dressCovers(cases[focus]) else showCovers(cases[focus], false)
        animate(cases[focus], if (show) pulled(cases[focus]) else cases[focus].shelfPose, 0.25f)
    }

    val focused: Movie? get() = cases[focus].movie

    /**
     * Puts [movie] in the highlighted slot (a film picked from search), with its spine if the shelf has one
     * printed, else a flat colour until [setSpine] brings it. Returns false if it needs printing.
     */
    fun replaceFocused(movie: Movie): Boolean {
        if (held != null) return true
        val case = cases[focus]
        if (restocking) finishRestockNow()
        case.movie = movie
        val spine = textures["${movie.id}-spine"]
        case.sleeves["cover-spine"]?.apply {
            if (spine != null) {
                setParameter("baseColorMap", spine, sampler)
                setParameter("baseColorFactor", Colors.RgbaType.LINEAR, 1f, 1f, 1f, 1f)
            } else {
                val c = movie.color
                setParameter("baseColorMap", white, sampler)
                setParameter("baseColorFactor", Colors.RgbaType.LINEAR, Color.red(c) / 255f * 0.45f, Color.green(c) / 255f * 0.45f, Color.blue(c) / 255f * 0.45f, 1f)
            }
        }
        plainSleeves(case)
        if (highlightShown) dressCovers(case)
        onChange()
        return spine != null
    }
    /** The films on the shelf in slot order, each once (the shelf repeats titles when the library is small). */
    fun movies(): List<Movie> = cases.mapNotNull { it.movie }.distinctBy { it.id }
    val focusRow get() = focus / COLUMNS

    fun moveFocus(step: Int) {
        val row = focus / COLUMNS
        setFocus(row * COLUMNS + ((focus % COLUMNS + step + COLUMNS) % COLUMNS))
    }
    fun focusRow(row: Int) = setFocus(row * COLUMNS + focus % COLUMNS)

    /** The case that was picked out when the app last stopped. */
    fun restoreFocus(slot: Int) = setFocus(slot.coerceIn(0, ROWS * COLUMNS - 1))

    private fun setFocus(slot: Int) {
        if (held != null || slot == focus) return
        animate(cases[focus], cases[focus].shelfPose, 0.18f)
        showCovers(cases[focus], false)
        focus = slot
        if (highlightShown) { dressCovers(cases[focus]); animate(cases[focus], pulled(cases[focus]), 0.18f); onFocusMoved?.invoke() }
        onChange()
    }

    /**
     * The picked-out case sticks out far enough to show its sleeve, so it gets the soft colour-field
     * covers (poster palette, like the spine). Every other shelf case keeps its covers hidden.
     */
    private fun dressCovers(case: Case) {
        val movie = case.movie ?: return
        val front = textures.getOrPut(movie.id + "-shelf-front") { texture(Printing.shelfCover(movie)) }
        val back = textures.getOrPut(movie.id + "-shelf-back") { texture(Printing.shelfCover(movie, flip = true)) }
        case.sleeves["cover-front"]?.apply { setParameter("baseColorMap", front, sampler); setParameter("baseColorFactor", Colors.RgbaType.LINEAR, 1f, 1f, 1f, 1f) }
        case.sleeves["cover-back"]?.apply { setParameter("baseColorMap", back, sampler); setParameter("baseColorFactor", Colors.RgbaType.LINEAR, 1f, 1f, 1f, 1f) }
        showCovers(case, true)
    }
    /** The highlighted case is tipped toward you and lifted, like a finger pulling it from the shelf. */
    private fun pulled(case: Case) = case.shelfPose.let {
        it.copy(y = it.y + 0.012f, z = it.z + 0.045f, q = Pose.multiply(Pose.pitch(-0.22f), it.q))
    }

    /** Lifts the highlighted case off the shelf into your hands, printed front and back ([fly] false: swap only, see [flyToHand]). */
    fun pickUp(seconds: Float = 0.6f, fly: Boolean = true) {
        val case = cases[focus]
        val movie = case.movie ?: return
        held = movie; heldCase = case; spin = 0f
        // Swap the shelf's cheap box for the detailed case, drawn in its own unblurred pass.
        heroOf = case
        setVisible(case.instance.entities, false)
        showHero(true)
        heroSleeves["cover-spine"]?.setParameter("baseColorMap", textures[movie.id + "-spine"] ?: white, sampler)
        heroMovie = movie.id
        heroSleeves["cover-front"]?.let { front ->
            val poster = textures[movie.id + "-poster"]
            if (poster != null) front.setParameter("baseColorMap", poster, sampler) else printed(front, movie.id + "-front") { Printing.front(movie) }
        }
        heroSleeves["cover-back"]?.let { back ->
            val printedBack = textures[movie.id + "-poster-back"]
            if (printedBack != null) back.setParameter("baseColorMap", printedBack, sampler) else printed(back, movie.id + "-back") { Printing.back(movie) }
        }
        place(case, case.to)  // put the detailed case where the shelf box is before it flies
        if (fly) animate(case, handPose(), seconds)
    }
    fun flyToHand(seconds: Float = 0.6f) { heldCase?.let { animate(it, handPose(), seconds) } }
    /** ◀ ▶ while holding: turn the case over. */
    fun turn(step: Int) {
        val case = heldCase ?: return
        spin += step * PI.toFloat()
        animate(case, handPose(), 0.55f)
    }
    /**
     * Back to the shelf. The detailed case flies home (drawn over the fading blur, which would hide the
     * shelf's own box) and swaps for the shelf box when it lands.
     */
    fun putBack() {
        val case = heldCase ?: return
        held = null; heldCase = null
        returning = case
        lidStart = 0L; setLid(0f)  // next time it comes off the shelf closed
        animate(case, pulled(case), 0.5f)
    }
    private var returning: Case? = null
    private fun landed(case: Case) {
        returning = null
        if (heroOf !== case || held != null) return
        heroOf = null; showHero(false)
        setVisible(case.instance.entities, true)
        if (highlightShown && case === cases[focus]) dressCovers(case) else showCovers(case, false)  // back on the shelf, still picked out
    }

    /** The shelf box of the picked-out case: left out of the blur's snapshot when it's about to be lifted. */
    fun focusedCaseEntities(): IntArray = cases[focus].instance.entities
    val showingBack get() = Math.floorMod((spin / PI.toFloat()).toInt(), 2) == 1

    /** Called every frame with the camera's model matrix. */
    val animating get() = lidStart != 0L || cases.any { it.start != 0L }

    fun update(frameNanos: Long, camera: FloatArray) {
        now = frameNanos
        lastCamera = camera
        updateGloss()
        if (lidStart != 0L) {
            val t = min(1f, (frameNanos - lidStart) / 1e9f / lidSeconds)
            setLid(lidFrom + (lidTo - lidFrom) * (t * t * (3 - 2 * t)))
            if (t >= 1f) lidStart = 0L
        }
        for (case in cases) {
            if (case.start == 0L) continue
            val t = ((frameNanos - case.start) / 1e9f / case.seconds).coerceIn(0f, 1f)  // before a delayed start: stays put
            val e = t * t * (3 - 2 * t)
            place(case, case.from.mix(case.to, e))
            if (t >= 1f) {
                case.start = 0L
                if (case === returning) landed(case)
                when (case.restockStep) { 1 -> swapAtFarPoint(case); 2 -> swapLanded(case) }
            }
        }
    }

    /** A pose fixed relative to the camera (camera space: x right, z toward the viewer). */
    fun inFrontOfCamera(lx: Float, ly: Float, lz: Float, scale: Float, q: FloatArray = Pose.yaw(0f)): Pose {
        val c = lastCamera
        return Pose(c[0] * lx + c[4] * ly + c[8] * lz + c[12], c[1] * lx + c[5] * ly + c[9] * lz + c[13], c[2] * lx + c[6] * ly + c[10] * lz + c[14],
            Pose.multiply(Pose.rotation(c), q), scale)
    }

    private fun handPose(): Pose {
        // 0.3 m in front of the camera (the shelf view stands 0.5 m back), up and left of centre.
        val c = lastCamera
        val lx = -0.11f; val ly = 0.012f; val lz = -0.3f
        val x = c[0] * lx + c[4] * ly + c[8] * lz + c[12]
        val y = c[1] * lx + c[5] * ly + c[9] * lz + c[13]
        val z = c[2] * lx + c[6] * ly + c[10] * lz + c[14]
        return Pose(x, y, z, Pose.multiply(Pose.rotation(c), Pose.yaw(-0.12f + spin)), 1f)
    }

    private fun animate(case: Case, target: Pose, seconds: Float, delay: Float = 0f) {
        if (now == 0L) { case.from = target; case.to = target; place(case, target); return }  // before the first frame
        now = System.nanoTime()  // frames may have been idle; time the animation from this moment
        case.from = if (case.start != 0L) case.from.mix(case.to, ((now - case.start) / 1e9f / case.seconds).coerceIn(0f, 1f)) else case.to
        case.to = target; case.seconds = seconds; case.start = now + (delay * 1e9f).toLong()
    }

    private fun showCovers(case: Case, show: Boolean) {
        val rm = engine.renderableManager
        for (e in case.coverEntities) rm.setLayerMask(rm.getInstance(e), 0xff, if (show) 0x1 else 0)
    }

    private fun place(case: Case, pose: Pose) {
        val tm = engine.transformManager
        val m = pose.matrix()
        tm.setTransform(tm.getInstance(case.instance.root), m)
        if (case === heroOf) tm.setTransform(tm.getInstance(hero.root), m)
    }

    private fun plainSleeves(case: Case) {
        val movie = case.movie ?: return
        val c = movie.color
        for (name in listOf("cover-front", "cover-back")) case.sleeves[name]?.apply {
            setParameter("baseColorMap", white, sampler)
            setParameter("baseColorFactor", Colors.RgbaType.LINEAR, Color.red(c) / 255f * 0.55f, Color.green(c) / 255f * 0.55f, Color.blue(c) / 255f * 0.55f, 1f)
        }
    }

    private fun printed(material: MaterialInstance, key: String, draw: () -> Bitmap) {
        material.setParameter("baseColorMap", textures.getOrPut(key) { texture(draw()) }, sampler)
        material.setParameter("baseColorFactor", Colors.RgbaType.LINEAR, 1f, 1f, 1f, 1f)
    }

    private fun texture(bitmap: Bitmap): Texture {
        val texture = Texture.Builder().width(bitmap.width).height(bitmap.height).levels(0xff)
            .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.RGBA8)
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE).build(engine)
        TextureHelper.setBitmap(engine, texture, 0, bitmap)
        texture.generateMipmaps(engine)
        return texture
    }

    /** A spine with its mip levels made on the CPU ([Printing.mips], cached raw): the Mali GPU makes each
     *  level in a render pass of its own (ten per 64×900 spine), which froze the shuffle. */
    private fun spineTexture(mips: Printing.Mips): Texture {
        val texture = Texture.Builder().width(mips.width).height(mips.height).levels(0xff)
            .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.RGBA8).build(engine)
        var w = mips.width; var h = mips.height; var offset = 0
        for (i in 0 until texture.levels) {
            val level = mips.data.duplicate().apply { position(offset); limit(offset + w * h * 4) }.slice()
            texture.setImage(engine, i, Texture.PixelBufferDescriptor(level, Texture.Format.RGBA, Texture.Type.UBYTE))
            offset += w * h * 4; w = maxOf(1, w / 2); h = maxOf(1, h / 2)
        }
        return texture
    }

    fun destroy(loader: AssetLoader) {
        textures.values.forEach { engine.destroyTexture(it) }
        engine.destroyTexture(white)
        loader.destroyAsset(asset)
        loader.destroyAsset(hero)
        cases.forEach { c -> c.sleeves.values.forEach { engine.destroyMaterialInstance(it) } }
    }
}
