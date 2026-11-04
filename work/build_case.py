# Builds the DVD case for the Android app: android/app/src/main/assets/dvd_case.glb
# Run: Blender --background --python work/build_case.py
# Blender Z-up; the glTF exporter maps (x, y, z) -> (x, z, -y), so in the app the case is
# 0.135 wide (X) x 0.190 tall (Y) x 0.014 deep (Z), front cover toward +Z, spine toward -X.
import bpy, bmesh, mathutils, os, math, numpy as np
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
bpy.ops.wm.read_factory_settings(use_empty=True)

def material(name, color, roughness, textured=False):
    """Unlit (KHR_materials_unlit): the TV box shows colours as-is, no per-pixel lighting maths."""
    m = bpy.data.materials.new(name); m.use_nodes = True
    tree = m.node_tree; tree.nodes.clear()
    bg = tree.nodes.new('ShaderNodeBackground'); bg.inputs['Color'].default_value = (*color, 1)
    out = tree.nodes.new('ShaderNodeOutputMaterial'); tree.links.new(bg.outputs['Background'], out.inputs['Surface'])
    if textured:
        # White placeholder; the app swaps in each film's printed artwork at runtime.
        img = bpy.data.images.new(name + '_tex', 4, 4); img.pixels.foreach_set(np.ones(64, dtype=np.float32))
        n = tree.nodes.new('ShaderNodeTexImage'); n.image = img
        tree.links.new(n.outputs['Color'], bg.inputs['Color'])
    return m


# --- Rounded case geometry, shared by the shelf case and the one in your hands ---
# Real Amaray cases: corners rounded ~3 mm seen from the front, the spine rounded ~2.2 mm into the
# front and back, every other edge eased ~1.2 mm. The insert wraps the rounded spine.
CW, CH, CD = 0.135, 0.190, 0.014          # case width (x), height (z), depth (y; front is -y)
SPINE_R, EDGE_R, CORNER_R = 0.0022, 0.0012, 0.003
PROUD = 0.00012                           # inserts sit just proud of the plastic
INSERT_H = CH - 2 * CORNER_R              # the spine strip stops where the corners start curving away

def rounded_block(name, y0, y1, material, segments, outer_ys):
    """A CW x (y1-y0) x CH block with its edges rounded by bevel weight: depth edges (front-view
    corners) CORNER_R, the spine's long edges SPINE_R, other edges EDGE_R. Faces at y in outer_ys
    are the case's outside; edges on any other face (a parting face) stay sharp."""
    bpy.ops.mesh.primitive_cube_add(size=1, location=(0, (y0 + y1) / 2, 0))
    o = bpy.context.object; o.name = name
    o.scale = (CW, y1 - y0, CH); bpy.ops.object.transform_apply(location=True, scale=True)
    bm = bmesh.new(); bm.from_mesh(o.data)
    w = bm.edges.layers.float.get('bevel_weight_edge') or bm.edges.layers.float.new('bevel_weight_edge')
    for e in bm.edges:
        a, b = e.verts[0].co, e.verts[1].co
        if abs(b.y - a.y) > 1e-7:
            e[w] = CORNER_R / CORNER_R                      # front-view corner
        elif any(abs(a.y - oy) < 1e-7 for oy in outer_ys):
            e[w] = (SPINE_R if abs(b.z - a.z) > 1e-7 and a.x < 0 else EDGE_R) / CORNER_R
        else:
            e[w] = 0.0                                      # parting face: sharp
    bm.to_mesh(o.data); bm.free()
    m = o.modifiers.new('round', 'BEVEL'); m.width = CORNER_R; m.segments = segments
    m.limit_method = 'WEIGHT'; m.offset_type = 'OFFSET'; m.profile = 0.5
    bpy.context.view_layer.objects.active = o
    bpy.ops.object.modifier_apply(modifier='round')
    bpy.ops.object.shade_smooth_by_angle(angle=math.radians(40))
    o.data.materials.append(material)
    return o

def mesh_object(name, verts, faces, uvs, colors, material, facing):
    """A mesh from flat lists; [uvs]/[colors] per face corner. Faces are flipped to point along [facing]."""
    me = bpy.data.meshes.new(name); me.from_pydata(verts, [], faces); me.update()
    uv = me.uv_layers.new(name='UVMap')
    col = me.color_attributes.new(name='Col', type='FLOAT_COLOR', domain='CORNER') if colors else None
    li = 0
    for poly in me.polygons:
        for k, loop in enumerate(poly.loop_indices):
            uv.data[loop].uv = uvs[poly.index][k]
            if col: col.data[loop].color = (*([colors[poly.index][k]] * 3), 1.0)
    o = bpy.data.objects.new(name, me); bpy.context.collection.objects.link(o)
    # Single-sided materials cull back faces: every face must point out of the case.
    bm = bmesh.new(); bm.from_mesh(me)
    wrong = [f for f in bm.faces if f.normal.dot(mathutils.Vector(facing)) < 0]
    if wrong: bmesh.ops.reverse_faces(bm, faces=wrong, flip_multires=False); bm.to_mesh(me)
    bm.free()
    print('MESH', name, len(me.polygons), 'faces', 'flipped' if wrong else 'ok', flush=True)
    o.data.materials.append(material)
    return o

