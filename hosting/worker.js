// speelyberg.com, as a Cloudflare Worker (see docs/extras.md):
//   /shelflife/assets/<file>  the intro videos (and the page's pictures), from the R2 bucket bound as ASSETS, with Range support
//   /shelflife                the ShelfLife page
//   /                         "speelyberg" in soft white pixel letters on black
// Anything else is 404.
const PREFIX = '/shelflife/assets/';
const PROJECT = 'https://github.com/speelyberg/ShelfLife';
const V = 3;   // bump when the page's pictures change: browsers keep them a day, and a new ?v= makes them fetch again

const PAGE = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>ShelfLife: Nostalgia. Now on demand.</title>
<meta name="description" content="An Android TV app that puts your Plex film library on a shelf of DVD cases in a 3D living room.">
<meta property="og:title" content="ShelfLife">
<meta property="og:description" content="Nostalgia. Now on demand. Your Plex films as DVD cases on a shelf in a 3D living room.">
<meta property="og:image" content="https://speelyberg.com/shelflife/assets/shelflife-room.jpg">
<style>
/* Hallmark · shape: feature stack, drawn 9be813, swapped to a ritual rail · genre: editorial · theme: brand · redesign: full
 * paper: oklch(18.7% 0.02 266) · accent: oklch(85% 0.146 90) · display: system sans 700 (the logo's face) · body: system sans · hero: Banner · nav: edge-aligned · footer: colophon · signature: the ritual before the film as a filmstrip rail, in order
 * axes: dark/grotesk-sans/warm · posture: Restrained · contrast: pass · fold: pass · v2.0.0 */
/* Hallmark · pre-emit critique: P4 H4 E4 S5 R4 V4 · weakest: P, the page argues that a room beats a grid; the room shot leads at full width to make it */
/* Hallmark · waive 1 · display is the system sans at 700 · ShelfLife's logo and banner are drawn in systemFont bold (make_brand.swift), so the page keeps that brand face */
/* Hallmark · waive 79 · the room figure follows the hero section with no gap · the ShelfLife living room screenshot fills the fold under the tagline, so nothing is empty */
/* Hallmark · answered F4 · the filled tab is a selected state in the browse switcher, not a second primary button */
:root {
  --space-3xs: 2px; --space-2xs: 4px; --space-xs: 8px; --space-sm: 12px; --space-md: 16px; --space-lg: 24px; --space-xl: 40px; --space-2xl: 64px; --space-3xl: 96px; --space-4xl: 144px;
  --text-sm: 0.875rem; --text-md: 1.0625rem;
  --text-display-s: clamp(2.25rem, 4vw + 0.5rem, 3.75rem); --text-display: clamp(2.75rem, 5vw + 1rem, 5.25rem);
  --text-section: clamp(2rem, 2.5vw + 1rem, 3.5rem); --text-title: clamp(1.25rem, 0.75vw + 0.875rem, 1.625rem); --text-lead: clamp(1.125rem, 0.35vw + 1rem, 1.375rem);
  --ease-out: cubic-bezier(0.16, 1, 0.3, 1);
  --dur-micro: 120ms; --dur-short: 220ms;
  --container: 1440px; --gutter: 64px; --gap-columns: 32px; --section-gap-s: 120px; --section-gap: 144px;
  /* The logo's backdrop (work/icon/make_brand.swift drawBackdrop): projector blue, lighter in the middle, with a faint warm lamp glow */
  --color-paper: oklch(18.7% 0.02 266); --color-paper-2: oklch(26% 0.031 263); --color-paper-3: oklch(36.9% 0.039 259);
  --color-lamp: oklch(92.6% 0.043 89);
  --color-ink: oklch(95.3% 0.013 87); --color-ink-2: oklch(80% 0.02 83); --color-muted: oklch(70% 0.018 83);
  --color-rule: oklch(40% 0.03 260);
  --color-accent: oklch(85% 0.146 90); --color-accent-2: oklch(90% 0.12 92); --color-accent-ink: oklch(19.7% 0.03 93); --color-focus: oklch(86% 0.18 90);
  --font-display: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, system-ui, sans-serif; --font-body: var(--font-display);
  --radius-card: 10px; --radius-pill: 999px;
  --tracking-display: -0.025em;
}
*, *::before, *::after { box-sizing: border-box; }
html, body { overflow-x: clip; }
html { background: var(--color-paper); scrollbar-color: var(--color-paper-3) var(--color-paper); scroll-padding-top: var(--space-xl); }
body { margin: 0; color: var(--color-ink); font-family: var(--font-body); font-size: var(--text-md); line-height: 1.6; -webkit-font-smoothing: antialiased; caret-color: var(--color-accent); }
/* Pinned to the window, so it stays put as the page scrolls (a fixed layer, since phones ignore background-attachment: fixed) */
body::before { content: ""; position: fixed; inset: 0; z-index: -1;
  background: radial-gradient(closest-corner circle at 50% 50%, color-mix(in oklab, var(--color-lamp) 9%, transparent), color-mix(in oklab, var(--color-lamp) 3.5%, transparent) 35%, transparent 75%),
              radial-gradient(farthest-corner circle at 50% 50%, var(--color-paper-3), var(--color-paper-2) 55%, var(--color-paper)); }
