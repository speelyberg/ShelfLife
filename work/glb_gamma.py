"""Prepares the Blender-exported glbs for ShelfLife's display-space renderer (no post-processing).

Filament on the Onn's Mali GPU runs without post-processing, so nothing converts linear light to
display values at the end: every colour must already be display-encoded. This script, run after
build_room / bake_android / build_case:
  * rewrites each material's baseColorFactor RGB from linear to sRGB-encoded values,
  * drops KHR_materials_clearcoat (a second specular lobe the TV box can't afford),
  * moves room.glb's baked lightmap out to room_lightmap.jpg (the app uploads it as a plain,
    non-sRGB texture) and leaves a 4x4 placeholder in its place,
  * writes backdrop.glb: a unit quad with an unlit, blended, textured material (the blurred
    snapshot behind a case in your hands).
Run: python3 work/glb_gamma.py android/app/src/main/assets
"""
import json, struct, sys, zlib, os

def read(p):
    b = open(p, 'rb').read()
    n = struct.unpack('<I', b[12:16])[0]
    j = json.loads(b[20:20 + n])
    m = struct.unpack('<I', b[20 + n:24 + n])[0]
    return j, bytearray(b[28 + n:28 + n + m])

def write(p, j, binbuf):
    js = json.dumps(j, separators=(',', ':')).encode()
    js += b' ' * (-len(js) % 4)
    binbuf = bytes(binbuf) + b'\0' * (-len(binbuf) % 4)
    total = 12 + 8 + len(js) + 8 + len(binbuf)
    out = struct.pack('<III', 0x46546C67, 2, total) + struct.pack('<II', len(js), 0x4E4F534A) + js + struct.pack('<II', len(binbuf), 0x004E4942) + binbuf
    open(p, 'wb').write(out)

def enc(c):
    return 12.92 * c if c <= 0.0031308 else 1.055 * c ** (1 / 2.4) - 0.055

def png4(rgba=(0, 0, 0, 255)):
    raw = b''.join(b'\0' + bytes(rgba) * 4 for _ in range(4))
    chunk = lambda t, d: struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 4, 4, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b'')

def replace_view(j, binbuf, view_index, data):
    """Swaps one bufferView's bytes, shifting every later view."""
    v = j['bufferViews'][view_index]
    start, old = v.get('byteOffset', 0), v['byteLength']
    pad = (-len(data)) % 4
    new = data + b'\0' * pad
    delta = len(new) - old
    binbuf[start:start + old] = new
    v['byteLength'] = len(data)
    for w in j['bufferViews']:
        if w is not v and w.get('byteOffset', 0) > start: w['byteOffset'] = w.get('byteOffset', 0) + delta
    j['buffers'][0]['byteLength'] = len(binbuf)

assets = sys.argv[1]
for name in ['room.glb', 'dvd_case.glb', 'dvd_case_hero.glb', 'disc.glb', 'tray.glb']:
    p = os.path.join(assets, name)
    j, binbuf = read(p)
    if j.get('asset', {}).get('extras', {}).get('shelflife_gamma'): print(name, 'already done'); continue
    for m in j.get('materials', []):
        pbr = m.setdefault('pbrMetallicRoughness', {})
        f = pbr.get('baseColorFactor', [1, 1, 1, 1])
        pbr['baseColorFactor'] = [round(enc(c), 5) for c in f[:3]] + [f[3]]
        m.get('extensions', {}).pop('KHR_materials_clearcoat', None)
        if 'extensions' in m and not m['extensions']: del m['extensions']
    j['extensionsUsed'] = [e for e in j.get('extensionsUsed', []) if e != 'KHR_materials_clearcoat']
    if name == 'room.glb':
        for im in j['images']:
            if im.get('name') == 'room_lightmap':
                v = j['bufferViews'][im['bufferView']]
                open(os.path.join(assets, 'room_lightmap.jpg'), 'wb').write(binbuf[v.get('byteOffset', 0):v.get('byteOffset', 0) + v['byteLength']])
                replace_view(j, binbuf, im['bufferView'], png4((128, 128, 128, 255)))
                im['mimeType'] = 'image/png'
    j.setdefault('asset', {}).setdefault('extras', {})['shelflife_gamma'] = True
    write(p, j, binbuf)
    print(name, 'converted')

