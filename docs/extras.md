# Extras: marks that aren't in the repo

Studio intros and the format logos belong to other people, so neither the source nor the APK includes them. The app fetches each one once into its own storage (`files/extras/`). Anything it can't get is left out:
- no intro before the film,
- no Blu-ray mark on the spine,
- no DVD logo in the screensaver,
- no studio logo on the case back.

Anything missing is tried again on the next launch. The warning card is ShelfLife's own and is built into the app; see [Warning cards](warning-cards.md), which also records the FBI seal and Interpol emblem this used to download. The code is in `android/app/src/main/kotlin/com/shelflife/tv/Extras.kt`.

| What | Where it comes from |
|---|---|
| DVD Video and Blu-ray logos | Wikimedia Commons, live (`DVD-Video_Logo.svg`, `Blu-ray_Disc.svg`), turned white in the app |
| Studio logos (case backs) | TMDB, live (company ids 2, 4, 25, 174, 5, 33). Needs `TMDB_API_KEY`. |
| Feature Presentation intros and the QuickPlay background | https://speelyberg.com/shelflife/assets, or your own server at `EXTRAS_URL` |

## The intro videos

They're renders made outside the repo, so they have no public source. Every build downloads them from **https://speelyberg.com/shelflife/assets** on first launch, with nothing to set up:

```
feature_presentation.mp4             (Disney-style)
feature_presentation_paramount.mp4
feature_presentation_fox.mp4
feature_presentation_warner.mp4
feature_presentation_sony.mp4
feature_presentation_universal.mp4
quickplay_bg.mp4
```

The app requests `<EXTRAS_URL>/<file name>` for each. Once they're on the TV, it never asks again (a fresh install does).

### How speelyberg.com serves them

A Cloudflare Worker serves them from an R2 bucket. How it's set up, and the website it also serves, is in [website.md](website.md).

### Using your own copy

Put the seven files in a folder and serve it over HTTPS (or plain HTTP on your home network). Then set the address in `android/secrets.env`, which git ignores, and rebuild:

```
EXTRAS_URL=https://your-server.example.com/shelflife
```

`EXTRAS_URL=` with nothing after it builds an app with no intros.
