"""ShelfLife's procedural parody of the FBI Anti-Piracy Warning seal: the gold spiked border, the motto ribbon, the
navy disc with its rings, the stars, the striped emblem with its gold cap and scales, and the lettering (our own
words, set where the original's sit). Measured from warning-sources/fbi_apw.png (1213x1223, seal centre (606, 611));
the reference is used for measurements only, every pixel here is generated.

Spikes: 10 identical units, one every 36 degrees. Each is a tall spike with a 'horn' on each side, and a shorter
spike sits behind the gap between units. Each face is shaded by angle across the face (bright on its clockwise
side in image terms), as measured.
Ribbon: three white panels (left, centre, right), two tan folds where the strip turns over between panels, and a
tan curl plus a white tail at each end. Mirror-symmetric. The tan parts share one horizontal gradient.

Text: Superclarendon Black for the arcs and the band, Times New Roman squeezed narrower for the ribbon. Each word
is laid letter by letter along a path (a circle, a straight line, or the ribbon panel's centre line). Letter
spacing is calibrated by fitting the original's words into their measured spans; ours use that spacing, squeezed
narrower only if they would not fit.

Writes, in work/fbi-seal/ (every layer shares one square canvas so they line up in After Effects; stack them in
this order, bottom first):
  fbi_spikes.png, fbi_disc.png, fbi_arc_text.png, fbi_stars.png, fbi_emblem.png, fbi_ribbon.png,
  fbi_ribbon_text.png, fbi_band.png
  fbi_seal.png                        all of them composited
  fbi_check.png                       ours next to the reference, for comparison
Run: python3 work/make_fbi_seal.py [size px, default 4096]"""
import math, os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'fbi-seal')
SIZE = int(sys.argv[1]) if len(sys.argv) > 1 else 4096
REF = 1224.0                          # design units are px of the reference; the canvas is REF x REF around the seal centre
C = (REF / 2, REF / 2)                # seal centre on the canvas
REF_CENTRE = (606.0, 611.0)           # seal centre in the reference image (used only to map ribbon coordinates)
SS = 4                                # supersampling of each piece's edge

# --- spikes, polar (angle degrees, radius design px); angle is maths-style: 0 = right, counter-clockwise ---
PERIOD, PHASE = 36.0, 90.0            # a tall tip straight up, then every 36 degrees
R_IN = 470.0                          # pieces start here; the navy disc (r 524) covers everything inside
TALL, V, HORN, HORN_W = (0.0, 611.0), (7.1, 561.5), 593.5, 13.6     # tall tip, the V between tip and horn, horn top r, horn edge angle
BACK_TIP, BACK_SIDE = (18.0, 587.0), 562.0                          # back spike tip; its edge radius where the horns cover it
G_FACE = [(0, (255, 216, 54)), (.14, (251, 213, 52)), (.28, (241, 204, 49)), (.42, (233, 196, 45)), (.56, (227, 190, 43)),
          (.70, (223, 186, 41)), (.85, (219, 183, 41)), (1, (219, 183, 40))]       # both faces of the tall spike
G_HORN_R = [(0, (252, 214, 53)), (.29, (252, 214, 53)), (.45, (242, 205, 49)), (.6, (230, 193, 44)), (.75, (223, 186, 42)),
            (1, (217, 181, 40))]
G_HORN_L = [(0, (250, 212, 53)), (1, (250, 212, 53))]
G_BACK = [(0, (238, 200, 30)), (.16, (248, 209, 9)), (.27, (250, 212, 37)), (.39, (251, 213, 55)), (.5, (249, 213, 61)),
          (.61, (234, 199, 58)), (.73, (219, 186, 55)), (.84, (205, 174, 53)), (1, (190, 162, 50))]

# --- ribbon, left half in reference px (mirrored about RIBBON_AXIS), traced from the reference's outlines. Each piece
# is a list of runs; a run is a smooth Catmull-Rom curve, and runs meet at sharp corners. ---
RIBBON_AXIS = 600.0
WHITE = (255, 255, 255)
G_TAN = [(0, (196, 156, 144)), (.5, (253, 238, 214)), (1, (196, 156, 144))]   # across every fold and curl, left to right
TAIL = [[(288, 674), (308, 671), (318, 671), (323, 672), (326, 674), (326, 677)], [(326, 677), (321, 683), (304, 696)],
        [(304, 696), (300, 693), (288, 676)]]