def spine_strip(name, segments, material):
    """The insert's spine: flat across the spine and round over both rounded edges, u from the back
    edge (0) to the front (1), v up. Shaded by vertex colour (lighter where it faces you), so the
    curve reads even on the unlit shelf cases."""
    x0 = -CW / 2; r = SPINE_R + PROUD
    cb = (x0 + SPINE_R, CD / 2 - SPINE_R); cf = (x0 + SPINE_R, -(CD / 2 - SPINE_R))
    pts = []
    for i in range(segments + 1):                            # back round: 90 -> 180 degrees
        a = math.radians(90 + 90 * i / segments); pts.append((cb[0] + r * math.cos(a), cb[1] + r * math.sin(a), math.cos(a)))
    for i in range(segments + 1):                            # front round: 180 -> 270 degrees
        a = math.radians(180 + 90 * i / segments); pts.append((cf[0] + r * math.cos(a), cf[1] + r * math.sin(a), math.cos(a)))
    lengths = [0.0]
    for p, q in zip(pts, pts[1:]): lengths.append(lengths[-1] + math.hypot(q[0] - p[0], q[1] - p[1]))
    us = [l / lengths[-1] for l in lengths]
    shade = [0.72 + 0.28 * (-nx) for (_, _, nx) in pts]     # flat (nx = -1) full, edge-on 0.72
    h = INSERT_H / 2; verts = []
    for (x, y, _) in pts: verts += [(x, y, -h), (x, y, h)]
    faces, uvs, cols = [], [], []
    for i in range(len(pts) - 1):
        a, b, c, d = 2 * i, 2 * (i + 1), 2 * (i + 1) + 1, 2 * i + 1   # outward (-x) winding
        faces.append((a, b, c, d))
        uvs.append(((us[i], 0), (us[i + 1], 0), (us[i + 1], 1), (us[i], 1)))
        cols.append((shade[i], shade[i + 1], shade[i + 1], shade[i]))
    return mesh_object(name, verts, faces, uvs, cols, material, (-1, 0, 0))

def flat_insert(name, front, material, corner_segments, extra=0.0):
    """Front or back insert: from the spine round's edge to 1.5 mm short of the opening edge, 1.5 mm
    short of top and bottom, corners rounded inside the case's rounded corners. Planar UVs."""
    xa, xb = -CW / 2 + SPINE_R, CW / 2 - 0.0015
    za, zb = -(CH / 2 - 0.0015), CH / 2 - 0.0015
    rr = 0.0016; y = -(CD / 2 + PROUD + extra) if front else CD / 2 + PROUD + extra
    outline = []
    for cx, cz, a0 in ((xb - rr, za + rr, -90), (xb - rr, zb - rr, 0), (xa + rr, zb - rr, 90), (xa + rr, za + rr, 180)):
        for i in range(corner_segments + 1):
            a = math.radians(a0 + 90 * i / corner_segments); outline.append((cx + rr * math.cos(a), cz + rr * math.sin(a)))
    if not front: outline.reverse()                          # counter-clockwise as seen from its own side
    verts = [(x, y, z) for (x, z) in outline] + [((xa + xb) / 2, y, 0.0)]
    centre = len(outline)
    def uv(x, z):
        u = (x - xa) / (xb - xa)
        return (u if front else 1 - u, (z - za) / (zb - za))
    faces, uvs = [], []
    for i in range(len(outline)):
        j = (i + 1) % len(outline)
        faces.append((centre, i, j))
        uvs.append((uv((xa + xb) / 2, 0.0), uv(*outline[i]), uv(*outline[j])))
    return mesh_object(name, verts, faces, uvs, None, material, (0, -1, 0) if front else (0, 1, 0))

