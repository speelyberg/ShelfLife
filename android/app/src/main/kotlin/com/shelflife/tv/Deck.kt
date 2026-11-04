package com.shelflife.tv

import android.graphics.Bitmap
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.android.TextureHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.min

/** The disc and the DVD player's tray at the left end of the TV console. */
class Deck(private val engine: Engine, private val scene: Scene, private val loader: AssetLoader, resources: ResourceLoader, discModel: ByteArray, trayModel: ByteArray, hand: HandMaterials) {
    companion object {
        val trayClosed = Pose(-0.817f, 0.662f, 0.49f, Pose.yaw(0f))
        val trayOpen = trayClosed.copy(z = 0.64f)
        val hover = Pose(-0.817f, 0.75f, 0.64f, Pose.pitch((-PI / 2).toFloat()))  // flat, label up
        val onTray = hover.copy(y = 0.6655f)
        val inside = onTray.copy(z = 0.49f)
        private val away = Pose(0f, -20f, 0f, Pose.yaw(0f))  // parked out of sight
    }

    private class Prop(val asset: FilamentAsset) {
        var from = away; var to = away; var start = 0L; var seconds = 0.5f
    }

    private val disc = load(discModel, resources)
    private val tray = load(trayModel, resources).also { place(it, trayClosed); it.from = trayClosed; it.to = trayClosed }
    private val props = arrayOf(disc, tray)
    // Values from disc.glb's materials. The label and rainbow are drawn unlit, at the 55% brightness the
    // light gave them when they were lit (measured against recordings on the Onn).
    private val layerShade = floatArrayOf(0.55f, 0.55f, 0.55f, 1f)
    private val label: MaterialInstance? = hand.apply(disc.asset.getFirstEntityByName("disc-label"), layerShade, 0.35f, 0.28f, layered = true)
    init { hand.apply(disc.asset.getFirstEntityByName("disc-body"), floatArrayOf(0.90633f, 0.91627f, 0.93574f, 1f), 0.9f, 0.18f) }
    private var labelTexture: Texture? = null

    // The rainbow: a transparent layer over the label whose bands turn with the angle you see the disc
    // from, the way diffraction on a real disc shifts as it moves.
    private val sheenEntity = disc.asset.getFirstEntityByName("disc-sheen")
    private val sheenBase = FloatArray(16).also { if (sheenEntity != 0) engine.transformManager.let { tm -> tm.getTransform(tm.getInstance(sheenEntity), it) } }
    private var sheenTexture: Texture? = null
    private var lastSheen = Float.NaN

