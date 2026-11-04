# ShelfLife

Your Plex film library as DVD cases on a shelf in a 3D living room. Browse the shelf, take a case down, turn it over, and put the disc in the player. From there it plays like a DVD: menu, warnings, Feature Presentation intro, then the film.

It's an Android TV app (`android/`), built and tuned on an Onn 4K Pro. Website: [speelyberg.com/shelflife](https://speelyberg.com/shelflife).

What it does:
- **The room:** a TV, a DVD player, a bookcase and a tower of 144 cases, each spine printed from its film's poster colours. Hold OK to shuffle the shelf.
- **Search:** ▲ past the top shelf opens a search bar over your whole library; results show as DVD cases.
- **A case in your hands:** the real cover, a printed back, and the room blurring behind it.
- **The disc:** the case opens, the disc goes into the player, then QuickPlay, previews, ShelfLife's anti-spoiler warning card, a Feature Presentation intro matched to the film's studio, and the film from Plex. Every film gets a disc menu with Special Features and Scene Selection.
- **TV screensaver:** the bouncing DVD logo, installed with the app, with adjustable effects.

## Status

- The source is on GitHub at speelyberg/ShelfLife, open source under the MIT licence ([LICENSE](LICENSE)).
- The intro videos are hosted at speelyberg.com/shelflife/assets, and every build downloads them from there by default.
- Latest notes: [2 November 2026](docs/2026-11-02-search-screensaver-and-website.md).

## Android TV

### Setup

Create `android/secrets.env` (git ignores it):

```
TMDB_API_KEY=<your TMDB v3 key>          # production-company logos on case backs, studio logos
EXTRAS_URL=https://<your server>          # optional: your own copy of the intros (default speelyberg.com), see docs/extras.md
```

Both are optional. Without a TMDB key the case backs have no studio logos; without EXTRAS_URL the intros come from speelyberg.com.

### Build and install

```
cd android
./gradlew assembleRelease                 # needs JDK 21 and the Android SDK (android/local.properties)
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the local debug key, which is enough for sideloading onto your own TV.

### Sign in to Plex

On the TV, open Settings (◀ in the room) and connect Plex: enter the code shown at plex.tv/link. Until then the shelf holds sample films.

### Use it as the TV's screensaver

Open ShelfLife once (it downloads the DVD logo), then choose **ShelfLife DVD** as the screensaver. On Google TV that takes one adb command; see [docs/screensaver.md](docs/screensaver.md).

### Assets that aren't in the repo

Studio intros and the DVD and Blu-ray logos are other people's marks:
- The logos download from Wikimedia Commons and TMDB.
- The intro videos download from speelyberg.com/shelflife/assets (or your own copy).

See [docs/extras.md](docs/extras.md).

## Docs

- [Search, the warning card, the TV screensaver and the website, 2 November 2026](docs/2026-11-02-search-screensaver-and-website.md): today's work, what's live, and what's left.
- [Website](docs/website.md): speelyberg.com, the Worker and bucket behind it, and how to change the page.
- [Extras](docs/extras.md): where the intros and logos come from, and how to serve your own copy of the videos.
- [TV screensaver](docs/screensaver.md): using the bouncing DVD logo as your TV's screensaver, and its effects.
- [Warning cards](docs/warning-cards.md): the ShelfLife anti-spoiler card, how it's made, and the FBI and Interpol cards it replaced.
- [Performance and sound pass, 1 November 2026](docs/2026-11-01-performance-and-sound.md): shader fixes, the pre-rendered disc insert, 3D resolution, sound timing and the new sounds, every sound effect's source, and preparing the repo for GitHub.

## Credits

Sound effects are CC0 recordings from Freesound; the list is in the performance doc. The Inter typeface is under the SIL Open Font License ([its licence](android/app/src/main/assets/licenses/Inter-OFL.txt)). Film metadata and artwork come from your own Plex server; company logos come from TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB.