CURL = [[(326, 675), (320, 683), (304, 695), (293, 707), (291, 711), (291, 719), (296, 726), (304, 731), (323, 731), (343, 727)],
        [(343, 727), (343, 724), (334, 694), (326, 675)]]                        # bottom runs under the panel
CURL_X = (291, 343)
LEFT = [[(297, 729), (325, 729), (336, 727), (361, 720), (403, 700), (430, 690), (457, 681), (472, 676), (493, 672),
         (510, 671), (523, 674), (529, 679), (533, 686), (535, 693)],          # top, rolling over at the right
        [(535, 693), (534, 704), (528, 712), (506, 727), (496, 737), (493, 743)],   # the end turning into the fold
        [(493, 746), (478, 748), (458, 754), (441, 761), (426, 768), (400, 781), (379, 792), (360, 798), (351, 798), (347, 796)],
        [(347, 796), (297, 729)]]
FOLD = [[(535, 697), (533, 705), (527, 712), (505, 727), (496, 736), (493, 741), (493, 746), (497, 751), (515, 760),
         (529, 765), (543, 769)], [(543, 769), (542, 737), (540, 722), (535, 697)]]
FOLD_X = (493, 543)
CENTRE_TOP = [(494, 747), (497, 750), (516, 759), (537, 766), (552, 769), (568, 771), (600, 772)]
CENTRE_LEFT = [(484, 819), (485, 800), (490, 770), (492, 759), (494, 753), (494, 747)]
CENTRE_BOTTOM = [(484, 819), (498, 827), (511, 832), (537, 839), (553, 841), (571, 843.5), (600, 844)]


def spline(pts, n=12):
    """Catmull-Rom through pts (ends kept), n samples per span."""
    p = [pts[0]] + list(pts) + [pts[-1]]
    out = []
    for i in range(1, len(p) - 2):
        p0, p1, p2, p3 = (np.array(q, float) for q in p[i - 1:i + 3])
        for t in np.linspace(0, 1, n, endpoint=False):
            out.append(0.5 * (2 * p1 + (p2 - p0) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t + (3 * p1 - p0 - 3 * p2 + p3) * t ** 3))
    out.append(np.array(pts[-1], float))
    return [tuple(q) for q in out]


def outline(runs):
    pts = []
    for run in runs:
        pts += spline(run)
    return pts


def ramp(stops, u):
    u = np.clip(u, 0, 1)
    xs = [s[0] for s in stops]
    return np.stack([np.interp(u, xs, [s[1][k] for s in stops]) for k in range(3)], -1)


def paint(layer, poly, colour_at):
    """Fill poly (design px) into layer (H x W x 4 float, premultiplied) with colour_at(x, y) -> rgb, x, y in design px."""
    k = SIZE / REF
    pts = [(x * k, y * k) for x, y in poly]
    x0, y0 = max(0, int(min(p[0] for p in pts)) - 1), max(0, int(min(p[1] for p in pts)) - 1)
    x1, y1 = min(SIZE, int(max(p[0] for p in pts)) + 2), min(SIZE, int(max(p[1] for p in pts)) + 2)
    m = Image.new('L', ((x1 - x0) * SS, (y1 - y0) * SS))
    ImageDraw.Draw(m).polygon([((x - x0) * SS, (y - y0) * SS) for x, y in pts], fill=255)
    a = np.asarray(m.resize((x1 - x0, y1 - y0), Image.BOX), float)[..., None] / 255
    yy, xx = np.mgrid[y0:y1, x0:x1] + 0.5
    rgb = colour_at(xx / k, yy / k)
    dst = layer[y0:y1, x0:x1]
    dst[..., :3] = rgb * a + dst[..., :3] * (1 - a)
    dst[..., 3:] = a + dst[..., 3:] * (1 - a)


def polar(phi, r, base):
    t = math.radians(base + phi)
    return C[0] + r * math.cos(t), C[1] - r * math.sin(t)


def angle_of(x, y, base):
    """Angle of design point (x, y) relative to base, in degrees, wrapped to (-180, 180]."""
    return (np.degrees(np.arctan2(C[1] - y, x - C[0])) - base + 180) % 360 - 180