    init {
        // Draw order for the two see-through layers: rainbow first, printed label over it.
        val rm0 = engine.renderableManager
        if (sheenEntity != 0) rm0.setPriority(rm0.getInstance(sheenEntity), 3)
        disc.asset.getFirstEntityByName("disc-label").takeIf { it != 0 }?.let { rm0.setPriority(rm0.getInstance(it), 6) }
        if (sheenEntity != 0) {
            val sheen = hand.apply(sheenEntity, layerShade, 0f, 0.2f, layered = true)!!
            val bmp = Printing.discSheen()
            val t = Texture.Builder().width(bmp.width).height(bmp.height).levels(0xff).sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.RGBA8).usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE).build(engine)
            TextureHelper.setBitmap(engine, t, 0, bmp); t.generateMipmaps(engine)
            sheen.setParameter("baseColorMap", t, TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE))
            sheenTexture = t
        }
    }

    /** Turns the rainbow to suit where the camera is relative to the disc. */
    private fun updateSheen(camera: FloatArray) {
        if (sheenEntity == 0 || disc.to === away && disc.start == 0L) return
        val tm = engine.transformManager
        val world = FloatArray(16).also { tm.getWorldTransform(tm.getInstance(disc.asset.root), it) }
        // Camera position in the disc's own frame (rigid transform: inverse = transpose of rotation).
        val dx = camera[12] - world[12]; val dy = camera[13] - world[13]; val dz = camera[14] - world[14]
        val lx = dx * world[0] + dy * world[1] + dz * world[2]
        val ly = dx * world[4] + dy * world[5] + dz * world[6]
        val lz = dx * world[8] + dy * world[9] + dz * world[10]
        val tilt = kotlin.math.atan2(kotlin.math.sqrt(lx * lx + ly * ly), lz)   // 0 = looking straight at it
        val angle = kotlin.math.atan2(ly, lx) + tilt * 3f                       // bands sweep as it tips
        if (kotlin.math.abs(angle - lastSheen) < 0.002f) return
        lastSheen = angle
        val c = kotlin.math.cos(angle); val s = kotlin.math.sin(angle)
        val r = floatArrayOf(c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        // Spin about the disc's face normal (+Z in the disc's frame), keeping the layer's own placement.
        tm.setTransform(tm.getInstance(sheenEntity), multiply(r, sheenBase))
    }

    private fun multiply(a: FloatArray, b: FloatArray) = FloatArray(16) { i ->
        val col = i / 4; val row = i % 4
        (0 until 4).sumOf { k -> (a[k * 4 + row] * b[col * 4 + k]).toDouble() }.toFloat()
    }
    private var now = 0L

    private fun load(bytes: ByteArray, resources: ResourceLoader): Prop {
        val asset = loader.createAsset(ByteBuffer.allocateDirect(bytes.size).put(bytes).flip())!!
        resources.loadResources(asset)
        asset.releaseSourceData()
        scene.addEntities(asset.entities)
        return Prop(asset).also { place(it, away) }
    }

    fun printLabel(bitmap: Bitmap) {
        val texture = Texture.Builder().width(bitmap.width).height(bitmap.height).levels(0xff)
            .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.RGBA8)
            .usage(Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE).build(engine)
        TextureHelper.setBitmap(engine, texture, 0, bitmap)
        texture.generateMipmaps(engine)
        label?.setParameter("baseColorMap", texture, TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE))
        labelTexture?.let { engine.destroyTexture(it) }
        labelTexture = texture
    }

    fun showDisc(at: Pose) { disc.start = 0L; disc.from = at; disc.to = at; place(disc, at) }
    fun moveDisc(to: Pose, seconds: Float) = animate(disc, to, seconds)
    fun hideDisc() = showDisc(away)

    /** While the disc is with the case in your hands it draws, and is lit, like the case (see Shelf's hero). */
    private var held = false
    fun inHand(on: Boolean) {
        held = on
        val rm = engine.renderableManager
        for (e in disc.asset.entities) if (rm.hasComponent(e)) rm.getInstance(e).let { ri ->
            rm.setChannel(ri, if (on) 2 else 0)
            rm.setLightChannel(ri, 0, !on); rm.setLightChannel(ri, 1, on)
            rm.setLayerMask(ri, 0xff, if (on) 0x4 else 0x1)
        }
    }
    fun moveTray(to: Pose, seconds: Float) = animate(tray, to, seconds)

    /**
     * Which of the disc's layers draw: while the baked insert clip shows the silver and rainbow, only the
     * live label ([label] true, [body] false); baking that clip, the reverse. Both true: the whole disc.
     */
    fun discLayers(body: Boolean, label: Boolean) {
        val rm = engine.renderableManager
        val labelEntity = disc.asset.getFirstEntityByName("disc-label")
        for (e in disc.asset.entities) if (rm.hasComponent(e)) rm.getInstance(e).let { ri ->
            val on = if (e == labelEntity) label else body
            rm.setLayerMask(ri, 0xff, if (!on) 0 else if (held) 0x4 else 0x1)
        }
    }

    val animating get() = disc.start != 0L || tray.start != 0L

    fun update(frameNanos: Long, camera: FloatArray) {
        updateSheen(camera)
        now = frameNanos
        for (p in props) {
            if (p.start == 0L) continue
            val t = min(1f, (frameNanos - p.start) / 1e9f / p.seconds)
            place(p, p.from.mix(p.to, t * t * (3 - 2 * t)))
            if (t >= 1f) p.start = 0L
        }
    }

    private fun animate(p: Prop, target: Pose, seconds: Float) {
        now = System.nanoTime()  // frames may have been idle; time the animation from this moment
        p.from = if (p.start != 0L) p.from.mix(p.to, min(1f, (now - p.start) / 1e9f / p.seconds)) else p.to
        p.to = target; p.seconds = seconds; p.start = now
    }

    private fun place(p: Prop, pose: Pose) {
        val tm = engine.transformManager
        tm.setTransform(tm.getInstance(p.asset.root), pose.matrix())
    }

    fun destroy() {
        labelTexture?.let { engine.destroyTexture(it) }
        sheenTexture?.let { engine.destroyTexture(it) }
        loader.destroyAsset(disc.asset)
        loader.destroyAsset(tray.asset)
    }
}