# The shelf case: every edge rounded, but few segments (2): on the shelf a case is a few dozen
# pixels wide. About 100 triangles; the 144 of them stay under 20k in all.
shelf_plastic = material('Black_plastic', (0.018, 0.018, 0.02), 0.3)  # near-black, not black
case = rounded_block('molded-case', -CD / 2, CD / 2, shelf_plastic, 2, (-CD / 2, CD / 2))
spine_strip('cover-spine', 2, material('cover-spine', (1, 1, 1), 0.5, textured=True))
flat_insert('cover-front', True, material('cover-front', (1, 1, 1), 0.5, textured=True), 2)
flat_insert('cover-back', False, material('cover-back', (1, 1, 1), 0.5, textured=True), 2)

def export(name, objects):
    bpy.ops.object.select_all(action='DESELECT')
    for o in objects: o.select_set(True)
    out = os.path.join(ROOT, f'android/app/src/main/assets/{name}.glb')
    bpy.ops.export_scene.gltf(filepath=out, export_format='GLB', use_selection=True, export_apply=True, export_yup=True, export_image_format='AUTO', export_vertex_color='ACTIVE')
    print('EXPORTED', name, os.path.getsize(out), flush=True)

case_parts = [o for o in bpy.data.objects]

# The disc: 12 cm, 1.2 mm thick, 15 mm hole, printed side facing app +Z (Blender -Y). Lit, unlike the
# rest of the set, so it catches the light: polished metal body, a glossy (clear-coated) printed label,
# and a separate transparent "sheen" layer the app turns with the viewing angle for the rainbow.
def lit_disc(name, color, metallic, roughness, textured=False, alpha=False):
    m = bpy.data.materials.new(name); m.use_nodes = True
    p = m.node_tree.nodes['Principled BSDF']
    p.inputs['Base Color'].default_value = (*color, 1); p.inputs['Metallic'].default_value = metallic
    p.inputs['Roughness'].default_value = roughness
    p.inputs['Coat Weight'].default_value = 1.0; p.inputs['Coat Roughness'].default_value = 0.05
    if textured:
        img = bpy.data.images.new(name + '_tex', 4, 4, alpha=alpha)
        img.pixels.foreach_set(np.tile([1.0, 1.0, 1.0, 0.0 if alpha else 1.0], 16).astype(np.float32))
        n = m.node_tree.nodes.new('ShaderNodeTexImage'); n.image = img
        m.node_tree.links.new(n.outputs['Color'], p.inputs['Base Color'])
        if alpha:
            m.node_tree.links.new(n.outputs['Alpha'], p.inputs['Alpha'])
            m.blend_method = 'BLEND'
    return m

bpy.ops.mesh.primitive_cylinder_add(vertices=64, radius=0.06, depth=0.0012, rotation=(math.pi / 2, 0, 0))
disc = bpy.context.object; disc.name = 'disc-body'
bpy.ops.mesh.primitive_cylinder_add(vertices=48, radius=0.0075, depth=0.01, rotation=(math.pi / 2, 0, 0))
hole = bpy.context.object
cut = disc.modifiers.new('hole', 'BOOLEAN'); cut.operation = 'DIFFERENCE'; cut.object = hole
bpy.context.view_layer.objects.active = disc; bpy.ops.object.modifier_apply(modifier='hole')
bpy.data.objects.remove(hole)
silver = lit_disc('Disc_silver', (0.80, 0.82, 0.86), 0.9, 0.18)
disc.data.materials.clear(); disc.data.materials.append(silver)
for poly in disc.data.polygons: poly.material_index = 0
print('DISC_BODY_MATERIALS', [m.name for m in disc.data.materials], len(disc.data.polygons), flush=True)

def face(name, offset, material):
    bpy.ops.mesh.primitive_circle_add(vertices=96, radius=0.0593, fill_type='NGON', location=(0, offset, 0), rotation=(math.pi / 2, 0, 0))
    o = bpy.context.object; o.name = name
    bpy.ops.object.mode_set(mode='EDIT'); bpy.ops.mesh.select_all(action='SELECT')
    bpy.ops.uv.cube_project(cube_size=0.1186, correct_aspect=False)
    bpy.ops.object.mode_set(mode='OBJECT')
    o.data.materials.append(material)
    return o
# Rainbow sheen sits under the printed label; the label is part see-through (bare disc) and solid where inked.
sheen = face('disc-sheen', -0.00065, lit_disc('disc-sheen', (1, 1, 1), 0.0, 0.2, textured=True, alpha=True))
label = face('disc-label', -0.00072, lit_disc('disc-label', (1, 1, 1), 0.35, 0.28, textured=True, alpha=True))
export('disc', [disc, label, sheen])

