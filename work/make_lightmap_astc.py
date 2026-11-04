"""Compresses the room's lightmap for the TV box: every mip level ASTC 6x6 (3.56 bits a texel; the
Onn's Mali-G31 decodes it in hardware, 1/9 the memory and read bandwidth of RGBA8). Run after
bake_android.py + glb_gamma.py:
  python3 work/make_lightmap_astc.py android/app/src/main/assets <path to ARM astcenc>
Writes assets/room_lightmap.astc: "SLA6", u32 levels, then per level u32 width, u32 height,
u32 byte count and the ASTC blocks (astcenc's 16-byte header stripped). Values are encoded as-is
(LDR, no sRGB): the app shows the lightmap in display space. The JPEG stays as the fallback."""
import sys, os, struct, subprocess, tempfile
from PIL import Image
assets, astcenc = sys.argv[1], sys.argv[2]
im = Image.open(os.path.join(assets, 'room_lightmap.jpg')).convert('RGBA')
out = [b'SLA6']
levels = []
with tempfile.TemporaryDirectory() as tmp:
    w, h = im.size
    while True:
        png, astc = os.path.join(tmp, 'l.png'), os.path.join(tmp, 'l.astc')
        im.save(png)
        subprocess.run([astcenc, '-cl', png, astc, '6x6', '-medium', '-silent'], check=True)
        data = open(astc, 'rb').read()[16:]
        levels.append((w, h, data))
        if w == 1 and h == 1: break
        w, h = max(1, w // 2), max(1, h // 2); im = im.resize((w, h), Image.BOX)
out.append(struct.pack('<I', len(levels)))
for w, h, data in levels: out.append(struct.pack('<III', w, h, len(data)) + data)
path = os.path.join(assets, 'room_lightmap.astc')
open(path, 'wb').write(b''.join(out))
print('written', path, os.path.getsize(path) // 1024, 'KB,', len(levels), 'levels')