# The held case's sleeves are unlit: a printed cover needs no lighting, and lit shading over most of the
# case was wasted work on the TV box. The plastic stays lit so its bevels read.
p = os.path.join(assets, 'dvd_case_hero.glb')
j, binbuf = read(p)
if any(m['name'].startswith('cover-') and 'KHR_materials_unlit' not in m.get('extensions', {}) for m in j['materials']):
    for m in j['materials']:
        if m['name'].startswith('cover-'): m.setdefault('extensions', {})['KHR_materials_unlit'] = {}
    if 'KHR_materials_unlit' not in j.setdefault('extensionsUsed', []): j['extensionsUsed'].append('KHR_materials_unlit')
    write(p, j, binbuf)
    print('dvd_case_hero.glb: sleeves unlit')

# The held case's gloss layers are see-through (alpha from the app's highlight texture).
p = os.path.join(assets, 'dvd_case_hero.glb')
j, binbuf = read(p)
changed = False
for m in j['materials']:
    if m['name'].startswith('gloss-') and m.get('alphaMode') != 'BLEND':
        m['alphaMode'] = 'BLEND'; m['doubleSided'] = False; changed = True
if changed:
    write(p, j, binbuf); print('dvd_case_hero.glb: gloss blended')

# The backdrop quad: 1 x 1 in XY facing +Z, UVs with (0,0) at the top-left of the image.
pos = struct.pack('<12f', -0.5, -0.5, 0, 0.5, -0.5, 0, 0.5, 0.5, 0, -0.5, 0.5, 0)
uv = struct.pack('<8f', 0, 1, 1, 1, 1, 0, 0, 0)
idx = struct.pack('<6H', 0, 1, 2, 0, 2, 3) + b'\0\0'
img = png4((255, 255, 255, 255))
binbuf = bytearray(pos + uv + idx + img)
j = {
    'asset': {'version': '2.0', 'generator': 'glb_gamma.py'},
    'extensionsUsed': ['KHR_materials_unlit'],
    'scene': 0, 'scenes': [{'nodes': [0]}],
    'nodes': [{'name': 'backdrop', 'mesh': 0}],
    'meshes': [{'primitives': [{'attributes': {'POSITION': 0, 'TEXCOORD_0': 1}, 'indices': 2, 'material': 0}]}],
    'materials': [{'name': 'backdrop', 'alphaMode': 'BLEND', 'doubleSided': True, 'extensions': {'KHR_materials_unlit': {}},
                   'pbrMetallicRoughness': {'baseColorTexture': {'index': 0}, 'baseColorFactor': [1, 1, 1, 1]}}],
    'textures': [{'source': 0, 'sampler': 0}], 'samplers': [{'magFilter': 9729, 'minFilter': 9729, 'wrapS': 33071, 'wrapT': 33071}],
    'images': [{'bufferView': 3, 'mimeType': 'image/png'}],
    'accessors': [
        {'bufferView': 0, 'componentType': 5126, 'count': 4, 'type': 'VEC3', 'min': [-0.5, -0.5, 0], 'max': [0.5, 0.5, 0]},
        {'bufferView': 1, 'componentType': 5126, 'count': 4, 'type': 'VEC2'},
        {'bufferView': 2, 'componentType': 5123, 'count': 6, 'type': 'SCALAR'}],
    'bufferViews': [{'buffer': 0, 'byteOffset': 0, 'byteLength': 48}, {'buffer': 0, 'byteOffset': 48, 'byteLength': 32},
                    {'buffer': 0, 'byteOffset': 80, 'byteLength': 12}, {'buffer': 0, 'byteOffset': 80 + len(idx), 'byteLength': len(img)}],
    'buffers': [{'byteLength': len(binbuf)}],
}
write(os.path.join(assets, 'backdrop.glb'), j, binbuf)
print('backdrop.glb written')
