# The TV screensaver

ShelfLife comes with a screensaver, **ShelfLife DVD**: the DVD Video logo bouncing around a black screen, changing colour at every wall. It installs with the app (`DvdDream.kt`, declared in `AndroidManifest.xml`). The logo is the one the app downloads on first launch (see [extras.md](extras.md)), so open ShelfLife once before using it.

## Choosing it

- **Android TV** (most TVs and boxes): Settings → Device Preferences → Screen saver → ShelfLife DVD. ShelfLife's own Settings → TV screensaver → **Use as screensaver** opens that page.
- **Google TV** (Onn 4K Pro, Chromecast with Google TV): the screen saver page only offers Google's Ambient mode, so set it once over adb:

  ```
  adb shell settings put secure screensaver_components com.shelflife.tv/.DvdDream
  ```

  To go back to Google's Ambient mode:

  ```
  adb shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop
  ```

  The TV starts it after the idle time set in Settings → System → Energy (or Ambient mode).

## Effects

ShelfLife's Settings → **TV screensaver**:

| Setting | What it does | Default |
|---|---|---|
| Speed | Slow, Normal or Fast drift | Normal |
| Logo size | Small, Medium or Large | Medium |
| Glow | A soft glow around the logo in its colour | On |
| Scanlines | A dark line every third pixel row, like a tube TV | Off |
| Corner flash | The screen flashes white when the logo lands in a corner (at most every 3 s) | On |

The colour follows **DVD screensaver** under Room: Colorful, Grey or White. If that's set to Off, the TV screensaver uses Colorful, since choosing a screensaver means you want it.

This project was made with help from Claude Code and Codex.
