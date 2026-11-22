# TCG Portfolio — fast TCG scanner & collection tracker (Android)

TCG Portfolio is an Android app for trading-card collectors. You scan cards with the camera, it
prices them, and it keeps track of your collection's value and profit/loss. It covers **Pokémon,
Magic: The Gathering, Yu-Gi-Oh!, Disney Lorcana, One Piece, Riftbound and Dragon Ball**
(Fusion World and the Super Card Game). There is no account, no ads and no
server: your collection lives on your phone.

## Install

Ready-built APKs are in [`dist/`](dist):

| File | For |
| --- | --- |
| `TCG-Portfolio-1.12.1-arm64-v8a.apk` (≈16 MB) | Almost every Android phone from 2017 on |
| `TCG-Portfolio-1.12.1-universal.apk` (≈34 MB) | Any device, including emulators and old 32-bit phones |

Each version installs over the previous one as an update and keeps your collection.

**Coming from 1.8.0 or earlier?** From 1.9.0 the app's package name is `app.tcgportfolio` (it was
`app.snapstack`). Android treats it as a different app, so it installs next to the old one instead
of updating it. To move your data: in the old app, **Settings → Back up collection**; in the new
app, **Settings → Restore a backup**; then uninstall the old one.

Copy the APK to your phone and open it. Android asks you to allow installing from that source
the first time. Requires Android 8.0 (API 26) or newer.

> These APKs are signed with a development key. To update to a build that was signed with a
> different key, uninstall first, and use **Settings → Back up collection** beforehand so you
> can restore your data.

## Why it's fast

Scanning is built to keep cards moving, without a pause between them:

* **On-device OCR on every frame.** ML Kit text recognition is bundled in the APK. It reads the
  printed collector number (`PAL EN 123/193`, `0123 M · MKM • EN`, `LOB-EN001`,
  `12/204 • EN • 5`, `OP01-001`, `OGN • 179/298`, `FB01-001`, `BT22-064`) straight from the camera feed at roughly 1600×1200, with no shutter press
  and no photo upload. The scanner also skips text outside the on-screen guide.
* **Only the card is read.** Each frame, just the scan-guide area's luminance is copied into a
  small grayscale buffer, with no colour conversion, and handed to OCR. That's about half the
  frame, so a higher-resolution camera stream (1920×1440) gives sharper digits for about the same
  OCR time. Text around the card is never read.
* **Blurry frames are skipped.** A sub-millisecond sharpness check drops motion-blurred frames,
  which cause most misreads, and the camera refocuses on the card if it stays blurry.
* **Reads are checked against your card list.** Each read is looked up in the set lists already on
  the phone. If the card that number points to has its name printed on the card in view, one frame
  is enough (the guide box turns green). If the number points to a card with a different name, the
  read needs an extra frame and is flagged for review.
* **One-digit misreads are corrected.** If "128" is read but the name printed on the card is #123 in
  that set, #123 is used. Corrected reads are never cached under the misread number.
* **Set lists load when the scanner opens,** so the first card doesn't wait for them.
* **Artwork picks the printing.** When several printings share a number (One Piece / Dragon Ball /
  Riftbound parallels and alt arts, DON!! cards, Pokémon cards with the same number in different
  sets), the scanner compares a tiny colour picture of the card in view with each candidate's catalog
  image. If one clearly wins, it's chosen automatically; otherwise the picker lists them by
  look-alike. Only those few thumbnails are fetched, and they're cached.
* **Frame voting instead of a confirm tap.** A card is committed once its number reads the same
  in 1–3 frames (Turbo / Balanced / Careful). A card held in view is never counted twice. Take
  it away and show it again to count another copy.
* **The camera never waits on the network.** Each card goes straight into the tray as
  "resolving" while the camera keeps reading. Lookups run in parallel, and identical in-flight
  lookups are merged.
* **Per-set prefetch.** The first card from a set downloads that whole set's list in the
  background. The rest of the pack or binder page then resolves on the phone in milliseconds,
  and still works with no signal. You can also download sets ahead of time
  (**Settings → Offline card data**).
* **Regular vs. parallel.** One Piece and Riftbound alt arts and parallels share the
  regular card's number. TCG Portfolio picks the regular card and lists the special printings as
  one-tap alternatives. It flags the card only when one of those alternatives is worth much more.
* **Choose a printing once.** When printings (Holo, Reverse Holo, Foil) are priced differently,
  the card is flagged but never blocks scanning. Pick a printing once and it applies to the rest
  of the stack. Printings that cost the same aren't flagged.
* **Warm connections, memory cache, and retries with backoff** for flaky APIs.

The scanner shows its own speed on screen (`OCR 48 ms · 14 fps`), along with how each card was
found ("Found in 12 ms · on device").

## Features

* **Scan modes:** Rapid (cards in a stream), Single (confirm each card), and Binder (one photo
  of a 2×2, 3×3, 3×4 or 4×4 page adds every pocket).
* **Snap button** takes a full-resolution photo for stubborn cards. **Type #** lets you enter a
  number by hand.
* **Rips:** give the session a name and the pack price to see your pull value against cost. A
  history of rips appears on the Portfolio tab.
