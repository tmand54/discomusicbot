# Play Console policy answers (Policy → App content)

These answers describe TCG Portfolio 1.8.0 as built from this repository. As the developer, you're
responsible for the final answers. Recheck them if you add analytics, crash reporting, ads or
accounts.

## Privacy policy

Use the URL where you host `docs/privacy-policy.html`. The same policy is in the app under
Settings → About → Privacy policy.

## Ads

**No, my app does not contain ads.**

## App access

**All functionality is available without special access.** There's no login. Reviewers can scan any
card or use Search.

## Content rating (IARC questionnaire)

* Category: **All Other App Types** (a utility / reference app).
* Violence, sexuality, language, controlled substances, crude humor: **No** to all.
* Users can interact or exchange content with each other: **No**.
* Shares the user's current physical location with other users: **No**.
* Allows users to purchase digital goods: **No**.
* Gambling, simulated gambling, or real-money contests: **No**. Recording what a booster pack was
  worth is a price tracker, not gambling.
* Unrestricted internet access (a web browser or search engine): **No**. The app only links out to
  specific shops and price pages.

Expected result: Everyone / PEGI 3 / USK 0.

## Target audience and content

* Target age groups: **13–15, 16–17, 18 and over**. Don't select under-13 groups; that would put
  the app under the Families policy (Designed for Families requirements, certified ad SDKs only, and
  more).
* Appeals to children: the app shows trading card prices and is for collectors. Answer **No** to
  "could unintentionally appeal to children" unless your listing images target children.

## Data safety

### Overview

| Question | Answer |
| --- | --- |
| Does your app collect or share any of the required user data types? | **Yes** (because of Google ML Kit diagnostics, below) |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (every request is HTTPS) |
| Do you provide a way for users to request that their data is deleted? | **No**. The app keeps no data on a server. Everything is deleted by uninstalling or clearing storage, and the only off-device data is Google ML Kit's diagnostics, which you don't hold |

### Data types

**App info and performance → Diagnostics**
* Collected: **Yes**. Shared: **No**.
* Processed ephemerally: **No**.
* Required or optional: **Required** (users can't turn it off).
* Purpose: **Analytics**.
* Why: Google ML Kit, the on-device text reader, sends device information (manufacturer, model,
  OS version), app information and performance metrics to Google for diagnostics and usage
  analytics. Google states this is encrypted in transit and isn't shared with third parties.

**App activity → In-app search history**
* Collected: **Yes**. Shared: **No** (sent to card databases only when the user searches, which
  counts as a user-initiated transfer).
* Processed ephemerally: **Yes** (used to answer the search; the app keeps no server-side log).
* Required or optional: **Optional** (only when the user searches).
* Purpose: **App functionality**.
* Why: search text is sent to the card databases (Scryfall, YGOPRODeck, Lorcast, TCGdex,
  optcgapi.com) to find cards.

Declare nothing else:
* **Photos and videos**: no. Camera frames are processed on the device and never leave it.
* **Personal info, location, contacts, financial info, messages, health, audio, files, calendar**:
  no. The app doesn't access them. Purchase prices typed into the collection stay on the device, so
  they aren't "collected" under Play's definition.
* **Device or other IDs**: no. The app sends no identifier. Google lists identifiers only for ML Kit's
  Play-services ("thin") models, and this app bundles its model.
* Card numbers and names sent to look up a card aren't user data. They identify a product, not a
  person.

## Other declarations

| Declaration | Answer |
| --- | --- |
| Government app | No |
| Financial features | **My app doesn't provide any financial features** (it shows collectible prices; no money moves) |
| Health | No health features |
| News app | No |
| COVID-19 contact tracing / status | No |
| Foreground service permissions | None. The app removes WorkManager's FOREGROUND_SERVICE permission |
| Photo and video permissions | None. The app uses CAMERA only, plus the system file picker for imports |
| Advertising ID | **No**. The app doesn't declare the AD_ID permission, and no SDK in it reads the advertising ID |