def spikes():
    L = np.zeros((SIZE, SIZE, 4))
    for i in range(10):
        base = PHASE + i * PERIOD
        lo = HORN_W - 0.4                                   # back spike, starting just under the horns
        back = [polar(lo, R_IN, base), polar(lo, BACK_SIDE, base), polar(*BACK_TIP, base),
                polar(2 * BACK_TIP[0] - lo, BACK_SIDE, base), polar(2 * BACK_TIP[0] - lo, R_IN, base)]
        paint(L, back, lambda x, y, b=base: ramp(G_BACK, (angle_of(x, y, b) - HORN_W) / (2 * (BACK_TIP[0] - HORN_W))))
        # the front unit as one shape (no seams); each face's gradient runs across its angle span, faces meet radially
        edge = [(-HORN_W, HORN), (-V[0], V[1]), TALL, V, (HORN_W, HORN)]
        poly = [polar(-HORN_W, R_IN, base)] + [polar(p, r, base) for p, r in edge] + [polar(HORN_W, R_IN, base)]
        faces = list(zip([p for p, _ in edge][:-1], [p for p, _ in edge][1:], [G_HORN_L, G_FACE, G_FACE, G_HORN_R]))
        def colour(x, y, b=base):
            phi = angle_of(x, y, b)
            out = ramp(faces[0][2], (phi - faces[0][0]) / (faces[0][1] - faces[0][0]))
            for pa, pb, g in faces[1:]:
                out = np.where((phi >= pa)[..., None], ramp(g, (phi - pa) / (pb - pa)), out)
            return out
        paint(L, poly, colour)
    return L


def ribbon():
    L = np.zeros((SIZE, SIZE, 4))
    dx, dy = C[0] - REF_CENTRE[0], C[1] - REF_CENTRE[1]
    def place(pts, mirror):
        return [((2 * RIBBON_AXIS - x if mirror else x) + dx, y + dy) for x, y in pts]
    def tan(span, mirror):
        a, b = span
        if mirror:
            a, b = 2 * RIBBON_AXIS - b, 2 * RIBBON_AXIS - a
        a, b = a + dx, b + dx
        return lambda x, y: ramp(G_TAN, (x - a) / (b - a))
    white = lambda x, y: np.broadcast_to(np.array(WHITE, float), x.shape + (3,))
    for m in (False, True):
        paint(L, place(outline(CURL), m), tan(CURL_X, m))
        paint(L, place(outline(TAIL), m), white)
        paint(L, place(outline(FOLD), m), tan(FOLD_X, m))
        paint(L, place(outline(LEFT), m), white)
    top, bottom = spline(CENTRE_TOP), spline(CENTRE_BOTTOM)
    half = spline(CENTRE_LEFT) + top
    mirror = lambda pts: [(2 * RIBBON_AXIS - x, y) for x, y in pts]
    centre = half + mirror(half[::-1]) + mirror(bottom) + bottom[::-1]
    paint(L, place(centre, False), white)
    return L


# --- disc, rings and lettering (reference px around the seal centre) ---
NAVY, GOLD, RED = (36, 50, 111), (250, 212, 53), (187, 32, 67)
DISC_R, RINGS = 523.5, [(495, 501), (402, 408)]          # navy disc; thin gold rings (inner, outer radius)
CLARENDON = ('/System/Library/Fonts/Supplemental/SuperClarendon.ttc', 5)   # Bold (index 7 = Black, 0 = Regular)
TIMES = ('/System/Library/Fonts/Supplemental/Times New Roman Bold.ttf', 0)
ARC_CAP, ARC_R = 44.0, 450.0                             # letters span r 428..472
TOP_TEXT, TOP_SPAN, TOP_ORIG = 'DEPARTMENT OF MOVIE NIGHT', (151.4, 29.0), 'DEPARTMENT OF JUSTICE'
BOTTOM_TEXT, BOTTOM_SPAN, BOTTOM_ORIG = 'BUREAU OF BE KIND, PLEASE REWIND', (184.6, 355.5), 'FEDERAL BUREAU OF INVESTIGATION'
BAND_OUT, BAND_IN = (0, 411, 1213, 638), (13, 424, 1200, 625)    # navy frame and white inside, reference image px
BAND_LINES = [('SHELFLIFE ANTI-SPOILER', 'FBI ANTI-PIRACY', NAVY, 79.0, 478.5, (49, 1163)),       # text, original, colour,
              ('WARNING', 'WARNING', RED, 84.0, 570.0, (205, 997))]                               # cap, centre y, ink x span
