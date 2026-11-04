"""ShelfLife's parody of the modern (Blu-ray era) FBI anti-piracy warning card: black, the seal on the left, a short
white warning on the right. Uses the seal from make_fbi_seal.py (work/fbi-seal/fbi_seal.png; run that first).

Writes work/fbi-seal/fbi_card.png (1920x1080).  Run: python3 work/make_fbi_card.py"""
import os
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
W, H, SS = 1920, 1080, 2
BG, INK = (0, 0, 0), (255, 255, 255)
SEAL_CENTRE, SEAL_D = (520, 540), 640                  # seal centre and diameter, px
TEXT_X, TEXT_W = 920, 860                              # text block: left edge and width, px
HEAD = ('/System/Library/Fonts/HelveticaNeue.ttc', 1, 56)       # Bold; path, index, size px
BODY = ('/System/Library/Fonts/HelveticaNeue.ttc', 0, 38)       # Regular
LEAD, GAP = 1.3, 40                                    # line height as a share of size; space between paragraphs, px
TEXT = [('head', 'SHELFLIFE ANTI-SPOILER WARNING'),
        ('body', 'Unauthorized spoiling of this motion picture is punishable under the laws of movie night.'),
        ('body', 'Talking during the film, checking your phone, or revealing the ending is investigated by the '
                 'Freshly Buttered Investigators and is punishable by up to 5 minutes in the kitchen and a fine of '
                 'one (1) large popcorn.')]


def font(spec):
    return ImageFont.truetype(spec[0], spec[2] * SS, index=spec[1])


def wrap(text, f, width):
    lines, line = [], ''
    for word in text.split():
        trial = (line + ' ' + word).strip()
        if f.getlength(trial) <= width or not line:
            line = trial
        else:
            lines.append(line)
            line = word
    return lines + [line]


if __name__ == '__main__':
    card = Image.new('RGB', (W * SS, H * SS), BG)
    seal = Image.open(os.path.join(HERE, 'fbi-seal', 'fbi_seal.png')).convert('RGBA')
    bbox = seal.getbbox()                              # trim the canvas to the seal's spikes
    seal = seal.crop(bbox).resize((SEAL_D * SS, round(SEAL_D * SS * (bbox[3] - bbox[1]) / (bbox[2] - bbox[0]))), Image.LANCZOS)
    card.paste(seal, (SEAL_CENTRE[0] * SS - seal.width // 2, SEAL_CENTRE[1] * SS - seal.height // 2), seal)
    # lay the paragraphs out, then centre the block vertically
    blocks = []
    for kind, text in TEXT:
        spec = HEAD if kind == 'head' else BODY
        f = font(spec)
        blocks.append((f, spec[2] * SS * LEAD, wrap(text, f, TEXT_W * SS)))
    total = sum(lh * len(lines) for _, lh, lines in blocks) + GAP * SS * (len(blocks) - 1)
    d = ImageDraw.Draw(card)
    y = H * SS / 2 - total / 2
    for f, lh, lines in blocks:
        for line in lines:
            d.text((TEXT_X * SS, y), line, font=f, fill=INK)
            y += lh
        y += GAP * SS
    out = os.path.join(HERE, 'fbi-seal', 'fbi_card.png')
    card.resize((W, H), Image.LANCZOS).save(out)
    print('wrote', out)
