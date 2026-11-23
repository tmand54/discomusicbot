# Releasing TCG Investment Portfolio on Google Play (free)

Version **1.0.0** (versionCode 1), package `app.tcginvestportfolio`, free with no ads and no in-app
purchases. The app meets Play's technical rules: it targets Android 16 (API 36), its 64-bit native
libraries support 16 KB memory pages, it's shrunk with R8, and GitHub builds a signed Android App
Bundle (`.aab`) with your upload key. Expect roughly 3 weeks end to end for a new personal account,
mostly the 14-day test and Google's reviews.

> **Free is permanent.** A free app can never be changed to paid. Ads or in-app purchases can be
> added later, but they make the app commercial, which conflicts with some card-data sources' terms.

## 1. Developer account (done)

Personal accounts created after November 13, 2023 must run a **closed test with at least 12
testers for 14 days in a row** before they can publish publicly (step 6).

## 2. Upload key and bundle (done)

Your upload key is in the repository's GitHub secrets. Every push builds a signed bundle:
**Actions** tab → newest **Build APK** run → **Artifacts** → `tcg-investment-portfolio-aab` →
unzip → `app-release.aab`. Keep the `.jks` file and its password backed up.

## 3. Put the privacy policy online

Upload `docs/privacy-policy.html` to [Netlify Drop](https://app.netlify.com/drop) (in a folder, with
a free account so the site stays up) or Google Sites. Keep the address, e.g.
`https://<site-name>.netlify.app/privacy-policy.html`.

## 4. Create the app

1. Play Console → **Home → Create app**.
2. App name **TCG Investment Portfolio**, default language **English (United States)**, **App**,
   **Free**.
3. Tick the declarations (Developer Program Policies, Play App Signing, US export laws) and create.

If you already created it as Paid: **Monetize → App pricing → Free**. Paid to free is always allowed.
If it was created under another name: **Grow → Store presence → Main store listing → App name**.

## 5. Store listing and app content

1. **Main store listing**: copy the name, short and full description from `listing.md`. Upload
   `graphics/icon-512.png`, `graphics/feature-graphic-1024x500.png` and 2–8 phone screenshots.
2. **Store settings**: category **Tools** (or Lifestyle) and your contact email.
3. **Policy → App content**: answer each section from `policy-answers.md` (privacy policy URL, ads:
   none, app access: no login, content rating, target audience 13+, Data safety, other declarations).

## 6. Internal test, then the closed test (12 testers, 14 days)

1. **Test and release → Testing → Internal testing → Create release**: upload `app-release.aab`,
   release name `1.0.0`, notes from `listing.md`, **Review and roll out**. Add yourself as a tester
   and install from the opt-in link to check the Play build on your phone.
2. **Closed testing → Create track** (or use "Alpha") → **Testers**: add 12+ email addresses or a
   Google Group → **Create release** with the same bundle → **Review and roll out**.
3. Send testers the **opt-in link**. They accept it and install from Play with the same Google
   account. Keep at least 12 opted in for **14 days in a row**, and ask them to open the app a few
   times; Google asks about engagement when you apply.

## 7. Production

1. After 14 days: **Dashboard → Apply for production** and answer the questions about your test.
2. When approved: **Production → Create new release**, use the same bundle (or a newer one),
   **Review and roll out**. Review takes a few hours to a few days.

## 8. Move your own phone to the Play version

The Play version is signed with Google's key and the sideloaded APKs aren't, so one won't update the
other: **Settings → Back up collection** in the old app, uninstall it, install from Play,
**Settings → Restore a backup**.

## 9. Every later release

1. Raise `versionCode` (2, 3…) and `versionName` (1.0.1, 1.1.0…) in `app/build.gradle.kts`.
2. Push; download the new `tcg-investment-portfolio-aab` from the Actions run.
3. **Production → Create new release**, upload, add release notes, roll out.

## Checklist

- [ ] Privacy policy online, URL saved
- [ ] App created as **Free** (or switched to Free)
- [ ] Store listing, icon, feature graphic, 2–8 screenshots
- [ ] App content forms done (`policy-answers.md`)
- [ ] Internal test installed on your phone
- [ ] 12+ testers opted in to the closed test for 14 days
- [ ] Production access approved, production release rolled out