RIBBON_TEXT = ['FRESHLY', 'BUTTERED', 'INVESTIGATORS']
RIBBON_TEXT_X = [(336, 514), (494, 706)]                 # text runs along these x spans (left panel, centre panel)
RIBBON_CAP = (0.42, 0.56)                                # cap height as a share of the panel's height: min, max
RIBBON_TRACK = 0.06                                      # letter spacing, as a share of the cap height
RIBBON_BEND = [1.0, 1.6, 1.0]                            # curve of each word's line, x the panel's own (left, centre, right)
RIBBON_SQUEEZE = 0.88                                    # narrowest the letters may be squeezed, once the cap is at its min

# --- stars and emblem (reference image px, like the ribbon) ---
# Stars: (centre x, centre y, angle of one tip in degrees, maths-style). Every star is the same bevelled 5-point
# star; its 10 faces have flat colours, listed counter-clockwise starting at the face left of that tip.
STARS = [(424.1, 323.3, 120), (778.6, 322.9, 58), (421.5, 891.2, 59), (780.6, 891.1, 122), (599.1, 940.0, 90)]
STAR_R, STAR_INNER = 44.0, 0.40                          # tip radius; inner corners at this share of it
STAR_FACES = [(250, 212, 53), (253, 216, 8), (231, 195, 67), (251, 230, 101), (230, 196, 30),
              (251, 214, 9), (253, 216, 8), (251, 215, 15), (251, 230, 101), (253, 216, 8)]
EMBLEM_AXIS = 601.0                                      # the emblem is mirror-symmetric about this x
STRIPES = ((531, 671), (324, 412), [RED, WHITE, RED, WHITE, RED])      # x span, y span (runs under the band), colours
# Gold cap, left half: top-left corner, flared point, the cove curving back in (quadratic control, end), then down
# to the stripes; and the knob on top (base, control of its concave side, top).
CAP = dict(top=256.0, corner=532.5, flare=(512.0, 276.75), cove=((529.5, 278.75), (531.0, 296.25)), bottom=324.5,
           knob_base=588.75, knob_ctrl=(596.0, 254.0), knob_top=(596.25, 244.5))
CAP_GOLD, CAP_SHEEN = (252, 216, 7), (251, 228, 100)     # base, and the diagonal highlight's centre colour
SHEEN_LINE, SHEEN_W = ((614.0, 262.0), (558.0, 315.0)), 38.0           # highlight runs along this line; half-width
SCALES = (25, 47, 90)                                    # the scales' colour
RING = ((600.75, 263.75), 7.5, 5.5)                      # hanging ring: centre, outer, inner radius
BEAM_TOP = [(555, 280.5), (588, 273), (601, 273)]        # left half, outer end to the middle
BEAM_BOTTOM = [(555, 285.5), (590, 280.5), (601, 289.5)] # ends in the point under the middle
HOOK = ((555.5, 279.0), 7.0, 4.6)                        # U-shaped cradle at the beam's end: centre, outer, inner radius
PAN = ((553.25, 297.5), 15.6, 15.0)                      # left pan: rim centre, half-width, depth
STRING_W = 1.3                                           # the pans' strings, from the beam end to the rim


def font(face, cap_px):
    """The face at the size whose capital H is cap_px tall."""
    f = ImageFont.truetype(face[0], 200, index=face[1])
    b = f.getbbox('H', anchor='ls')
    return ImageFont.truetype(face[0], max(1, round(200 * cap_px / (b[3] - b[1]))), index=face[1])