::selection { background: color-mix(in oklab, var(--color-accent) 30%, transparent); color: var(--color-ink); }
:focus-visible { outline: 2px solid var(--color-focus); outline-offset: 3px; }
a { color: inherit; text-decoration-thickness: 1px; text-underline-offset: 0.18em; transition: color var(--dur-short) var(--ease-out); }
a:hover { color: var(--color-accent-2); }
h1, h2, h3 { margin: 0; font-family: var(--font-display); font-weight: 700; font-synthesis: none; letter-spacing: var(--tracking-display); line-height: 1.05; overflow-wrap: break-word; min-width: 0; text-wrap: balance; }
h1 { max-width: 14ch; font-size: var(--text-display); line-height: 1; }
h1 span { display: block; }
h2 { max-width: 20ch; font-size: var(--text-section); }
h3 { font-size: var(--text-title); line-height: 1.2; letter-spacing: -0.01em; }
p, ul, ol, dl, dd, figure { margin: 0; }
p { text-wrap: pretty; color: var(--color-ink-2); max-width: 62ch; }
.lead { font-size: var(--text-lead); line-height: 1.45; color: var(--color-ink); max-width: 44ch; }
b { color: var(--color-ink); font-weight: 700; }
img, video { display: block; width: 100%; height: auto; border-radius: min(1vw, var(--radius-card)); outline: 1px solid color-mix(in oklab, var(--color-ink) 9%, transparent); outline-offset: -1px; background: var(--color-paper-2); }
.container { width: 100%; max-width: var(--container); margin-inline: auto; padding-inline: var(--gutter); }
.grid { display: grid; grid-template-columns: repeat(12, minmax(0, 1fr)); column-gap: var(--gap-columns); }
[hidden] { display: none !important; }
button:disabled { opacity: 0.55; cursor: not-allowed; }
.clip { position: relative; }
.osd { position: absolute; top: var(--space-md); left: var(--space-md); z-index: 10; display: flex; gap: var(--space-3xs); padding: var(--space-3xs); border-radius: var(--radius-pill); background: var(--color-paper); }
.osd[hidden] { display: none; }
.osd button, .carousel__nav button { display: inline-grid; place-items: center; width: 44px; height: 44px; padding: 0; border: 0; border-radius: var(--radius-pill); background: transparent; color: var(--color-ink); cursor: pointer; transition: background-color var(--dur-short) var(--ease-out), color var(--dur-short) var(--ease-out), transform var(--dur-micro) var(--ease-out); }
.osd button:hover, .carousel__nav button:hover { background: var(--color-paper-3); }
.osd button:active, .carousel__nav button:active { transform: scale(0.94); }
.osd button[aria-pressed="true"] { color: var(--color-accent); }
.osd button:focus-visible, .carousel__nav button:focus-visible { outline: 2px solid var(--color-focus); outline-offset: -2px; }
.osd__play, .osd__pause { display: contents; }
.osd [data-osd="play"]:not(.is-paused) .osd__play, .osd [data-osd="play"].is-paused .osd__pause { display: none; }
.ritual__text { grid-column: 1 / span 5; display: grid; gap: var(--space-md); }
.ritual__head .clip { grid-column: 7 / -1; }
.visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }

/* Controls */
.btn { display: inline-flex; align-items: center; min-height: 52px; padding: 0 var(--space-lg); border: 1px solid var(--color-accent); border-radius: var(--radius-pill); background: var(--color-accent); color: var(--color-accent-ink); font: 600 var(--text-md)/1 var(--font-body); white-space: nowrap; text-decoration: none; cursor: pointer; transition: background-color var(--dur-short) var(--ease-out), transform var(--dur-micro) var(--ease-out); }
.btn:hover { background: var(--color-accent-2); color: var(--color-accent-ink); }
.btn:active { transform: translateY(1px); }
.btn:focus-visible { outline: 2px solid var(--color-focus); outline-offset: 3px; }

/* Nav: the name and the one action (the GitHub button), edge to edge */
.nav { display: flex; align-items: center; justify-content: space-between; min-height: 72px; }
.wordmark { font: 700 var(--text-title)/1 var(--font-display); letter-spacing: -0.02em; text-decoration: none; }

/* Hero: the line and the summary above, the room across the whole width below and into the fold */
.hero { padding-block: var(--space-2xl) var(--space-xl); align-items: end; row-gap: var(--space-lg); }
.hero h1 { grid-column: 1 / span 7; }
.hero__aside { grid-column: 8 / -1; display: grid; gap: var(--space-lg); justify-items: start; padding-bottom: var(--space-xs); }
.room { margin-bottom: var(--section-gap); }

