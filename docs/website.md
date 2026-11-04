# speelyberg.com

The website and the intro-video host are one Cloudflare Worker, [`hosting/worker.js`](../hosting/worker.js), on the domain speelyberg.com, with an R2 bucket behind it. Both are on the free tiers: R2 has no bandwidth charge, and the Worker allows 100,000 requests a day.

| Address | What it is |
|---|---|
| `speelyberg.com` | Homepage: "speelyberg" in soft white pixel letters on black (a 5 × 9 pixel font drawn as SVG squares, with a blur and glow) |
| `speelyberg.com/shelflife` | The ShelfLife page |
| `speelyberg.com/shelflife/` | Redirects to `/shelflife` |
| `speelyberg.com/shelflife/assets/<file>` | Files from the R2 bucket: the seven intro videos the app downloads, and the page's pictures. Byte-range requests work; browsers cache them for a day |
| Anything else | 404 |

The source is on GitHub at [speelyberg/ShelfLife](https://github.com/speelyberg/ShelfLife).

## The ShelfLife page

All of it, HTML, CSS and a little script, is the `PAGE` string in `worker.js`. Top to bottom:

1. **Hero:** "Nostalgia. Now on demand." with one line (the GitHub button is in the top bar), then the living-room screenshot across the full width.
2. **Intro:** the memory of popping in a movie, beside three rows (a room, the bouncing logo, shuffle).
3. **Browse like you used to:** tabs for the shelf, search and the case. Without script all three panels show.
4. **The ritual, in order:** the disc clip, then a sideways rail of six stills from the case opening to the studio bumpers.
5. **The disc menu:** notes beside a carousel of the main menu, the Scene Selection clip and Special Features.
6. **Get it** (the banner beside the way in), and the footer.

Clips loop only while on screen and never under reduced motion. Each has DVD-style controls in its top-left corner: back two seconds, play or pause, and double speed.

The background is the logo's radial gradient (`#334054` → `#1c2433` → `#0f131c` with a faint warm glow, from `drawBackdrop` in `work/icon/make_brand.swift`), fixed to the window.

The screenshots are from the Google TV emulator. The bumper stills come from the second half of the Paramount- and Disney-style intros, chosen because they show no logos.

## What's in the R2 bucket (`shelflife-assets`, all at its root)

| File | Source |
|---|---|
| `feature_presentation.mp4`, `feature_presentation_paramount.mp4`, `_fox`, `_warner`, `_sony`, `_universal`, `quickplay_bg.mp4` | The intro renders (not in the repo; see [extras.md](extras.md)) |
| `shelflife-banner.png` | `work/icon/banner@2x.png` |
| `shelflife-room.jpg` | `docs/images/2026-11-01/04-room-at-720p.jpg`, at 1600 × 900 |
| `shot-shelf.jpg`, `shot-search.jpg`, `shot-case-front.jpg`, `shot-case-back.jpg`, `shot-disc.jpg`, `shot-player.jpg`, `shot-quickplay.jpg`, `shot-menu.jpg` | Emulator screenshots, 1600 × 900 (not in the repo) |
| `shot-warning.jpg` | `work/fbi-seal/fbi_card.png`, at 1600 × 900 |
| `shot-bumper-paramount.jpg`, `shot-bumper-disney.jpg` | Frames at 9.8 s and 5.2 s of those intros, 1600 × 900 |
| `clip-shelf`, `clip-case`, `clip-disc`, `clip-scenes` (`.webm` and `.mp4` each) | Emulator recordings (`adb shell screenrecord`), trimmed, 1280 × 720, muted, under 600 KB each. Not in the repo |
| `clip-case.jpg`, `clip-disc.jpg` | Poster frames from those clips |
| `shot-scenes.jpg`, `shot-features.jpg` | Back to the Future's Scene Selection (page 2) and Love Actually's Special Features, 1600 × 900 |

## Changing the site

The Worker isn't deployed from git; its code is pasted into the Cloudflare dashboard.

1. Edit `hosting/worker.js`, then run `node hosting/worker.test.mjs`. It checks the routes, the byte ranges, the page and the homepage against a stand-in bucket.
2. If any picture changed, upload it to the bucket under the same name, and add 1 to `V` at the top of `worker.js`. Image links end in `?v=<V>`, so browsers fetch the new file instead of a day-old copy.
3. Copy the code with `pbcopy < hosting/worker.js`. In Cloudflare, open **Workers & Pages** → `speelyberg` → **Edit code**, select all, paste and **Deploy**.
4. Commit and push.

To check that the live site matches the repo:

```
curl -s https://speelyberg.com/shelflife | diff - <(node --input-type=module -e "import w from './hosting/worker.js'; const r=await w.fetch(new Request('https://speelyberg.com/shelflife'),{}); process.stdout.write(await r.text())")
```

## Setting it up from nothing

1. **Bucket:** in Cloudflare, under **R2**, create `shelflife-assets` and upload the files above. Leave public access off: the Worker reads the bucket directly, so it needs no URL of its own.
2. **Worker:** under **Workers & Pages**, create a Worker named `speelyberg` (start from Hello World), paste in `hosting/worker.js` and deploy.
3. **Binding:** in the Worker's **Settings** → **Bindings**, add an R2 bucket binding: variable `ASSETS`, bucket `shelflife-assets`.
4. **Domain:** in the Worker's **Settings** → **Domains & Routes**, add the custom domain `speelyberg.com`. Cloudflare makes the DNS record and the certificate.