* **Portfolio:** total value and a performance chart for **1D, 5D, 1M, 6M, 1Y and All**, with the
  gain and % return for the interval, plus **ROI**, amount invested and profit for the whole collection.
  Cards you add or remove aren't counted as gains or losses: each snapshot also records the invested
  basis (price paid, or the market price when added) and the % return is a Modified Dietz return.
  Press and drag on the chart to read any point. Also a breakdown by game, your most valuable cards,
  and changes since each card was added.
* **Collection:** search, sort and filter by game, plus set progress (owned / total, with the
  missing cards shown dimmed) and a wantlist. Hold a card to start selecting, tap more (or
  **Select all**), then **Delete** to remove them all at once, with Undo.
* **Card details:** prices for each printing, price history, your copies, links to TCGplayer
  and eBay sold listings, and sharing.
* **Copies:** each copy records printing, condition, quantity, purchase price, grading
  (PSA / BGS / CGC / SGC / TAG with slab value) and notes.
* **Price alerts:** get a notification when a card rises above or falls below a target.
  Prices refresh daily in the background.
* **Import a pasted list** (`4 Lightning Bolt`, `2x 123/193`, `LOB-EN001`).
* **Backup / restore** to a JSON file, **CSV export**, and **Delete collection** to start over
  (with an option to also clear the wantlist and price alerts).
* **Import from list or CSV:** paste a card list, or choose a CSV or another collection app's
  backup file. Find it in Settings, on the empty Portfolio, or under Search → Import list or CSV.
  See below.
* **One Piece variants:** every printing of a code is offered when you scan it, labelled with its
  printing (for example, Ulti OP04-043: Regular or Judge Pack Vol. 3). This includes parallels,
  Judge / tournament / prize promos, pre-release and release-event stamps, and Premium Booster
  reprints. Promo and reprint sets download once in the background (a few MB). Card pages show the
  same variants.
* **Japanese One Piece:** Japanese copies are picked up automatically. A code from a set that's out
  in Japan but not yet in English is added as Japanese, with the official Japanese image. Any other
  card can be switched to Japanese in its sheet. Japanese cards share codes and art with English, so
  they use the English catalog. There's no free Japanese price feed, so Japanese copies are valued at
  the English price; set your own value on a copy, or check the Yuyu-tei link on the card page.
* **One Piece DON!! cards:** the scanner recognises the DON!! logo. You then pick which DON!! card
  it is from a filterable list, with cards from the set you're opening shown first.

## Import from list or CSV

**Settings → Import from list or CSV** gives you two options:

* **Paste a list,** one card per line: `4 Lightning Bolt`, `2x 123/193`, `OP01-001`,
  `LOB-EN001 x3`, `DON!!`. Pick a game for lines that are only a name. Pasted CSV text works too.
* **Choose a file:** a CSV, a plain-text list, a TCG Portfolio backup, or another collection app's
  backup. The format is detected automatically.

### Moving over from Ripfolio