/* Intro: the memory on the left, three things the room does on the right */
.intro { row-gap: var(--space-xl); margin-bottom: var(--section-gap); }
.intro__head { grid-column: 1 / span 5; display: grid; gap: var(--space-lg); align-content: start; }
.rows { grid-column: 7 / -1; list-style: none; padding: 0; border-top: 1px solid var(--color-rule); }
.rows li { display: grid; gap: var(--space-xs); padding-block: var(--space-xl); border-bottom: 1px solid var(--color-rule); }
.rows p { max-width: 48ch; }

/* Switcher: shelf, search, case */
.browse { margin-bottom: var(--section-gap); }
.browse__head { display: flex; flex-wrap: wrap; align-items: end; justify-content: space-between; gap: var(--space-lg); margin-bottom: var(--space-2xl); }
.tabs { display: flex; gap: var(--space-2xs); padding: var(--space-2xs); border: 1px solid var(--color-rule); border-radius: var(--radius-pill); }
.tabs button { min-height: 44px; padding: 0 var(--space-md); border: 0; border-radius: var(--radius-pill); background: transparent; color: var(--color-ink-2); font-weight: 600; cursor: pointer; transition: background-color var(--dur-short) var(--ease-out), color var(--dur-short) var(--ease-out); }
.tabs button:hover { color: var(--color-ink); background: var(--color-paper-2); }
.tabs button:active { background: var(--color-paper-3); }
.tabs button[aria-selected="true"] { background: var(--color-ink); color: var(--color-paper); }
.tabs button:focus-visible { outline-offset: -2px; }
.tabs button[aria-selected="true"]:focus-visible { outline-color: var(--color-accent-ink); }
.tabs[hidden] { display: none; }
.panel { align-items: center; row-gap: var(--space-xl); }
.panel + .panel { margin-top: var(--space-3xl); }
.js .panel + .panel { margin-top: 0; }
.panel__pics { grid-column: 1 / span 8; display: grid; gap: var(--space-md); }
.panel__pics--pair { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.panel__text { grid-column: 9 / -1; display: grid; gap: var(--space-lg); }
.facts { display: grid; gap: var(--space-md); padding: 0; list-style: none; color: var(--color-ink-2); }
.facts li { padding-top: var(--space-md); border-top: 1px solid var(--color-rule); }

/* The ritual: a filmstrip that runs past the edge, in the order it plays */
.ritual { margin-bottom: var(--section-gap); }
.ritual__head { display: grid; grid-template-columns: repeat(12, minmax(0, 1fr)); column-gap: var(--gap-columns); row-gap: var(--space-xl); align-items: center; margin-bottom: var(--space-2xl); }
.rail { display: grid; grid-auto-flow: column; grid-auto-columns: minmax(17rem, 30%); gap: var(--gap-columns); margin: 0; padding: 0 max(var(--gutter), calc((100% - var(--container)) / 2 + var(--gutter))) var(--space-md); list-style: none; counter-reset: step; overflow-x: auto; scroll-snap-type: x mandatory; scroll-padding-inline: max(var(--gutter), calc((100% - var(--container)) / 2 + var(--gutter))); overscroll-behavior-x: contain; scrollbar-width: thin; }
.rail > li { display: grid; gap: var(--space-md); align-content: start; scroll-snap-align: start; counter-increment: step; }
.rail h3::before { content: counter(step) ". "; color: var(--color-accent); }
.rail:focus-visible { outline: 2px solid var(--color-focus); outline-offset: 4px; }
.ritual__after { margin-top: var(--space-xl); }

/* The menu */
.menu { align-items: center; row-gap: var(--space-xl); margin-bottom: var(--section-gap); }
.menu__text { grid-column: 1 / span 4; display: grid; gap: var(--space-lg); }
.carousel { grid-column: 6 / -1; min-width: 0; }
.carousel__track { display: grid; grid-auto-flow: column; grid-auto-columns: 100%; gap: 0; margin: 0; padding: 0; list-style: none; overflow-x: auto; scroll-snap-type: x mandatory; overscroll-behavior-x: contain; scrollbar-width: none; }
.carousel__track > li { scroll-snap-align: start; min-width: 0; }
.carousel__nav { display: flex; align-items: center; gap: var(--space-xs); margin-top: var(--space-md); }
.carousel__nav[hidden] { display: none; }
.carousel__nav button { border: 1px solid var(--color-rule); }
.carousel__count { margin-left: var(--space-xs); font-size: var(--text-sm); color: var(--color-muted); font-variant-numeric: tabular-nums; }

/* Get it: a change of paper, the home-screen banner beside the way in */
.get { padding-block: var(--section-gap-s); background: color-mix(in oklab, var(--color-paper) 70%, transparent); border-block: 1px solid var(--color-rule); }
.get .grid { align-items: center; row-gap: var(--space-xl); }
.get__text { grid-column: 1 / span 6; display: grid; gap: var(--space-lg); justify-items: start; }
.get figure { grid-column: 8 / -1; }
figcaption { margin-top: var(--space-sm); font-size: var(--text-sm); color: var(--color-muted); }

/* Footer */
.foot { padding-block: var(--space-2xl) var(--space-3xl); display: grid; gap: var(--space-md); }
.foot p { font-size: var(--text-sm); color: var(--color-muted); max-width: 72ch; }

@media (max-width: 60rem) {
  :root { --gutter: 24px; --section-gap-s: 80px; --section-gap: 96px; }
  .ritual__text, .ritual__head .clip, .hero h1, .hero__aside, .intro__head, .rows, .panel__pics, .panel__text, .menu__text, .carousel, .get__text, .get figure { grid-column: 1 / -1; }
  .hero { padding-top: var(--space-xl); }
  .rail { grid-auto-columns: minmax(16rem, 70%); }
}
@media (max-width: 40rem) {
  :root { --gutter: 16px; --gap-columns: 16px; --section-gap-s: 56px; --section-gap: 72px; }
  .rail { grid-auto-columns: 85%; }
}
@media (prefers-reduced-motion: reduce) { *, *::before, *::after { transition-duration: 0.01ms !important; scroll-behavior: auto !important; } }
</style>
</head>
<body>
<header class="container nav">
  <a class="wordmark" href="/shelflife" aria-label="ShelfLife home">ShelfLife</a>
  <a class="btn" href="${PROJECT}">Source on GitHub</a>
</header>

<main>
  <section class="container grid hero">
    <h1>Nostalgia. <span>Now on demand.</span></h1>
    <div class="hero__aside">
      <p class="lead">Your Plex films as DVD cases on a shelf, on Android TV.</p>
    </div>
  </section>
  <figure class="container room" data-kind="product">
    <img src="/shelflife/assets/shelflife-room.jpg?v=${V}" width="1600" height="900" fetchpriority="high" alt="The ShelfLife living room: a TV showing the DVD Video screensaver, a tall DVD tower full of cases, and a bookcase">
  </figure>

  <section class="container grid intro">
    <div class="intro__head">
      <h2>Popping in a movie at home</h2>
      <p class="lead">Back in the day: a friend’s DVD tower, a menu looping while someone made popcorn, a warning card you couldn’t skip. ShelfLife brings that back, with your own Plex films.</p>
    </div>
    <ul class="rows">
      <li><h3>A room, not a grid</h3><p>A TV, a DVD player, and a shelf full of <i>your</i> films, with easy controls.</p></li>
      <li><h3>The bouncing logo</h3><p>Maybe it’ll hit the corner. It works as a real TV screensaver too.</p></li>
      <li><h3>Shuffle</h3><p>Hold OK in the room to restock the shelf with a different random set of films.</p></li>
    </ul>
  </section>

  <section class="container browse" data-switch>
    <div class="browse__head">
      <h2>Browse like you used to</h2>
      <div class="tabs" role="tablist" aria-label="Browsing" hidden>
        <button type="button" role="tab" id="t-shelf" aria-controls="p-shelf" aria-selected="true">The shelf</button>
        <button type="button" role="tab" id="t-search" aria-controls="p-search" aria-selected="false" tabindex="-1">Search</button>
        <button type="button" role="tab" id="t-case" aria-controls="p-case" aria-selected="false" tabindex="-1">The case</button>
      </div>
    </div>
    <div class="grid panel" role="tabpanel" id="p-shelf" aria-labelledby="t-shelf">
      <div class="panel__pics" data-kind="product"><figure class="clip" data-kind="product"><video muted loop playsinline preload="none" width="1280" height="720" poster="/shelflife/assets/shot-shelf.jpg?v=${V}" aria-label="Moving from the room to the DVD tower, then along a shelf as each highlighted case tips out with its title below"><source src="/shelflife/assets/clip-shelf.webm?v=${V}" type="video/webm"><source src="/shelflife/assets/clip-shelf.mp4?v=${V}" type="video/mp4"></video><div class="osd" hidden><button type="button" data-osd="rew" aria-label="Back two seconds"><svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="currentColor" stroke="currentColor" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"><polygon points="11 19 2 12 11 5 11 19"/><polygon points="22 19 13 12 22 5 22 19"/></svg></button><button type="button" data-osd="play" aria-label="Pause"><span class="osd__pause"><svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="currentColor" stroke="currentColor" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"><rect x="6" y="4" width="4" height="16" rx="1"/><rect x="14" y="4" width="4" height="16" rx="1"/></svg></span><span class="osd__play"><svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="currentColor" stroke="currentColor" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"><polygon points="6 3 20 12 6 21 6 3"/></svg></span></button><button type="button" data-osd="ff" aria-label="Double speed" aria-pressed="false"><svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="currentColor" stroke="currentColor" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"><polygon points="13 19 22 12 13 5 13 19"/><polygon points="2 19 11 12 2 5 2 19"/></svg></button></div></figure></div>
      <div class="panel__text">
        <p class="lead">Move along a shelf and the highlighted case tips out, title below.</p>
        <ul class="facts">
          <li><b>Spines</b> are printed from each film’s poster colours, with the title, rating, year and a DVD, Blu-ray or 4K UHD band.</li>
          <li><b>Films you’ve watched</b> get a “Previously Viewed” sticker.</li>
        </ul>
      </div>
    </div>
    <div class="grid panel" role="tabpanel" id="p-search" aria-labelledby="t-search">
      <div class="panel__pics" data-kind="product"><img src="/shelflife/assets/shot-search.jpg?v=${V}" width="1600" height="900" loading="lazy" alt="The search bar above the shelf with results for night shown as DVD cases, five to a row, over the blurred room"></div>
      <div class="panel__text">
        <p class="lead">Find any film in your library.</p>
        <ul class="facts">
          <li><b>Press up past the top shelf</b> for a search bar.</li>
          <li><b>Results show as DVD cases,</b> five to a row, over the blurred room.</li>
          <li><b>Pick one</b> and it goes on the shelf and into your hands.</li>
        </ul>
      </div>
    </div>
    <div class="grid panel" role="tabpanel" id="p-case" aria-labelledby="t-case">
      <div class="panel__pics" data-kind="product"><figure class="clip" data-kind="product"><video muted loop playsinline preload="none" width="1280" height="720" poster="/shelflife/assets/clip-case.jpg?v=${V}" aria-label="Forrest Gump tips out of the shelf, is held up beside its synopsis card, and is turned over to its printed back"><source src="/shelflife/assets/clip-case.webm?v=${V}" type="video/webm"><source src="/shelflife/assets/clip-case.mp4?v=${V}" type="video/mp4"></video><div class="osd" hidden><button type="button" data-osd="rew" aria-label="Back two seconds"><svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="currentColor" stroke="currentColor" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"><polygon points="11 19 2 12 11 5 11 19"/><polygon points="22 19 13 12 22 5 22 19"/></svg></button><button type="button" data-osd="play" aria-label="Pause"><span class="osd__pause"><svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="currentColor" stroke="currentColor" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"><rect x="6" y="4" width="4" height="16" rx="1"/><rect x="14" y="4" width="4" height="16" rx="1"/></svg></span><span class="osd__play"><svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="currentColor" stroke="currentColor" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"><polygon points="6 3 20 12 6 21 6 3"/></svg></span></button><button type="button" data-osd="ff" aria-label="Double speed" aria-pressed="false"><svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="currentColor" stroke="currentColor" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"><polygon points="13 19 22 12 13 5 13 19"/><polygon points="2 19 11 12 2 5 2 19"/></svg></button></div></figure>
      <div class="panel__pics panel__pics--pair" data-kind="product">
        <img src="/shelflife/assets/shot-case-front.jpg?v=${V}" width="1600" height="900" loading="lazy" alt="Home Alone’s DVD case held up, showing its cover, with a details card beside it">
        <img src="/shelflife/assets/shot-case-back.jpg?v=${V}" width="1600" height="900" loading="lazy" alt="The same case turned round: a printed back with the synopsis, a Blu-ray band and a Previously Viewed sticker">
      </div>
      </div>
      <div class="panel__text">
        <p class="lead">Turn it over. And over.</p>
        <ul class="facts">
          <li><b>The front</b> has the poster. <b>The back</b> has the synopsis, running time, rating, audio and video details, and the studio’s logo.</li>
          <li><b>Left and right</b> turn the case; the room blurs behind it.</li>
          <li><b>A card</b> beside it shows the full synopsis.</li>
        </ul>
      </div>
    </div>
  </section>

  <section class="ritual" aria-labelledby="ritual-head">
    <div class="container ritual__head">
      <div class="ritual__text">
        <h2 id="ritual-head">The ritual, in order</h2>
        <p class="lead">Pop in the disc. Settings can turn off the warning card, QuickPlay and the bumpers.</p>
      </div>
      <figure class="clip" data-kind="product"><video muted loop playsinline preload="none" width="1280" height="720" poster="/shelflife/assets/clip-disc.jpg?v=${V}" aria-label="The case opens, the disc lifts out, and the camera turns to the DVD player as the tray takes it"><source src="/shelflife/assets/clip-disc.webm?v=${V}" type="video/webm"><source src="/shelflife/assets/clip-disc.mp4?v=${V}" type="video/mp4"></video><div class="osd" hidden><button type="button" data-osd="rew" aria-label="Back two seconds"><svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="currentColor" stroke="currentColor" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"><polygon points="11 19 2 12 11 5 11 19"/><polygon points="22 19 13 12 22 5 22 19"/></svg></button><button type="button" data-osd="play" aria-label="Pause"><span class="osd__pause"><svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="currentColor" stroke="currentColor" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"><rect x="6" y="4" width="4" height="16" rx="1"/><rect x="14" y="4" width="4" height="16" rx="1"/></svg></span><span class="osd__play"><svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="currentColor" stroke="currentColor" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"><polygon points="6 3 20 12 6 21 6 3"/></svg></span></button><button type="button" data-osd="ff" aria-label="Double speed" aria-pressed="false"><svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="currentColor" stroke="currentColor" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"><polygon points="13 19 22 12 13 5 13 19"/><polygon points="2 19 11 12 2 5 2 19"/></svg></button></div></figure>
    </div>
    <ol class="rail" tabindex="0" aria-label="What happens before the film, in order">
      <li data-kind="product"><img src="/shelflife/assets/shot-disc.jpg?v=${V}" width="1600" height="900" loading="lazy" alt="The case open with the disc lifting out, its label printed with the film’s logo"><h3>The case opens</h3><p>The disc comes out, with the film’s logo on its label.</p></li>
      <li data-kind="product"><img src="/shelflife/assets/shot-player.jpg?v=${V}" width="1600" height="900" loading="lazy" alt="The disc on its way into the DVD player under the TV"><h3>The tray opens</h3><p>The disc goes in, with recorded sounds for each step.</p></li>
      <li data-kind="product"><img src="/shelflife/assets/shot-quickplay.jpg?v=${V}" width="1600" height="900" loading="lazy" alt="The QuickPlay card: a spinning disc, and QuickPlay, Previews and Main Menu buttons with a countdown"><h3>QuickPlay</h3><p>Counts down, then starts the film. Or pick Previews: trailers from your own library.</p></li>
      <li data-kind="product"><img src="/shelflife/assets/shot-warning.jpg?v=${V}" width="1600" height="900" loading="lazy" alt="ShelfLife’s anti-spoiler warning card: a parody seal on black beside the warning text"><h3>The warning card</h3><p>An anti-spoiler parody. Like the real ones, you can’t skip it for three seconds.</p></li>
      <li data-kind="product"><img src="/shelflife/assets/shot-bumper-paramount.jpg?v=${V}" width="1600" height="900" loading="lazy" alt="Gold FEATURE PRESENTATION lettering on a purple gradient"><h3>Feature Presentation</h3><p>A bumper matched to the film’s studio.</p></li>
      <li data-kind="product"><img src="/shelflife/assets/shot-bumper-disney.jpg?v=${V}" width="1600" height="900" loading="lazy" alt="Thin white Feature lettering over PRESENTATION on a deep blue background crossed by fine lines"><h3>One per studio</h3><p>Paramount, Disney, Fox, Warner, Sony or Universal.</p></li>
    </ol>
    <p class="container ritual__after">Then the film plays from your Plex server.</p>
  </section>

  <section class="container grid menu">
    <div class="menu__text">
      <h2>Every film gets a disc menu</h2>
      <ul class="facts">
        <li><b>Play Movie, Resume or Start Over,</b> plus audio and subtitle settings.</li>
        <li><b>Scene Selection</b> from the film’s chapters, four to a page.</li>
        <li><b>Special Features:</b> trailers and extras from Plex.</li>
        <li><b>Stop any time</b> and it resumes where you left off.</li>
      </ul>
    </div>
    <div class="carousel" data-carousel>
      <ul class="carousel__track" tabindex="0" aria-label="Disc menu screens">
        <li><figure data-kind="product"><img src="/shelflife/assets/shot-menu.jpg?v=${V}" width="1600" height="900" loading="lazy" alt="Home Alone’s disc menu: title and details, Play Movie, Special Features and Settings, beside its cover"><figcaption>The main menu, built from the film’s own artwork.</figcaption></figure></li>
        <li><figure class="clip" data-kind="product"><video muted loop playsinline preload="none" width="1280" height="720" poster="/shelflife/assets/shot-scenes.jpg?v=${V}" aria-label="Back to the Future’s Scene Selection: chapters five to eight as stills, the highlight moving from one to the next"><source src="/shelflife/assets/clip-scenes.webm?v=${V}" type="video/webm"><source src="/shelflife/assets/clip-scenes.mp4?v=${V}" type="video/mp4"></video><div class="osd" hidden><button type="button" data-osd="rew" aria-label="Back two seconds"><svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="currentColor" stroke="currentColor" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"><polygon points="11 19 2 12 11 5 11 19"/><polygon points="22 19 13 12 22 5 22 19"/></svg></button><button type="button" data-osd="play" aria-label="Pause"><span class="osd__pause"><svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="currentColor" stroke="currentColor" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"><rect x="6" y="4" width="4" height="16" rx="1"/><rect x="14" y="4" width="4" height="16" rx="1"/></svg></span><span class="osd__play"><svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="currentColor" stroke="currentColor" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"><polygon points="6 3 20 12 6 21 6 3"/></svg></span></button><button type="button" data-osd="ff" aria-label="Double speed" aria-pressed="false"><svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="currentColor" stroke="currentColor" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"><polygon points="13 19 22 12 13 5 13 19"/><polygon points="2 19 11 12 2 5 2 19"/></svg></button></div><figcaption>Scene Selection: pick a chapter from its still.</figcaption></figure></li>
        <li><figure data-kind="product"><img src="/shelflife/assets/shot-features.jpg?v=${V}" width="1600" height="900" loading="lazy" alt="Love Actually’s Special Features page listing its trailer, beside the cover"><figcaption>Special Features: trailers and extras from Plex.</figcaption></figure></li>
      </ul>
      <div class="carousel__nav" hidden>
        <button type="button" data-step="-1" aria-label="Previous screen"><svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="currentColor" stroke="currentColor" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"><polygon points="19 20 9 12 19 4 19 20"/><line x1="5" x2="5" y1="19" y2="5"/></svg></button>
        <button type="button" data-step="1" aria-label="Next screen"><svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="currentColor" stroke="currentColor" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"><polygon points="5 4 15 12 5 20 5 4"/><line x1="19" x2="19" y1="5" y2="19"/></svg></button>
        <span class="carousel__count" aria-live="polite">1 of 3</span>
      </div>
    </div>
  </section>

  <section class="get" id="get">
    <div class="container grid">
      <div class="get__text">
        <h2>Free and open source</h2>
        <p>An Android TV app, built and tested on an Onn 4K Pro. Build it from the source, install it on your TV, and sign in to Plex with a plex.tv/link code. Until you sign in, the shelf holds sample films.</p>
        <a class="btn" href="${PROJECT}">Source on GitHub</a>
      </div>
      <figure data-kind="product">
        <img src="/shelflife/assets/shelflife-banner.png?v=${V}" width="1280" height="720" loading="lazy" alt="ShelfLife: a film strip holding three shelves, beside the name">
        <figcaption>The banner on your TV’s home screen.</figcaption>
      </figure>
    </div>
  </section>
</main>

<footer class="container foot">
  <a class="wordmark" href="/shelflife" aria-label="ShelfLife home">ShelfLife</a>
  <p>Film details and artwork come from your own Plex server; company logos from TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB. ShelfLife is not affiliated with Plex or any film studio. Film titles and artwork shown belong to their owners.</p>
</footer>

<script>
// Tabs for the browse section. Without script every panel shows, one after another.
document.querySelectorAll('[data-switch]').forEach(function (box) {
  var tabs = Array.prototype.slice.call(box.querySelectorAll('[role="tab"]'));
  box.querySelector('[role="tablist"]').hidden = false;
  document.documentElement.classList.add('js');
  function pick(tab, focus) {
    tabs.forEach(function (t) {
      var on = t === tab;
      t.setAttribute('aria-selected', on); t.tabIndex = on ? 0 : -1;
      document.getElementById(t.getAttribute('aria-controls')).hidden = !on;
    });
    if (focus) tab.focus();
  }
  tabs.forEach(function (t, i) {
    t.addEventListener('click', function () { pick(t); });
    t.addEventListener('keydown', function (e) {
      var d = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0;
      if (d) { e.preventDefault(); pick(tabs[(i + d + tabs.length) % tabs.length], true); }
    });
  });
  pick(tabs[0]);
});
// Clips loop while on screen, never under reduced motion, with DVD-style controls: back two seconds, play or pause, double speed.
var still = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
var seen = 'IntersectionObserver' in window && new IntersectionObserver(function (es) {
  es.forEach(function (e) { var v = e.target; if (e.isIntersecting && !v.dataset.paused) v.play().catch(function () {}); else v.pause(); });
}, { threshold: 0.4 });
document.querySelectorAll('.clip').forEach(function (fig) {
  var v = fig.querySelector('video'), osd = fig.querySelector('.osd');
  var play = osd.querySelector('[data-osd="play"]'), ff = osd.querySelector('[data-osd="ff"]');
  function show() { var p = !!v.dataset.paused; play.classList.toggle('is-paused', p); play.setAttribute('aria-label', p ? 'Play' : 'Pause'); }
  osd.hidden = false;
  if (still) { v.dataset.paused = '1'; v.preload = 'metadata'; }
  show();
  play.addEventListener('click', function () {
    if (v.dataset.paused) { delete v.dataset.paused; v.play().catch(function () {}); } else { v.dataset.paused = '1'; v.pause(); }
    show();
  });
  osd.querySelector('[data-osd="rew"]').addEventListener('click', function () { v.currentTime = Math.max(0, v.currentTime - 2); });
  ff.addEventListener('click', function () { var on = v.playbackRate === 1; v.playbackRate = on ? 2 : 1; ff.setAttribute('aria-pressed', on); });
  if (seen) seen.observe(v);
});
// The disc menu carousel: swipe or scroll it, or step with the chapter buttons.
document.querySelectorAll('[data-carousel]').forEach(function (box) {
  var track = box.querySelector('.carousel__track'), nav = box.querySelector('.carousel__nav'), count = box.querySelector('.carousel__count');
  var n = track.children.length;
  function at() { return Math.round(track.scrollLeft / track.clientWidth); }
  nav.hidden = false;
  nav.querySelectorAll('[data-step]').forEach(function (b) {
    b.addEventListener('click', function () { var i = (at() + Number(b.dataset.step) + n) % n; track.scrollTo({ left: i * track.clientWidth, behavior: still ? 'auto' : 'smooth' }); });
  });
  track.addEventListener('scroll', function () { count.textContent = (at() + 1) + ' of ' + n; }, { passive: true });
});
</script>
</body>
</html>`;

// A 5 x 9 pixel font, just the letters needed: rows 0-1 ascender, 2-6 x-height, 7-8 descender.
const GLYPHS = {
  s: ['', '', '.####', '#....', '.###.', '....#', '####.'],
  p: ['', '', '####.', '#...#', '#...#', '####.', '#....', '#....', '#....'],
  e: ['', '', '.###.', '#...#', '#####', '#....', '.###.'],
  l: ['.##..', '..#..', '..#..', '..#..', '..#..', '..#..', '.###.'],
  y: ['', '', '#...#', '#...#', '#...#', '.####', '....#', '....#', '.###.'],
  b: ['#....', '#....', '####.', '#...#', '#...#', '#...#', '####.'],
  r: ['', '', '#.##.', '##..#', '#....', '#....', '#....'],
  g: ['', '', '.####', '#...#', '#...#', '.####', '....#', '....#', '.###.'],
};
function pixelText(text) {
  const rects = [];
  [...text].forEach((ch, i) => GLYPHS[ch].forEach((row, y) => [...row].forEach((px, x) => {
    if (px === '#') rects.push(`<rect x="${i * 6 + x}" y="${y}" width="1" height="1"/>`);
  })));
  // Soft-edged pixels with a faint glow, like a low-res picture on an old TV; sizes are in pixels, so it scales.
  const soft = '<filter id="soft" x="-20%" y="-50%" width="140%" height="200%"><feGaussianBlur stdDeviation="0.22" result="b"/>' +
    '<feGaussianBlur stdDeviation="0.9" result="g"/><feComponentTransfer in="g" result="glow"><feFuncA type="linear" slope="0.55"/></feComponentTransfer>' +
    '<feMerge><feMergeNode in="glow"/><feMergeNode in="b"/></feMerge></filter>';
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="-2 -2 ${text.length * 6 + 3} 13" fill="#fff" role="img" aria-label="${text}"><defs>${soft}</defs><g filter="url(#soft)" shape-rendering="crispEdges">${rects.join('')}</g></svg>`;
}
const HOME = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>speelyberg</title>
<style>
  html, body { margin: 0; height: 100%; background: #000; }
  body { display: grid; place-items: center; }
  svg { width: min(72vw, 900px); height: auto; }
</style>
</head>
<body>${pixelText('speelyberg')}</body>
</html>`;

export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname;
    if (path === '/') return new Response(HOME, { headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'public, max-age=300' } });
    if (path === '/shelflife/') return Response.redirect(new URL('/shelflife', request.url), 302);
    if (path === '/shelflife') return new Response(PAGE, { headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'public, max-age=300' } });
    if (!path.startsWith(PREFIX)) return new Response('Not found', { status: 404 });
    if (request.method !== 'GET' && request.method !== 'HEAD') return new Response('Method not allowed', { status: 405, headers: { Allow: 'GET, HEAD' } });

    const key = decodeURIComponent(path.slice(PREFIX.length));
    const object = await env.ASSETS.get(key, { range: request.headers, onlyIf: request.headers });
    if (object === null) return new Response('Not found', { status: 404 });

    const headers = new Headers();
    object.writeHttpMetadata(headers);
    headers.set('etag', object.httpEtag);
    headers.set('accept-ranges', 'bytes');
    headers.set('cache-control', 'public, max-age=86400');
    if (!headers.has('content-type')) headers.set('content-type', key.endsWith('.jpg') ? 'image/jpeg' : key.endsWith('.png') ? 'image/png' : 'video/mp4');
    if (!('body' in object)) return new Response(null, { status: 304, headers });   // If-None-Match matched

    const r = object.range;
    if (r && request.headers.has('range')) {
      const start = r.offset ?? object.size - r.suffix;
      const length = r.length ?? object.size - start;
      headers.set('content-range', `bytes ${start}-${start + length - 1}/${object.size}`);
      headers.set('content-length', String(length));
      return new Response(request.method === 'HEAD' ? null : object.body, { status: 206, headers });
    }
    headers.set('content-length', String(object.size));
    return new Response(request.method === 'HEAD' ? null : object.body, { headers });
  },
};