# The player's tray: slides out of the deck to take the disc.
bpy.ops.mesh.primitive_cube_add(size=1); tray = bpy.context.object; tray.name = 'tray'
tray.scale = (0.15, 0.155, 0.005); bpy.ops.object.transform_apply(scale=True)
tray.data.materials.append(material('Tray_black', (0.04, 0.04, 0.04), 0.5))
export('tray', [tray])

path = os.path.join(ROOT, 'android/app/src/main/assets/dvd_case.glb')
export('dvd_case', case_parts)


# --- The case in your hands: only one exists, so it can have the detail the shelf cases skip. ---
# Lit (Principled) materials, rounded edges, the seam between the two halves, and the latch tabs.
for o in list(bpy.data.objects): bpy.data.objects.remove(o)

def lit(name, color, roughness, textured=False):
    m = bpy.data.materials.new(name); m.use_nodes = True
    m.use_backface_culling = True  # exported single-sided: sleeves never show through from inside the case
    p = m.node_tree.nodes['Principled BSDF']
    p.inputs['Base Color'].default_value = (*color, 1); p.inputs['Roughness'].default_value = roughness
    if textured:
        img = bpy.data.images.new(name + '_tex', 4, 4); img.pixels.foreach_set(np.ones(64, dtype=np.float32))
        n = m.node_tree.nodes.new('ShaderNodeTexImage'); n.image = img
        m.node_tree.links.new(n.outputs['Color'], p.inputs['Base Color'])
    return m

plastic = lit('Hero_plastic', (0.01, 0.01, 0.011), 0.3)
inner = lit('Hero_inside', (0.025, 0.025, 0.028), 0.45)

def block(name, size, loc, material, bevel=0.0):
    bpy.ops.mesh.primitive_cube_add(size=1, location=loc); o = bpy.context.object
    o.name = name; o.scale = size; bpy.ops.object.transform_apply(scale=True)
    if bevel:
        b = o.modifiers.new('bevel', 'BEVEL'); b.width = bevel; b.segments = 3
    o.data.materials.append(material)
    return o

# It opens like the real thing. Blender axes: x across, -y toward the front (app +Z), z up (app Y).
# Two rounded halves (base y 0 .. CD/2, lid -CD/2 .. 0) meeting at a sharp parting line, 4 segments
# per rounded edge; one object in your hands, so the extra triangles cost nothing measurable.
parts = [rounded_block('case-base', 0.0, CD / 2, plastic, 4, (CD / 2,))]
bpy.ops.mesh.primitive_cylinder_add(vertices=40, radius=0.011, depth=0.0016, location=(0, -0.0008, 0), rotation=(math.pi / 2, 0, 0))
hub = bpy.context.object; hub.name = 'case-hub'; hub.data.materials.append(inner); parts.append(hub)
lid_parts = [rounded_block('case-lid', -CD / 2, 0.0, plastic, 4, (-CD / 2,)),
             # The clasp in the middle of the opening edge (the one place that isn't rounded).
             block('case-clasp', (0.0016, 0.005, 0.02), (CW / 2 + 0.0004, -0.0025, 0), plastic, 0.0004)]
bpy.ops.object.select_all(action='DESELECT')
for o in lid_parts: o.select_set(True)
bpy.context.view_layer.objects.active = lid_parts[0]
bpy.ops.object.convert(target='MESH'); bpy.ops.object.join()
lid = bpy.context.view_layer.objects.active; lid.name = 'case-lid'
# Origin on the hinge line (spine edge, between the halves).
bpy.context.scene.cursor.location = (-CW / 2, 0, 0)
bpy.ops.object.origin_set(type='ORIGIN_CURSOR')
parts.append(lid)
front = flat_insert('cover-front', True, lit('cover-front', (1, 1, 1), 0.35, textured=True), 4)
front.parent = lid; front.matrix_parent_inverse = lid.matrix_world.inverted()   # swings with the lid
parts += [front, flat_insert('cover-back', False, lit('cover-back', (1, 1, 1), 0.35, textured=True), 4),
          spine_strip('cover-spine', 4, lit('cover-spine', (1, 1, 1), 0.35, textured=True))]
# The plastic sleeve's sheen: a see-through gloss layer just over each insert. The app slides its
# highlight across as the case turns and sets its strength (Settings → Cover sheen); glb_gamma.py
# makes these materials blended.
gloss = flat_insert('gloss-front', True, material('gloss-front', (1, 1, 1), 0.5, textured=True), 4, extra=0.00008)
gloss.parent = lid; gloss.matrix_parent_inverse = lid.matrix_world.inverted()
parts += [gloss, flat_insert('gloss-back', False, material('gloss-back', (1, 1, 1), 0.5, textured=True), 4, extra=0.00008)]
export('dvd_case_hero', parts)