1. In Ripfolio, go to **Settings → Back up**. That saves a file named `ripfolio-backup-YYYY-MM-DD.json`.
2. In TCG Portfolio, go to **Import from list or CSV → Choose a file** and pick that file.
3. Each card is matched by game, set, number and name against the same databases the scanner
   uses, then priced and added to your collection. Quantity, condition, foil, purchase price,
   grading (company, grade, slab value, cert #) and the date added are carried over.

The result screen lists anything that couldn't be matched, and you can copy that list. Some things
can't come over:

* **Sold, traded or removed cards.** Only cards you still own are imported.
* **Sealed products.**
* **Ripfolio's wantlist and custom lists.** The backup stores only Ripfolio's internal card IDs
  for these, not names.
* **Games TCG Portfolio doesn't price yet,** such as Pokémon Japan and Dragon Ball.

CSV files also work: Ripfolio's CSV export, TCG Portfolio's own, or any CSV with a card-name column.
Optional columns are set, number, game, quantity, condition, printing, purchase price and
grading. Restoring a TCG Portfolio backup through the same button replaces your collection, and
asks you to confirm first.

## Data sources

All of these are free public APIs and need no key:

| Game | Source | Prices |
| --- | --- | --- |
| Pokémon (EN) | [TCGdex](https://tcgdex.dev) | TCGplayer market, per printing |
| Magic | [Scryfall](https://scryfall.com/docs/api) | USD / foil / etched |
| Yu-Gi-Oh! | [YGOPRODeck](https://ygoprodeck.com/api-guide/) | per set printing & rarity |
| Lorcana | [Lorcast](https://lorcast.com/docs/api) | USD / foil |
| One Piece | [tcgcsv.com](https://tcgcsv.com) (daily TCGplayer catalog) | TCGplayer market, Normal / Foil |
| Riftbound | [tcgcsv.com](https://tcgcsv.com) (daily TCGplayer catalog) | TCGplayer market, Normal / Foil |
| Dragon Ball (Fusion World + Super Card Game) | [tcgcsv.com](https://tcgcsv.com) (daily TCGplayer catalog) | TCGplayer market, Normal / Foil |

One Piece, Riftbound and Dragon Ball card lists are downloaded a set at a time. Scanning a card downloads its
set automatically, and the Search tab has a one-tap **Download all sets** button for offline use.

**Backup sources** keep things working when a source is down or missing data:

| When… | TCG Portfolio falls back to |
| --- | --- |
| A Pokémon, Magic, Yu-Gi-Oh! or Lorcana card has no price, or its source is unreachable | TCGplayer's catalog (tcgcsv.com), matched by set, number and name |
| TCGdex is down, or doesn't have a brand-new Pokémon set yet | TCGplayer's catalog, using the set code printed on the card (`PAL`, `DLR`…) |
| TCGdex doesn't list a new Black Star promo yet (e.g. Kyogre `MEP 117`) | TCGplayer's promo set for that era, when scanning, typing the code, or searching by name |
| Searching One Piece by name | [optcgapi.com](https://optcgapi.com), so you don't need every set downloaded |
| tcgcsv.com can't price a One Piece card | optcgapi.com market prices |

I couldn't find a second Riftbound source that responded reliably, so Riftbound uses TCGplayer's
catalog only.

**Dragon Ball codes:**

* **Fusion World** (`FB01-001`, `FS01-01`, `SB01-…`, promos `FP-006`) and recent Super Card Game
  boosters (`BT22-064`) map straight to their set.
* **Older Super Card Game codes** map to TCGplayer's own set codes: boosters `BT5` → `DBS-B05`,
  tournament packs `TB` → `DBS-TB…`, expansions `EX` → `DBS-BE…`, and promos `P-…`.
* **Codes both games print:** `ST01-001`, `EB01-…` and `P-001` exist in One Piece and Dragon Ball. In Auto
  mode, the card's other text (Saiyan, Combo, Awaken… vs DON!!, Counter…) decides. Picking the game
  in the scanner removes any doubt.
* **Starter-deck codes (`SD…`, `XD…`)** are filed inside booster sets on TCGplayer, so they resolve
  once that set is downloaded. Use Search → Download all sets for Dragon Ball to cover them all.
* **The old Panini Dragon Ball Z game** isn't included.

TCG Portfolio is not affiliated with any card game publisher, TCGplayer, or the data providers.

## Build from source

You need JDK 17+ and the Android SDK (platform 36).

```bash
./gradlew testDebugUnitTest          # parser / voting / matching tests
LIVE_API=1 ./gradlew testDebugUnitTest --tests '*LiveSourcesTest'   # hits the real APIs
./gradlew assembleRelease            # APKs in app/build/outputs/apk/release/
./gradlew bundleRelease              # Google Play bundle (needs your upload key)
```

Without a keystore, release APKs are signed with the local debug key. To sign with your own key,
create `keystore.properties` in the project root (`storeFile`, `storePassword`, `keyAlias`,
`keyPassword`) or set `SNAPSTACK_KEYSTORE`, `SNAPSTACK_KEYSTORE_PASSWORD`, `SNAPSTACK_KEY_ALIAS`
and `SNAPSTACK_KEY_PASSWORD`. `bundleRelease` refuses to run without a key, because Play rejects
debug-signed bundles. In CI, the same values go in repository secrets, with the keystore
base64-encoded as `SNAPSTACK_KEYSTORE_BASE64`. The GitHub Actions workflow builds APKs on every
push (plus the Play bundle when the key secrets are set) and attaches the APKs to a GitHub Release
when you push a `v*` tag.

## Look

The **Vault** theme pairs ink-black (dark mode) or warm paper (light mode) with the logo's gold and
teal. The logo is a gold card with a rising value line inside scanner brackets, and Android 13+
themed icons get an outline version. See [`theme-preview.png`](docs/play-store/graphics/theme-preview.png).

## Publishing on Google Play

Everything for the Play Store is in [`docs/play-store/`](docs/play-store/):

* [`RELEASE.md`](docs/play-store/RELEASE.md): step by step, from creating your upload key to production.
* [`listing.md`](docs/play-store/listing.md): store listing text, screenshots to take, release notes.
* [`policy-answers.md`](docs/play-store/policy-answers.md): Data safety, content rating, target audience
  and other App content answers.
* [`graphics/`](docs/play-store/graphics/): 512×512 icon and 1024×500 feature graphic.
* [`docs/privacy-policy.html`](docs/privacy-policy.html): privacy policy to host (also in the app
  under Settings → About).

### Project layout

```
app/src/main/java/app/tcgportfolio/
  scan/        CardTextParser (OCR text → card numbers), FrameVoter, TextAnalyzer (CameraX + ML Kit), ScanSession
  data/        CardRepository (memory → device → network + set prefetch), CollectionRepository, Settings
  data/importer/ Ripfolio / CSV / TCG Portfolio backup import (parsing + card matching)
  data/source/ TCGdex, Scryfall, YGOPRODeck, Lorcast and tcgcsv (One Piece, Riftbound) clients
  data/db/     SQLite storage with change-notifying flows
  ui/          Jetpack Compose screens: portfolio, scan, collection, card, search, settings
  work/        Daily price refresh + alert notifications
```