def lay_text(img, text, face, cap, colour, path, gap=None, orig=None):
    """Set text along path (design px points, reading direction), cap centred on the path. Spacing: gap (design px
    between letters), else the gap that makes orig fill the path; then squeezed narrower if text would overflow."""
    k = SIZE / REF
    f = font(face, cap * k * SS)
    P = np.array(path, float)
    seg = np.hypot(*np.diff(P, axis=0).T)
    s = np.concatenate([[0], np.cumsum(seg)])
    length = s[-1]
    adv = lambda t: np.array([f.getlength(ch) for ch in t]) / (k * SS)
    if gap is None:
        gap = (length - adv(orig).sum()) / (len(orig) - 1) if orig else 0.0
    w = adv(text)
    sx = min(1.0, (length - gap * (len(text) - 1)) / w.sum())
    total = w.sum() * sx + gap * (len(text) - 1)
    pos = (length - total) / 2
    for ch, wi in zip(text, w * sx):
        c = pos + wi / 2
        pos += wi + gap
        if ch == ' ':
            continue
        x, y = np.interp(c, s, P[:, 0]), np.interp(c, s, P[:, 1])
        d = 1.0
        x2, y2 = np.interp(c + d, s, P[:, 0]), np.interp(c + d, s, P[:, 1])
        x1, y1 = np.interp(c - d, s, P[:, 0]), np.interp(c - d, s, P[:, 1])
        ang = -math.degrees(math.atan2(y2 - y1, x2 - x1))
        D = int(cap * k * SS * 3)
        g = Image.new('L', (D, D))
        ImageDraw.Draw(g).text((D / 2, D / 2 + cap * k * SS / 2), ch, font=f, fill=255, anchor='ms')
        g = g.resize((max(1, round(D * sx)), D), Image.LANCZOS) if sx < 1 else g
        g = g.rotate(ang, resample=Image.BICUBIC, expand=True)
        g = g.resize((max(1, round(g.width / SS)), max(1, round(g.height / SS))), Image.LANCZOS)
        tile = Image.new('RGBA', g.size, colour + (0,))
        tile.putalpha(g)
        img.alpha_composite(tile, (round(x * k - g.width / 2), round(y * k - g.height / 2)))


def arc(r, a0, a1, n=400):
    return [(C[0] + r * math.cos(math.radians(t)), C[1] - r * math.sin(math.radians(t))) for t in np.linspace(a0, a1, n)]


def qbez(p0, c, p1, n=16):
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * c[0] + t * t * p1[0],
             (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * c[1] + t * t * p1[1]) for t in np.linspace(0, 1, n)]


def flat(rgb):
    return lambda x, y: np.broadcast_to(np.array(rgb, float), x.shape + (3,))


