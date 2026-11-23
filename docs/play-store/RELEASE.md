# Selling TCG Portfolio on Google Play for $1

Version **1.0.0** (versionCode 1), package `app.tcgportfolio`. The app meets Play's technical rules:
it targets Android 16 (API 36), its 64-bit native libraries support 16 KB memory pages, it's
shrunk with R8, and it builds a signed Android App Bundle (`.aab`). Everything below is what only
you can do, in order. Expect roughly 3–4 weeks end to end for a new personal account, mostly the
14-day test and Google's reviews.

> **Decide "paid" before the first public release.** Play lets a paid app become free later, but a
> free app can never become paid. Create the app as **Paid** from the start.

## 1. Create a Google Play developer account (day 1)

1. Go to [play.google.com/console](https://play.google.com/console) and sign in with the Google
   account that will own the app. A separate account just for publishing is a good idea.
2. Choose **Yourself** (personal account) or **An organization** (needs a D-U-N-S number).
3. Pay the **US$25** one-time registration fee.
4. Verify your identity (government ID) and your contact phone and email. This can take a few days.
5. Your developer name and contact email are shown publicly on the store page.

Personal accounts created after November 13, 2023 must run a **closed test with at least 12
testers for 14 days in a row** before they can publish publicly (step 9). Organization accounts
don't.

## 2. Set up a payments profile so you can sell (day 1)

1. Play Console → **Settings** → **Payments profile** (or Setup → Payments profile) → create one.
2. Enter your legal name and address, **bank account** for payouts, and **tax information** (in the
   US a W-9; elsewhere the W-8BEN and your local tax ID).
3. Google verifies the bank account, usually with a small test deposit you confirm.

Without a payments profile, the price field stays locked and you can only publish free apps.

What you receive from $1: Google keeps a **15% service fee** (on your first US$1M of yearly
earnings), and in many countries the price includes sales tax or VAT, which Google pays out of
the price. Expect roughly **$0.70–$0.85 per sale**, depending on the buyer's country.

## 3. Create your upload key (once, on your own computer)

You need a JDK (Android Studio includes one). In a terminal:

```
keytool -genkeypair -v -keystore tcg-portfolio-upload.jks -alias upload \
  -keyalg RSA -keysize 4096 -validity 10000
```

Pick a strong password and enter your name when asked. Then:

* **Back up `tcg-portfolio-upload.jks` and its password** somewhere safe, such as a password manager.
  If you lose it, Play support can reset the upload key, but that takes days.
* **Never commit it.** `.gitignore` already ignores `*.jks` and `keystore.properties`.

With Play App Signing (the default), Google holds the key that signs what buyers download. Your
upload key only proves that uploads come from you.

## 4. Build the 1.0.0 bundle

Create `keystore.properties` in the project root (next to `settings.gradle.kts`):

```
storeFile=/full/path/to/tcg-portfolio-upload.jks
storePassword=your-store-password
keyAlias=upload
keyPassword=your-key-password
```

Then:

```
./gradlew testDebugUnitTest bundleRelease
```

The bundle is `app/build/outputs/bundle/release/app-release.aab`. Without a key, `bundleRelease`
stops with an explanation instead of producing a debug-signed bundle that Play would reject.

**Or let GitHub build it:** add these repository secrets (Settings → Secrets and variables →
Actions), and every push produces a `tcg-portfolio-aab` artifact on the workflow run:

| Secret | Value |
| --- | --- |
| `SNAPSTACK_KEYSTORE_BASE64` | Output of `base64 -w0 tcg-portfolio-upload.jks` (macOS: `base64 -i tcg-portfolio-upload.jks`) |
| `SNAPSTACK_KEYSTORE_PASSWORD` | Store password |
| `SNAPSTACK_KEY_ALIAS` | `upload` |
| `SNAPSTACK_KEY_PASSWORD` | Key password |

## 5. Put the privacy policy online

Play needs a public URL. The repository is private, so host the page somewhere public:

* **Netlify Drop** (free, no account needed to start): drag a folder containing
  `docs/privacy-policy.html` onto [app.netlify.com/drop](https://app.netlify.com/drop), then claim the
  site so it stays up. The policy is at `https://<site-name>.netlify.app/privacy-policy.html`.
* **Google Sites** (free): create a site, paste the policy text into a page, publish it.
* **GitHub Pages** works for a private repository only on a paid GitHub plan: repository Settings →
  Pages → Deploy from a branch, folder `/docs`.

## 6. Create the app in Play Console

1. **Home → Create app**.
2. App name **TCG Portfolio**, default language **English (United States)**, **App**, **Paid**.
3. Accept the declarations and create it.

## 7. Set the price to $1

1. **Monetize → Products → App pricing** (in some layouts: Monetize with Play → App pricing).
2. Set the price: **USD 1.00** (or **0.99**, the usual store price point).
3. Let Play convert it to local currencies, and keep the countries you want to sell in selected.
4. Save.

## 8. Fill in the store listing and app content

1. **Grow → Store presence → Main store listing**: copy the name, short and full description from
   `listing.md`. Upload `graphics/icon-512.png`, `graphics/feature-graphic-1024x500.png`, and 2–8
   phone screenshots (`listing.md` says which screens to capture).
2. **Store settings**: category **Tools** (or Lifestyle), your contact email, and optionally a
   website.
3. **Policy → App content**: answer every section using `policy-answers.md`: privacy policy URL,
   ads (none), app access (no login), content rating questionnaire, target audience (13+),
   Data safety, and the other declarations. One difference for a paid app: in the content rating
   questionnaire, "purchase digital goods" still means in-app purchases, so the answer stays **No**.

## 9. Closed test: 12 testers, 14 days

Testers of a paid app in a closed test **have to buy it**, unless you make them **license testers**:

1. **Settings → License testing**: add the Gmail addresses of your testers. License testers can
   install your paid app without being charged.
2. **Test and release → Testing → Closed testing → Create track** (or use the default "Alpha").
3. **Testers**: add the same 12+ email addresses (or a Google Group they join).
4. **Create release**: upload `app-release.aab`, release name `1.0.0`, release notes from
   `listing.md`, then **Review and roll out**. The first review can take a few days.
5. Send testers the **opt-in link** from the Testers tab. They must accept it and install the app
   from Play with the same Google account.
6. Keep **at least 12 testers opted in for 14 days in a row**. Ask them to open the app a few times;
   Google asks about tester engagement when you apply.

Tip: start with **Internal testing** (free installs for up to 100 people, no review wait) to check
the Play build on your own phone first.

## 10. Apply for production and launch

1. After 14 days: **Dashboard → Apply for production**, and answer the questions about your test.
   Google reviews the application (usually up to about a week).
2. When approved: **Test and release → Production → Create new release**. Upload the `.aab` (or
   promote the closed-test release), add release notes, **Review and roll out**.
3. The app review takes from a few hours to a few days. Then TCG Portfolio is on sale at $1.

## 11. Move your own phone to the Play version

The Play version is signed with Google's key, and the APKs in `dist/` aren't, so Android won't update
one with the other:

1. In the sideloaded app: **Settings → Back up collection**, and save the file somewhere you can reach.
2. Uninstall the sideloaded app.
3. Install TCG Portfolio from Play.
4. **Settings → Restore a backup** and choose the file.

## 12. Every later release

1. Raise `versionCode` (2, 3…; it must always go up) and `versionName` (1.0.1, 1.1.0…) in
   `app/build.gradle.kts`.
2. `./gradlew testDebugUnitTest bundleRelease`
3. **Production → Create new release**, upload, add release notes, roll out. Buyers get updates free.

## Checklist

- [ ] Developer account paid and verified
- [ ] Payments profile with bank and tax details
- [ ] Upload key created and backed up
- [ ] `bundleRelease` builds `app-release.aab` (1.0.0, versionCode 1)
- [ ] Privacy policy online, URL saved
- [ ] App created as **Paid**, price set to $1
- [ ] Store listing, icon, feature graphic, 2–8 screenshots
- [ ] App content forms done (`policy-answers.md`)
- [ ] 12+ testers added as license testers and closed testers, 14 days opted in
- [ ] Production access approved, production release rolled out
