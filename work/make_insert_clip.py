"""Packs the baked disc insert (the open case and the disc's silver and rainbow, see InsertTimeline in
Insert.kt) into android/app/src/main/assets/insert_clip.bin, ASTC 6x6 frame by frame.

1. Bake on the TV box (a case comes off the shelf by itself, then each frame is rendered offscreen):
     adb shell am start -n com.shelflife.tv/.MainActivity --ez bakeInsert true
   and wait for `insert baked` in `adb logcat -s ShelfLifePerf`.
2. adb pull /sdcard/Android/data/com.shelflife.tv/files/insert-bake <dir>
3. python3 work/make_insert_clip.py <dir>/insert-bake android/app/src/main/assets <path to astcenc>
   (needs Pillow; astcenc from github.com/ARM-software/astc-encoder releases)

Frames come as cNNN_x_y_w_h_W_H.png (the case) and dNNN_….png (the disc, baked with the case filling
depth): premultiplied RGBA exactly as rendered, rows top-down, cropped to what has any alpha. The quad
shows its texture upside down (V runs bottom-up), so each frame is stored flipped (measured on the TV
against the live render). Identical frames (the case while the disc is out) are stored once.
Written: "SLC2", u32 frame count, fps, full width, height; then per layer (case, disc) u32 distinct
frame count, each u32 x, y (top-left), width, height (padded to whole 6x6 blocks), byte count and the
blocks (astcenc's 16-byte header stripped), then u32 per timeline frame: which one shows. Values are
encoded as-is (LDR, no sRGB, alpha kept premultiplied), the same display-space convention as the lightmap."""
import sys, os, re, glob, struct, subprocess, tempfile
from PIL import Image

src, assets, astcenc = sys.argv[1], sys.argv[2], sys.argv[3]
FPS = 30
blob, full = [], None
with tempfile.TemporaryDirectory() as tmp:
    for prefix in 'cd':
        files = sorted(glob.glob(os.path.join(src, prefix + '*.png')))
        distinct, index, seen = [], [], {}
        for path in files:
            x, y, w, h, W, H = map(int, re.match(r'[cd]\d+_(\d+)_(\d+)_(\d+)_(\d+)_(\d+)_(\d+)\.png', os.path.basename(path)).groups())
            full = (W, H)
            im = Image.open(path).convert('RGBA')
            key = (x, y, im.tobytes())
            if key not in seen:
                # Whole 6x6 blocks, padded with transparent texels, grown down and right (clamped to the frame by moving left/up).
                pw, ph = -(-w // 6) * 6, -(-h // 6) * 6
                x0, y0 = min(x, W - pw) if pw <= W else 0, min(y, H - ph) if ph <= H else 0
                padded = Image.new('RGBA', (pw, ph), (0, 0, 0, 0))
                padded.paste(im, (x - x0, y - y0))
                padded = padded.transpose(Image.FLIP_TOP_BOTTOM)
                png, astc = os.path.join(tmp, 'f.png'), os.path.join(tmp, 'f.astc')
                padded.save(png)
                subprocess.run([astcenc, '-cl', png, astc, '6x6', '-thorough', '-silent'], check=True)
                data = open(astc, 'rb').read()[16:]
                seen[key] = len(distinct)
                distinct.append(struct.pack('<IIIII', x0, y0, pw, ph, len(data)) + data)
            index.append(seen[key])
        blob.append(struct.pack('<I', len(distinct)) + b''.join(distinct) + struct.pack('<%dI' % len(index), *index))
        print(prefix, len(files), 'frames,', len(distinct), 'distinct')
data = b'SLC2' + struct.pack('<IIII', len(index), FPS, *full) + b''.join(blob)
path = os.path.join(assets, 'insert_clip.bin')
open(path, 'wb').write(data)
print('written', path, len(data) // 1024, 'KB')
