# Bakes the room's lighting for the Android TV app and exports android/app/src/main/assets/room.glb.
# Cycles computes every light, bounce and shadow once; the TV box then just draws one textured mesh.
# Run after build_room.py:
#   Blender --background outputs/LivingRoom.blend --python work/bake_android.py -- [--size 4096] [--samples 256]
import bpy, os, sys, time
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
args = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
def arg(name, default): return int(args[args.index(name) + 1]) if name in args else default
SIZE, SAMPLES = arg('--size', 4096), arg('--samples', 256)
ASSETS = os.path.join(ROOT, 'android/app/src/main/assets')
start = time.time()

scene = bpy.context.scene
scene.render.engine = 'CYCLES'
prefs = bpy.context.preferences.addons['cycles'].preferences
prefs.compute_device_type = 'METAL'; prefs.get_devices()
for d in prefs.devices: d.use = True
scene.cycles.device = 'GPU'
scene.cycles.samples = SAMPLES

# 1. Merge every static mesh (minus the TV screen, which will show video) into one object.
static = [o for o in scene.objects if o.type == 'MESH' and not o.get('render_only') and o.name != 'TVScreen']
bpy.ops.object.select_all(action='DESELECT')
copies = []
for o in static:
    c = o.copy(); c.data = o.data.copy(); scene.collection.objects.link(c); c.select_set(True); copies.append(c)
    o.hide_render = True; o.hide_set(True)  # originals stay out of the bake; the copy stands in
bpy.context.view_layer.objects.active = copies[0]
bpy.ops.object.convert(target='MESH')  # applies bevels etc.
bpy.ops.object.join()
room = bpy.context.view_layer.objects.active
room.name = 'Baked_room'

# 2. A second UV layout where every face gets its own patch of the lightmap.
render_uv = room.data.uv_layers.active
render_uv_name = render_uv.name
lightmap_uv = room.data.uv_layers.new(name='Lightmap')
room.data.uv_layers.active = lightmap_uv
render_uv.active_render = True  # materials keep sampling their own textures
bpy.ops.object.mode_set(mode='EDIT'); bpy.ops.mesh.select_all(action='SELECT')
bpy.ops.uv.smart_project(angle_limit=1.15, island_margin=0.002, scale_to_bounds=False)
bpy.ops.uv.pack_islands(margin=0.002, rotate=True)
bpy.ops.object.mode_set(mode='OBJECT')
print(f'UV done {time.time() - start:.0f}s', flush=True)

# 3. Bake diffuse light + colour + emission (no glossy: reflections depend on where you stand).
image = bpy.data.images.new('RoomLightmap', SIZE, SIZE, float_buffer=True)
for slot in room.material_slots:
    tree = slot.material.node_tree
    node = tree.nodes.new('ShaderNodeTexImage'); node.image = image; tree.nodes.active = node
bake = scene.render.bake
bake.use_pass_direct = bake.use_pass_indirect = bake.use_pass_color = True
bake.use_pass_diffuse = bake.use_pass_emit = bake.use_pass_transmission = True
bake.use_pass_glossy = False
bake.margin = 6
lightmap_path = os.path.join(ROOT, 'work/room_lightmap.jpg')
if '--reuse' in args and os.path.exists(lightmap_path):
    print('Reusing existing bake (UV layout is deterministic)', flush=True)
else:
    bpy.ops.object.select_all(action='DESELECT'); room.select_set(True)
    bpy.ops.object.bake(type='COMBINED')
    print(f'Bake done {time.time() - start:.0f}s', flush=True)
    # 4. Save through the scene's AgX view transform, so the texture already looks like the Blender render.
    scene.render.image_settings.file_format = 'JPEG'; scene.render.image_settings.quality = 90
    image.save_render(lightmap_path, scene=scene)
os.makedirs(ASSETS, exist_ok=True)

# 5. One unlit material showing the baked texture on the lightmap UVs; drop the other UV layer.
baked = bpy.data.materials.new('Baked_room'); baked.use_nodes = True
tree = baked.node_tree; tree.nodes.clear()
uv = tree.nodes.new('ShaderNodeUVMap'); uv.uv_map = 'Lightmap'
tex = tree.nodes.new('ShaderNodeTexImage'); tex.image = bpy.data.images.load(lightmap_path)
bg = tree.nodes.new('ShaderNodeBackground'); out = tree.nodes.new('ShaderNodeOutputMaterial')
tree.links.new(uv.outputs['UV'], tex.inputs['Vector']); tree.links.new(tex.outputs['Color'], bg.inputs['Color'])
tree.links.new(bg.outputs['Background'], out.inputs['Surface'])
room.data.materials.clear(); room.data.materials.append(baked)
for poly in room.data.polygons: poly.material_index = 0
room.data.uv_layers.remove(room.data.uv_layers[render_uv_name])

# 6. Export the baked room plus the TV screen (still a normal material; video replaces it in the app).
bpy.ops.object.select_all(action='DESELECT'); room.select_set(True)
tv = scene.objects.get('TVScreen')
if tv:
    tv.hide_set(False); tv.select_set(True)
    # Unlit + a black placeholder texture: the app paints the room's soft reflection onto the
    # glass at runtime and fades it out as the camera approaches the TV.
    glass = bpy.data.materials.new('TV_glass'); glass.use_nodes = True
    t = glass.node_tree; t.nodes.clear()
    img = bpy.data.images.new('TV_glass_tex', 4, 4); img.pixels.foreach_set([0.0, 0.0, 0.0, 1.0] * 16)
    tex = t.nodes.new('ShaderNodeTexImage'); tex.image = img
    bg = t.nodes.new('ShaderNodeBackground'); out = t.nodes.new('ShaderNodeOutputMaterial')
    t.links.new(tex.outputs['Color'], bg.inputs['Color']); t.links.new(bg.outputs['Background'], out.inputs['Surface'])
    tv.data.materials.clear(); tv.data.materials.append(glass)
path = os.path.join(ASSETS, 'room.glb')
bpy.ops.export_scene.gltf(filepath=path, export_format='GLB', use_selection=True, export_apply=True, export_yup=True,
                          export_lights=False, export_cameras=False, export_image_format='AUTO')
print(f'EXPORTED_BAKED {path} {os.path.getsize(path)} bytes, {SIZE}px {SAMPLES} samples, {time.time() - start:.0f}s', flush=True)
