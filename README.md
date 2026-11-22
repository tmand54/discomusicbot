# SnapStack — fast TCG scanner & collection tracker (Android)

SnapStack is an Android app for trading-card collectors. You scan cards with the camera, it
prices them, and it keeps track of your collection's value and profit/loss. It covers **Pokémon,
Magic: The Gathering, Yu-Gi-Oh! and Disney Lorcana**. There is no account, no ads and no
server: your collection lives on your phone.

## Install

Ready-built APKs are in [`dist/`](dist):

| File | For |
| --- | --- |
| `SnapStack-1.0.0-arm64-v8a.apk` (≈16 MB) | Almost every Android phone from 2017 on |
| `SnapStack-1.0.0-universal.apk` (≈34 MB) | Any device, including emulators and old 32-bit phones |

Copy the APK to your phone and open it. Android asks you to allow installing from that source
the first time. Requires Android 8.0 (API 26) or newer.

> These APKs are signed with a development key. To update to a build that was signed with a
> different key, uninstall first, and use **Settings → Back up collection** beforehand so you
> can restore your data.

## Why it's fast

Scanning is built to keep cards moving, without a pause between them:

* **On-device OCR on every frame.** ML Kit text recognition is bundled in the APK. It reads the
  printed collector number (`PAL EN 123/193`, `0123 M · MKM • EN`, `LOB-EN001`,
  `12/204 • EN • 5`) straight from the camera feed at roughly 1600×1200, with no shutter press
  and no photo upload. The scanner also skips text outside the on-screen guide.
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
* **Portfolio:** total value, P&L against purchase prices, a value-over-time chart, a breakdown
  by game, your most valuable cards, and changes since each card was added.
* **Collection:** search, sort and filter by game, plus set progress (owned / total, with the
  missing cards shown dimmed) and a wantlist.
* **Card details:** prices for each printing, price history, your copies, links to TCGplayer
  and eBay sold listings, and sharing.
* **Copies:** each copy records printing, condition, quantity, purchase price, grading
  (PSA / BGS / CGC / SGC / TAG with slab value) and notes.
* **Price alerts:** get a notification when a card rises above or falls below a target.
  Prices refresh daily in the background.
* **Import a pasted list** (`4 Lightning Bolt`, `2x 123/193`, `LOB-EN001`).
* **Backup / restore** to a JSON file, and **CSV export**.

## Data sources

All of these are free public APIs and need no key:

| Game | Source | Prices |
| --- | --- | --- |
| Pokémon (EN) | [TCGdex](https://tcgdex.dev) | TCGplayer market, per printing |
| Magic | [Scryfall](https://scryfall.com/docs/api) | USD / foil / etched |
| Yu-Gi-Oh! | [YGOPRODeck](https://ygoprodeck.com/api-guide/) | per set printing & rarity |
| Lorcana | [Lorcast](https://lorcast.com/docs/api) | USD / foil |

SnapStack is not affiliated with any card game publisher, TCGplayer, or the data providers.

## Build from source

You need JDK 17+ and the Android SDK (platform 36).

```bash
./gradlew testDebugUnitTest          # parser / voting / matching tests
LIVE_API=1 ./gradlew testDebugUnitTest --tests '*LiveSourcesTest'   # hits the real APIs
./gradlew assembleRelease            # APKs in app/build/outputs/apk/release/
```

Without a keystore, release builds are signed with the local debug key. To sign with your own
key, set `SNAPSTACK_KEYSTORE`, `SNAPSTACK_KEYSTORE_PASSWORD`, `SNAPSTACK_KEY_ALIAS` and
`SNAPSTACK_KEY_PASSWORD`. In CI, the same values go in repository secrets, with the keystore
base64-encoded as `SNAPSTACK_KEYSTORE_BASE64`. The GitHub Actions workflow builds APKs on
every push and attaches them to a GitHub Release when you push a `v*` tag.

### Project layout

```
app/src/main/java/app/snapstack/
  scan/        CardTextParser (OCR text → card numbers), FrameVoter, TextAnalyzer (CameraX + ML Kit), ScanSession
  data/        CardRepository (memory → device → network + set prefetch), CollectionRepository, Settings
  data/source/ TCGdex, Scryfall, YGOPRODeck and Lorcast clients
  data/db/     SQLite storage with change-notifying flows
  ui/          Jetpack Compose screens: portfolio, scan, collection, card, search, settings
  work/        Daily price refresh + alert notifications
```
