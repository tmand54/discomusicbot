# Publishing TCG Portfolio on Google Play

Everything in the app is ready for Play: it targets Android 16 (API 36), its 64-bit native libraries
support 16 KB memory pages, it's shrunk with R8, and it builds a signed Android App Bundle (`.aab`).
These are the steps only you can do.

## 1. Decide the package name (permanent)

The app's package name is `app.snapstack`, from the project's first name. Once you upload to Play,
it can never change. It appears in the Play URL (`play.google.com/store/apps/details?id=app.snapstack`)
and nowhere else users normally see. Keep it, or change `applicationId` in `app/build.gradle.kts`
before the first upload. If Play says the name is already taken, pick another one the same way.

## 2. Create your upload key (once, on your own computer)

You need a JDK (Android Studio includes one). In a terminal:

```
keytool -genkeypair -v -keystore tcg-portfolio-upload.jks -alias upload \
  -keyalg RSA -keysize 4096 -validity 10000
```

Pick a strong password and enter your name when asked. Then:

* **Back up `tcg-portfolio-upload.jks` and its password** somewhere safe, such as a password manager.
  If you lose the upload key, Play support can reset it, but that takes days.
* **Never commit it.** `.gitignore` already ignores `*.jks` and `keystore.properties`.

With Play App Signing (the default for new apps), Google holds the key that signs what users
download. Your upload key only proves uploads come from you.

## 3. Build the bundle

Create `keystore.properties` in the project root (next to `settings.gradle.kts`):

```
storeFile=/full/path/to/tcg-portfolio-upload.jks
storePassword=your-store-password
keyAlias=upload
keyPassword=your-key-password
```

Then:

```
./gradlew bundleRelease
```

The bundle is at `app/build/outputs/bundle/release/app-release.aab`. Without a key, `bundleRelease`
stops with an explanation rather than producing a debug-signed bundle that Play would reject.

### Or let GitHub build it

Add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `SNAPSTACK_KEYSTORE_BASE64` | Output of `base64 -w0 tcg-portfolio-upload.jks` (macOS: `base64 -i tcg-portfolio-upload.jks`) |
| `SNAPSTACK_KEYSTORE_PASSWORD` | Store password |
| `SNAPSTACK_KEY_ALIAS` | `upload` |
| `SNAPSTACK_KEY_PASSWORD` | Key password |

Each push then also produces a `tcg-portfolio-aab` artifact on the workflow run.

## 4. Host the privacy policy

Play needs a public URL. Any of these work:

* **GitHub Pages** (public repo, or a private one on a paid plan): Settings → Pages → Deploy from a
  branch, folder `/docs`. The policy is then at `https://<user>.github.io/<repo>/privacy-policy.html`.
* **Google Sites**, **Netlify Drop** or any web host: upload `docs/privacy-policy.html` as it is.

## 5. Play Console

1. **Developer account**: [play.google.com/console](https://play.google.com/console) ($25 one-time,
   identity verification). Use a contact email you don't mind being public.
2. **Create app**: name *TCG Portfolio*, App, Free.
3. **Testing first**: personal accounts created after November 13, 2023 must run a **closed test
   with at least 12 testers, opted in for 14 days in a row**, before applying for production.
   Organization accounts skip this. Create the closed test track, upload the `.aab`, add testers by
   email (or a Google Group), and share the opt-in link. Internal testing (up to 100 people, no
   review wait) is a good first step for yourself.
4. **Store listing**: copy from `listing.md`, upload `graphics/icon-512.png`,
   `graphics/feature-graphic-1024x500.png` and your phone screenshots.
5. **App content**: answer using `policy-answers.md` (privacy policy, ads, app access, content
   rating, target audience, data safety, other declarations).
6. **After 14 days of testing**: apply for production access from the Dashboard, then create a
   production release with the same `.aab` (or a newer one).

## 6. Moving your own phone to the Play version

The APKs in `dist/` are signed with a different key from the Play version, so Android won't update
one with the other. To switch without losing anything:

1. In the current app: **Settings → Back up collection**. Save the file somewhere you can reach.
2. Uninstall the sideloaded app.
3. Install TCG Portfolio from Play (from your test track while it's in testing).
4. **Settings → Restore a backup** and choose the file.

## 7. Every later release

1. Bump `versionCode` (must always go up) and `versionName` in `app/build.gradle.kts`.
2. `./gradlew testDebugUnitTest bundleRelease`
3. Upload the new `.aab` to a track, add release notes, and roll out.

## Checklist

- [ ] Package name decided
- [ ] Upload key created and backed up
- [ ] `keystore.properties` (or GitHub secrets) set up, `bundleRelease` succeeds
- [ ] Privacy policy hosted, URL saved
- [ ] Developer account verified
- [ ] Store listing text, icon, feature graphic, 2–8 screenshots
- [ ] App content forms done (`policy-answers.md`)
- [ ] Closed test running with 12+ testers for 14 days (personal accounts)
- [ ] Production access granted, production release rolled out
