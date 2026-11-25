# Google Play Console checklist — Apex Horizon

Policies change; confirm each item in Play Console at submission time.

## 1. What the project already satisfies

| Requirement | Status in this project |
|---|---|
| Target API level (new apps/updates must target Android 16 / API 36 from 31 Aug 2026) | `compileSdk = 36`, `targetSdk = 36` in `app/build.gradle.kts` |
| Android App Bundle (.aab) | `./gradlew bundleRelease` |
| 64-bit / 16 KB page size | Pure Kotlin/Java, no native libraries of its own |
| Edge-to-edge (enforced on API 35+) | `enableEdgeToEdge()` + `safeDrawingPadding()` insets everywhere |
| Predictive back | `android:enableOnBackInvokedCallback="true"` + Compose `BackHandler` |
| Hardware not required | `camera`, `camera.any`, `gps`, `gyroscope`, `accelerometer`, `touchscreen` all `required="false"` |
| Runtime permissions with rationale | Location and camera requested from the Home screen with explanatory status rows; app works without camera |
| No background location | Sensors are released on `ON_STOP` and when the drive ends |
| Minimal permissions | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `CAMERA`, `INTERNET` (map downloads only). |
| Safety disclaimer | Shown before the first drive, repeated in About |
| Privacy policy in-app | About screen; hosted copy required (see 3) |
| English only | `values/strings.xml` only, `localeFilters += "en"` |
| Release shrinking | R8 + resource shrinking enabled |

## 2. Before you upload

1. **applicationId** — `com.apexhorizon.app` is a placeholder. It cannot be changed after the first upload. Change it in `app/build.gradle.kts` (and the `namespace`/package if you want them to match).
2. **Create an upload key** and `keystore.properties` (see README). Enrol in **Play App Signing** when creating the app.
3. Bump `versionCode` for every upload.
4. Build: `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.

## 3. Privacy policy URL

Publish `docs/privacy-policy.md` with GitHub Pages (Settings → Pages → Deploy from a branch → `main` / `/docs`). The URL will be
`https://<user>.github.io/<repo>/privacy-policy.html`. Paste it in Play Console (App content → Privacy policy) and in `privacy_policy_url` inside `strings.xml`. Fill in the contact line in the policy first.

## 4. App content declarations (suggested answers)

- **App category:** Auto & Vehicles (or Maps & Navigation). **Type:** App. **Free.**
- **Ads:** No.
- **Target audience:** 18+ (not designed for children).
- **Content rating:** answer the questionnaire honestly: no violence, no user-generated content, no sharing of location with other users.
- **Data safety:**
  - Data collected: **None**. `INTERNET` is used only to download map files; no user data is sent. Location, destinations and camera are processed on-device only. Data shared: None.
  - Data shared: **None**.
  - Security practices: data is not transmitted; users can delete all data in the app or by uninstalling.
  - Privacy policy: the URL from step 3.
- **Permissions:** foreground location and camera only. No sensitive-permission declaration form is needed because there is no background location, SMS, call log or all-files access.
- **Government/financial/health/news apps:** No.
- **Advertising ID:** not used (answer "No").

## 5. Store listing assets (in `store/`)

- `play-store-icon-512.png` — 512×512 app icon
- `feature-graphic-1024x500.png` — feature graphic
- **Screenshots:** take at least 2 phone screenshots yourself (Home, Drive HUD in Demo mode, Trip summary). Demo mode is ideal because it needs no permissions or driving.
- Text: see `store/listing.md`.

## 6. Testing track (new personal developer accounts)

Personal accounts created after 13 Nov 2023 must run a **closed test with at least 12 testers opted in for 14 continuous days** before applying for production access. Organisation accounts are exempt. Start the closed test as early as possible.

## 7. Pre-launch QA on a real device

- Fresh install → Start drive → deny location → dialog appears, Demo still works.
- Allow location, deny camera → HUD runs in Sensor Mode.
- Allow both → camera preview with overlay; cover the lens to see the low-light notice.
- Press Back in every screen: Drive asks "End this drive?", others go back, Home exits.
- Rotate the phone while driving; switch to another app and back (sensors stop and resume).
- Turn location services off → "Location is turned off" dialog.
- Import a GPX file; drive/replay along it.