def stars():
    L = np.zeros((SIZE, SIZE, 4))
    dx, dy = C[0] - REF_CENTRE[0], C[1] - REF_CENTRE[1]
    for sx, sy, tip in STARS:
        cx, cy = sx + dx, sy + dy
        pts = [(cx + (STAR_R if i % 2 == 0 else STAR_R * STAR_INNER) * math.cos(math.radians(tip + 36 * i)),
                cy - (STAR_R if i % 2 == 0 else STAR_R * STAR_INNER) * math.sin(math.radians(tip + 36 * i))) for i in range(10)]
        def colour(x, y, cx=cx, cy=cy, tip=tip):
            face = (((np.degrees(np.arctan2(cy - y, x - cx)) - tip) % 360) // 36).astype(int).clip(0, 9)
            return np.array(STAR_FACES, float)[face]
        paint(L, pts, colour)
    return to_image(L)


def emblem():
    L = np.zeros((SIZE, SIZE, 4))
    dx, dy = C[0] - REF_CENTRE[0], C[1] - REF_CENTRE[1]
    A = EMBLEM_AXIS
    full = lambda half: [(x + dx, y + dy) for x, y in half] + [(2 * A - x + dx, y + dy) for x, y in half[::-1]]
    # stripes, as one shape coloured by x
    (x0, x1), (y0, y1), cols = STRIPES
    paint(L, [(x0 + dx, y0 + dy), (x1 + dx, y0 + dy), (x1 + dx, y1 + dy), (x0 + dx, y1 + dy)],
          lambda x, y: np.array(cols, float)[((x - dx - x0) / (x1 - x0) * len(cols)).astype(int).clip(0, len(cols) - 1)])
    # gold cap with its knob; the sheen is a band along SHEEN_LINE
    c = CAP
    half = [(x0, c['bottom']), c['cove'][1]] + \
        qbez(c['cove'][1], c['cove'][0], c['flare'])[1:] + [(c['corner'], c['top']), (c['knob_base'], c['top'])] + \
        qbez((c['knob_base'], c['top']), c['knob_ctrl'], c['knob_top'])[1:] + [(A, c['knob_top'][1])]
    (ax, ay), (bx, by) = SHEEN_LINE
    nx, ny = by - ay, -(bx - ax)
    nn = math.hypot(nx, ny)
    def sheen(x, y):
        d = np.abs((x - dx - ax) * nx + (y - dy - ay) * ny) / nn
        t = np.clip(1 - d / SHEEN_W, 0, 1)[..., None]
        return np.array(CAP_GOLD, float) * (1 - t) + np.array(CAP_SHEEN, float) * t
    paint(L, full(half), sheen)
    # scales
    ink = flat(SCALES)
    (rx, ry), ro, ri = RING
    circ = lambda r, rev=False: [(rx + r * math.cos(t) + dx, ry + r * math.sin(t) + dy)
                                 for t in (np.linspace(2 * math.pi, 0, 64) if rev else np.linspace(0, 2 * math.pi, 64))]
    paint(L, circ(ro) + circ(ri, True), ink)
    paint(L, [(x + dx, y + dy) for x, y in BEAM_TOP] +
          [(2 * A - x + dx, y + dy) for x, y in BEAM_TOP[::-1]] + [(2 * A - x + dx, y + dy) for x, y in BEAM_BOTTOM] +
          [(x + dx, y + dy) for x, y in BEAM_BOTTOM[::-1]], ink)
    (px, py), hw, depth = PAN
    def line(p, q):
        ux, uy = q[0] - p[0], q[1] - p[1]
        n = math.hypot(ux, uy)
        ox, oy = -uy / n * STRING_W / 2, ux / n * STRING_W / 2
        return [(p[0] + ox, p[1] + oy), (q[0] + ox, q[1] + oy), (q[0] - ox, q[1] - oy), (p[0] - ox, p[1] - oy)]
    for m in (False, True):
        f = (lambda x: 2 * A - x) if m else (lambda x: x)
        P = lambda pts: [(f(x) + dx, y + dy) for x, y in pts]
        (hx, hy), h_out, h_in = HOOK
        u = lambda r, a, b: [(hx + r * math.cos(t), hy + r * math.sin(t)) for t in np.linspace(a, b, 24)]
        paint(L, P(u(h_out, 0, math.pi) + u(h_in, math.pi, 0)), ink)
        paint(L, P([(px + hw * math.cos(t), py + depth * math.sin(t)) for t in np.linspace(math.pi, 0, 40)]), ink)
        top = BEAM_BOTTOM[0]
        for q in [(px - hw + 1, py), (px, py), (px + hw - 1, py)]:
            paint(L, P(line(top, q)), ink)
    return to_image(L)


def disc():
    k = SIZE / REF * SS
    big = Image.new('RGBA', (SIZE * SS, SIZE * SS))
    d = ImageDraw.Draw(big)
    ring = lambda r, fill: d.ellipse((C[0] * k - r * k, C[1] * k - r * k, C[0] * k + r * k, C[1] * k + r * k), fill=fill)
    ring(DISC_R, NAVY + (255,))
    for ri, ro in RINGS:
        ring(ro, GOLD + (255,))
        ring(ri, NAVY + (255,))
    return big.resize((SIZE, SIZE), Image.LANCZOS)


def arc_text():
    img = Image.new('RGBA', (SIZE, SIZE))
    lay_text(img, TOP_TEXT, CLARENDON, ARC_CAP, WHITE, arc(ARC_R, *TOP_SPAN), orig=TOP_ORIG)
    lay_text(img, BOTTOM_TEXT, CLARENDON, ARC_CAP, WHITE, arc(ARC_R, *BOTTOM_SPAN), orig=BOTTOM_ORIG)
    return img


def band():
    k = SIZE / REF * SS
    dx, dy = C[0] - REF_CENTRE[0], C[1] - REF_CENTRE[1]
    big = Image.new('RGBA', (SIZE * SS, SIZE * SS))
    d = ImageDraw.Draw(big)
    box = lambda b: ((b[0] + dx) * k, (b[1] + dy) * k, (b[2] + dx) * k, (b[3] + dy) * k)
    d.rectangle(box(BAND_OUT), fill=NAVY + (255,))
    d.rectangle(box(BAND_IN), fill=WHITE + (255,))
    img = big.resize((SIZE, SIZE), Image.LANCZOS)
    for text, orig, colour, cap, y, (x0, x1) in BAND_LINES:
        lay_text(img, text, CLARENDON, cap, colour, [(x0 + dx, y + dy), (x1 + dx, y + dy)], orig=orig)
    return img


def ribbon_text():
    """Each word runs along its panel's centre line (halfway between the panel's top and bottom edges)."""
    img = Image.new('RGBA', (SIZE, SIZE))
    dx, dy = C[0] - REF_CENTRE[0], C[1] - REF_CENTRE[1]
    def edge(pts):
        p = np.array(spline(pts))
        o = np.argsort(p[:, 0])
        return lambda x: np.interp(x, p[o, 0], p[o, 1])
    left_top, left_bot = edge(LEFT[0]), edge(LEFT[2])
    c_top, c_bot = edge(CENTRE_TOP), edge(CENTRE_BOTTOM)
    mirror = lambda f: (lambda x: f(2 * RIBBON_AXIS - x))
    panels = [(left_top, left_bot, RIBBON_TEXT_X[0]), (c_top, c_bot, RIBBON_TEXT_X[1]),
              (mirror(left_top), mirror(left_bot), tuple(2 * RIBBON_AXIS - x for x in RIBBON_TEXT_X[0][::-1]))]
    for word, (top, bot, (x0, x1)), bend in zip(RIBBON_TEXT, panels, RIBBON_BEND):
        xs = np.linspace(x0, x1, 200)
        # the centre line is a cubic fitted to the middle of the span: near the panel ends the edges curl or stop,
        # which would bend the line and tilt the end letters
        inner = np.linspace(x0 + 0.15 * (x1 - x0), x1 - 0.15 * (x1 - x0), 50)
        ys = (top(inner) + bot(inner)) / 2
        if x0 < RIBBON_AXIS < x1:          # the centre panel is symmetric: fit it with its mirror image so both ends match
            inner, ys = np.concatenate([inner, 2 * RIBBON_AXIS - inner]), np.concatenate([ys, ys])
        fit = np.poly1d(np.polyfit(inner, ys, 3))
        chord = np.poly1d(np.polyfit([x0, x1], [fit(x0), fit(x1)], 1))      # bend scales the sag away from the chord
        mid = [(x + dx, chord(x) + bend * (fit(x) - chord(x)) + dy) for x in xs]
        # largest cap (within RIBBON_CAP) at which the word fits with RIBBON_SQUEEZE or less; a long word gets
        # smaller letters rather than squeezed ones
        h = float(np.mean(bot(xs) - top(xs)))
        length = float(np.hypot(*np.diff(np.array(mid), axis=0).T).sum())
        f = font(TIMES, 100)
        per_cap = sum(f.getlength(ch) for ch in word) / 100 * RIBBON_SQUEEZE + RIBBON_TRACK * (len(word) - 1)
        cap = min(RIBBON_CAP[1] * h, max(RIBBON_CAP[0] * h, length / per_cap))
        lay_text(img, word, TIMES, cap, RED, mid, gap=RIBBON_TRACK * cap)
    return img


def to_image(L):
    a = L[..., 3:]
    rgb = np.where(a > 0, L[..., :3], 0)
    return Image.fromarray(np.concatenate([rgb, a * 255], -1).round().clip(0, 255).astype(np.uint8), 'RGBA')


if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    layers = [('fbi_spikes', to_image(spikes())), ('fbi_disc', disc()), ('fbi_arc_text', arc_text()),
              ('fbi_stars', stars()), ('fbi_emblem', emblem()), ('fbi_ribbon', to_image(ribbon())), ('fbi_ribbon_text', ribbon_text()), ('fbi_band', band())]
    seal = Image.new('RGBA', (SIZE, SIZE))
    for name, im in layers:
        im.save(os.path.join(OUT, name + '.png'))
        seal.alpha_composite(im)
    seal.save(os.path.join(OUT, 'fbi_seal.png'))
    # check: ours at reference scale, beside the reference
    comp = Image.new('RGBA', (SIZE, SIZE), (60, 60, 60, 255))
    comp.alpha_composite(seal)
    ours = comp.resize((int(REF), int(REF)), Image.LANCZOS)
    ref = Image.new('RGBA', (int(REF), int(REF)), (60, 60, 60, 255))
    src = Image.open(os.path.join(HERE, 'warning-sources', 'fbi_apw.png')).convert('RGBA')
    ref.alpha_composite(src, (int(C[0] - REF_CENTRE[0]), int(C[1] - REF_CENTRE[1])))
    check = Image.new('RGB', (2 * int(REF), int(REF)))
    check.paste(ours.convert('RGB'), (0, 0))
    check.paste(ref.convert('RGB'), (int(REF), 0))
    check.save(os.path.join(OUT, 'fbi_check.png'))
    print('wrote', OUT)
