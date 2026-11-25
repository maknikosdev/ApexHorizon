# Apex Horizon

**English** · [Ελληνικά](#ελληνικά)

> See the road before it arrives.

Apex Horizon is a calm, glanceable driving HUD for Android that I built with Kotlin and Jetpack Compose. It smooths your speed, draws the road ahead, warns about the next curve and can plan a trip with free offline maps. The design follows the logo: deep navy, cyber-blue for the path, neon-orange for curves and highlights, and a ticked speed dial.

| | |
|---|---|
| Min / target SDK | 26 / 36 |
| Language / UI | Kotlin 2.2, Jetpack Compose (Material 3), English-only UI |
| Camera | CameraX, optional (the full HUD works without it) |
| Permissions | Location, Camera (optional), Internet (only to download offline maps) |
| Maps | OpenStreetMap data, free, stored on the device |

---

## English

### How to use the app

**1. First launch**
1. Open the app. The Home screen shows a readiness card: *Camera Pipeline*, *Location & Motion*, *Route Horizon*.
2. Tap **Start drive**. Read and accept the safety notice (the app is an aid, not a navigation or safety system, and you stay responsible for driving).
3. Allow **Location** when asked. Camera is optional: if you decline it, the app runs in **Sensor Mode** with the same HUD on a synthetic horizon.
4. If GPS is switched off, the app offers to open Location settings.

**2. Try it without driving**
Tap **Demo / Replay** to run a synthetic mountain road through the real pipeline. No permissions needed. You can also replay saved drives from History at 1x, 2x or 4x.

**3. Reading the HUD**
- **Speed dial**: smoothed speed. Braking only changes the tone; there are no alarms.
- **Path**: the predicted path ahead. Solid means confident; dashed means the app is estimating.
- **Curve card**: for example "Left curve in 180 m · Suggested approach ~60 km/h", with an orange apex marker. The suggestion comes from road geometry and comfortable cornering. It is **not** a speed limit.
- **Status chips**: CAM, GPS and ROUTE state.
- **Confidence**: combines GPS quality, route match and (with the camera) light level. In low light or weak GPS the HUD tells you it is estimating from sensors and route.
- **Side panel**: G-load, trip distance and, on a planned route, **TO GO** (distance left).
- Android **Back** during a drive asks "End this drive?". At the end you get a summary with distance, time, average/max speed and a timeline of firm-braking and intense-corner events. Save it to History if you wish.

**4. Plan a trip with offline maps (free, no account)**
1. **Offline maps**: tap **Update maps** while online. Pick a region (for example Attica) and tap **Download**. Use Wi-Fi if you can; each file shows its size first. Do this once; afterwards everything works offline.
2. **Plan a trip**: choose **By car** or **On foot**.
   - By car follows roads open to cars and respects one-way streets.
   - On foot uses footpaths, steps and pedestrian streets, ignores one-way rules and avoids motorways (5 km/h).
3. Choose the destination (chip **To**): type a place or street name, or long-press the map. To change the start (chip **From**), do the same. The default start is **My location**.
4. Tap **Calculate route**. You get distance and estimated time, and the route is drawn on the map.
5. Tap **Start guided drive**, return to Home and press **Start drive**. The route feeds the Route Horizon (curve warnings, TO GO). If you leave the route for about 6 seconds, a new route is calculated automatically.

**5. Keeping maps up to date**
OpenStreetMap changes constantly. When I rebuild the packs, open **Offline maps → Update maps**: regions with newer data show "Update available". Downloads are verified (SHA-256) and replace the old file only when complete, so a failed update never breaks an existing map. You can delete any region to free space.

**6. Using your own route (GPX)**
On Home (or in Settings) use **Import GPX route** to load a track. It works as the Route Horizon when no planned trip is active. **Reverse** and **Remove** are in Settings.

**7. Settings and history**
Units (km/h or mph), camera on/off, GPX route tools, delete all saved runs. History lists saved drives; open one to see its summary or replay it.

### Back button
Drive → "End this drive?" dialog · Summary / History / Settings / About / Plan / Offline maps → previous screen · Home → exits the app.

### Build and run

**Android Studio (recommended):** *File → Open…* → select this folder → wait for Gradle sync (JDK 17+; the bundled JBR is fine) → pick a device → **Run**. On an emulator use *Extended controls → Location*, or use **Demo / Replay**.

```bash
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew bundleRelease          # app/build/outputs/bundle/release/app-release.aab
```

**Before publishing:** change `com.apexhorizon.app` to your own application id, and replace `YOUR-GITHUB-USER` in `strings.xml` and `strings_maps.xml`.

**Release signing**
```bash
keytool -genkeypair -v -keystore apex-horizon-upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
cp keystore.properties.example keystore.properties   # edit passwords and paths
./gradlew bundleRelease
```
`keystore.properties` and `*.jks` are git-ignored. Keep a backup of the keystore. Play Console steps: [`docs/PLAY_CONSOLE_CHECKLIST.md`](docs/PLAY_CONSOLE_CHECKLIST.md).

### Publishing the maps (GitHub, free)
1. Push the repository to GitHub and keep it **public**.
2. In `app/src/main/res/values/strings_maps.xml` set `maps_manifest_url` to `https://github.com/<you>/<repo>/releases/download/maps/manifest.json`.
3. Run **Actions → Build offline maps → Run workflow**. It downloads the Greece extract, cuts the regions listed in `maps/regions.json`, and publishes the packs plus `manifest.json` to a release tagged `maps`. It also runs monthly. The first run can take a long time.
4. Edit `maps/regions.json` to change regions (bounding boxes). Local build:
   `pip install osmium numpy && python tools/mapbuilder/build_pack.py --input greece-latest.osm.pbf --bbox 37.6,23.2,38.45,24.1 --out attica.apxm --data-version 20261125`

### Architecture
```
app/src/main/java/com/apexhorizon/app
├─ domain/   pure Kotlin: Geo, SpeedFilter, RouteHorizon, DriveEngine, ConfidenceModel, Replay/Demo
├─ maps/     MapPack (reader), Router (A*), search
├─ sensors/  LiveSampleSource (GPS + gyro + accel -> 5 Hz samples), Capabilities, LastLocation
├─ data/     RunStore, RouteStore, GpxParser, AppSettings, MapRepository
└─ ui/       AppViewModel, navigation, HUD, map view, screens
tools/mapbuilder/   OpenStreetMap -> .apxm pack builder
```
Live sensors, the demo and saved-run replay all produce the same sample stream for the same engine, so the demo exercises the real logic. Unit tests are in `app/src/test`.

### Known limits
- The overlay assumes a typical phone-camera field of view. It is a guidance graphic, not calibrated AR.
- Camera frames are used only to measure brightness.
- Suggested speeds are not speed limits.
- Maps: no turn restrictions and no voice guidance; a route must stay inside one downloaded region; quality depends on OpenStreetMap data.
- Curve advice needs a route (planned or GPX); without one the app predicts only from current motion.

### Privacy and safety
No accounts, ads or analytics. The internet is used only to download map files you choose; your location and destinations never leave the device. See [`docs/privacy-policy.md`](docs/privacy-policy.md). Apex Horizon is an informational aid, not a navigation or safety system.

### Credits and license
Map data © OpenStreetMap contributors (ODbL). Code: MIT, see [`LICENSE`](LICENSE).

---

## Ελληνικά

> Δες τον δρόμο πριν φτάσει.

Το Apex Horizon είναι ένα ήρεμο HUD οδήγησης για Android που έφτιαξα με Kotlin και Jetpack Compose. Εξομαλύνει την ταχύτητα, σχεδιάζει τον δρόμο μπροστά, προειδοποιεί για την επόμενη στροφή και μπορεί να σχεδιάσει διαδρομή με δωρεάν offline χάρτες. Ο σχεδιασμός ακολουθεί το λογότυπο: βαθύ ναυτικό μπλε, κυανό για τη διαδρομή, έντονο πορτοκαλί για τις στροφές και καντράν με χαρακτηριστικές διαβαθμίσεις.

### Πώς χρησιμοποιείται

**1. Πρώτη εκκίνηση**
1. Άνοιξε την εφαρμογή. Η αρχική οθόνη δείχνει κάρτα ετοιμότητας: *Camera Pipeline*, *Location & Motion*, *Route Horizon*.
2. Πάτα **Start drive**. Διάβασε και αποδέξου την ειδοποίηση ασφαλείας (η εφαρμογή είναι βοήθημα, όχι σύστημα πλοήγησης ή ασφαλείας, και την ευθύνη της οδήγησης την έχεις εσύ).
3. Δώσε άδεια **Location** όταν ζητηθεί. Η κάμερα είναι προαιρετική: αν την αρνηθείς, η εφαρμογή δουλεύει σε **Sensor Mode** με το ίδιο HUD πάνω σε συνθετικό ορίζοντα.
4. Αν το GPS είναι κλειστό, η εφαρμογή προσφέρει άνοιγμα των ρυθμίσεων τοποθεσίας.

**2. Δοκιμή χωρίς οδήγηση**
Πάτα **Demo / Replay** για μια συνθετική ορεινή διαδρομή μέσα από το πραγματικό σύστημα, χωρίς άδειες. Μπορείς επίσης να ξαναπαίξεις αποθηκευμένες διαδρομές από το History με 1x, 2x ή 4x.

**3. Πώς διαβάζεται το HUD**
- **Καντράν ταχύτητας:** εξομαλυμένη ταχύτητα. Το φρενάρισμα αλλάζει μόνο τον τόνο, δεν υπάρχουν συναγερμοί.
- **Διαδρομή (path):** η προβλεπόμενη πορεία μπροστά. Συνεχής γραμμή σημαίνει βεβαιότητα, διακεκομμένη σημαίνει ότι η εφαρμογή εκτιμά.
- **Κάρτα στροφής:** π.χ. «Left curve in 180 m · Suggested approach ~60 km/h», με πορτοκαλί σημάδι στην κορυφή της στροφής. Η πρόταση προκύπτει από τη γεωμετρία του δρόμου και άνετη λήψη στροφής. **Δεν** είναι όριο ταχύτητας.
- **Chips κατάστασης:** CAM, GPS, ROUTE.
- **Confidence (εμπιστοσύνη):** συνδυάζει ποιότητα GPS, ταύτιση με τη διαδρομή και (με κάμερα) φωτεινότητα. Σε χαμηλό φως ή αδύναμο GPS το HUD λέει ότι εκτιμά από αισθητήρες και διαδρομή.
- **Πλαϊνό πάνελ:** G-load, απόσταση ταξιδιού και, σε σχεδιασμένη διαδρομή, **TO GO** (υπολειπόμενη απόσταση).
- Το **Back** του Android στην οδήγηση ρωτά «End this drive?». Στο τέλος βλέπεις σύνοψη με απόσταση, χρόνο, μέση/μέγιστη ταχύτητα και χρονοδιάγραμμα με έντονα φρεναρίσματα και απότομες στροφές. Μπορείς να την αποθηκεύσεις στο History.

**4. Σχεδιασμός διαδρομής με offline χάρτες (δωρεάν, χωρίς λογαριασμό)**
1. **Offline maps:** πάτα **Update maps** με σύνδεση στο ίντερνετ. Διάλεξε περιοχή (π.χ. Αττική) και πάτα **Download**. Αν γίνεται, χρησιμοποίησε Wi-Fi· κάθε αρχείο δείχνει πρώτα το μέγεθός του. Το κάνεις μία φορά και μετά όλα δουλεύουν χωρίς ίντερνετ.
2. **Plan a trip:** διάλεξε **By car** (με όχημα) ή **On foot** (πεζός).
   - Με όχημα ακολουθεί δρόμους ανοιχτούς σε αυτοκίνητα και σέβεται τους μονόδρομους.
   - Πεζός χρησιμοποιεί μονοπάτια, σκάλες και πεζόδρομους, αγνοεί τους μονόδρομους και αποφεύγει τους αυτοκινητόδρομους (5 km/h).
3. Επίλεξε προορισμό (chip **To**): γράψε όνομα τόπου ή οδού ή κάνε παρατεταμένο πάτημα στον χάρτη. Για να αλλάξεις αφετηρία (chip **From**) κάνε το ίδιο. Η προεπιλεγμένη αφετηρία είναι το **My location**.
4. Πάτα **Calculate route**. Βλέπεις απόσταση και εκτιμώμενο χρόνο, και η διαδρομή σχεδιάζεται στον χάρτη.
5. Πάτα **Start guided drive**, επέστρεψε στην αρχική και πάτα **Start drive**. Η διαδρομή τροφοδοτεί το Route Horizon (προειδοποιήσεις στροφών, TO GO). Αν βγεις από τη διαδρομή για περίπου 6 δευτερόλεπτα, υπολογίζεται αυτόματα νέα.

**5. Ενημέρωση χαρτών**
Το OpenStreetMap αλλάζει συνεχώς. Όταν ξαναχτίζω τα πακέτα, άνοιξε **Offline maps → Update maps**: οι περιοχές με νεότερα δεδομένα δείχνουν «Update available». Οι λήψεις επαληθεύονται (SHA-256) και αντικαθιστούν το παλιό αρχείο μόνο όταν ολοκληρωθούν, άρα μια αποτυχημένη ενημέρωση δεν χαλά υπάρχοντα χάρτη. Μπορείς να διαγράψεις οποιαδήποτε περιοχή για να ελευθερώσεις χώρο.

**6. Δική σου διαδρομή (GPX)**
Στην αρχική (ή στις Ρυθμίσεις) διάλεξε **Import GPX route** για να φορτώσεις ένα track. Λειτουργεί ως Route Horizon όταν δεν υπάρχει ενεργό ταξίδι. Τα **Reverse** και **Remove** είναι στις Ρυθμίσεις.

**7. Ρυθμίσεις και ιστορικό**
Μονάδες (km/h ή mph), κάμερα on/off, εργαλεία GPX, διαγραφή όλων των αποθηκευμένων διαδρομών. Το History δείχνει τις αποθηκευμένες διαδρομές· άνοιξε μία για σύνοψη ή replay.

### Κουμπί Back
Οδήγηση → διάλογος «End this drive?» · Summary / History / Settings / About / Plan / Offline maps → προηγούμενη οθόνη · Αρχική → έξοδος από την εφαρμογή.

### Εγκατάσταση και εκτέλεση

**Android Studio (προτείνεται):** *File → Open…* → διάλεξε αυτόν τον φάκελο → περίμενε το Gradle sync (JDK 17+, ο ενσωματωμένος JBR αρκεί) → διάλεξε συσκευή → **Run**. Στον emulator χρησιμοποίησε *Extended controls → Location* ή το **Demo / Replay**.

```bash
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew bundleRelease          # app/build/outputs/bundle/release/app-release.aab
```

**Πριν τη δημοσίευση:** άλλαξε το `com.apexhorizon.app` σε δικό σου application id και αντικατάστησε το `YOUR-GITHUB-USER` στα `strings.xml` και `strings_maps.xml`.

**Υπογραφή release**
```bash
keytool -genkeypair -v -keystore apex-horizon-upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
cp keystore.properties.example keystore.properties   # συμπλήρωσε κωδικούς και διαδρομές
./gradlew bundleRelease
```
Τα `keystore.properties` και `*.jks` αγνοούνται από το git. Κράτα αντίγραφο του keystore. Βήματα για το Play Console: [`docs/PLAY_CONSOLE_CHECKLIST.md`](docs/PLAY_CONSOLE_CHECKLIST.md).

### Δημοσίευση χαρτών (GitHub, δωρεάν)
1. Ανέβασε το repository στο GitHub και άφησέ το **public**.
2. Στο `app/src/main/res/values/strings_maps.xml` όρισε το `maps_manifest_url` σε `https://github.com/<εσύ>/<repo>/releases/download/maps/manifest.json`.
3. Τρέξε **Actions → Build offline maps → Run workflow**. Κατεβάζει το αρχείο της Ελλάδας, κόβει τις περιοχές από το `maps/regions.json` και δημοσιεύει τα πακέτα μαζί με το `manifest.json` σε release με tag `maps`. Τρέχει και μηνιαία. Η πρώτη εκτέλεση μπορεί να πάρει πολύ ώρα.
4. Άλλαξε το `maps/regions.json` για να αλλάξεις περιοχές (πλαίσια συντεταγμένων). Τοπικό χτίσιμο:
   `pip install osmium numpy && python tools/mapbuilder/build_pack.py --input greece-latest.osm.pbf --bbox 37.6,23.2,38.45,24.1 --out attica.apxm --data-version 20261125`

### Αρχιτεκτονική
Δες το δέντρο φακέλων στην αγγλική ενότητα. Οι ζωντανοί αισθητήρες, το demo και το replay παράγουν την ίδια ροή δειγμάτων για την ίδια μηχανή, οπότε το demo δοκιμάζει την πραγματική λογική. Τα unit tests βρίσκονται στο `app/src/test`.

### Γνωστά όρια
- Το overlay υποθέτει τυπικό οπτικό πεδίο κάμερας κινητού. Είναι γραφικό καθοδήγησης, όχι βαθμονομημένο AR.
- Τα καρέ της κάμερας χρησιμοποιούνται μόνο για μέτρηση φωτεινότητας.
- Οι προτεινόμενες ταχύτητες δεν είναι όρια ταχύτητας.
- Χάρτες: χωρίς περιορισμούς στροφής και χωρίς φωνητική καθοδήγηση· η διαδρομή πρέπει να μένει σε μία κατεβασμένη περιοχή· η ποιότητα εξαρτάται από τα δεδομένα του OpenStreetMap.
- Οι συμβουλές στροφών χρειάζονται διαδρομή (σχεδιασμένη ή GPX)· χωρίς αυτήν η πρόβλεψη βασίζεται μόνο στην τρέχουσα κίνηση.

### Απόρρητο και ασφάλεια
Χωρίς λογαριασμούς, διαφημίσεις ή analytics. Το ίντερνετ χρησιμοποιείται μόνο για τη λήψη χαρτών που επιλέγεις· η τοποθεσία και οι προορισμοί σου δεν φεύγουν ποτέ από τη συσκευή. Δες το [`docs/privacy-policy.md`](docs/privacy-policy.md). Το Apex Horizon είναι ενημερωτικό βοήθημα, όχι σύστημα πλοήγησης ή ασφαλείας.

### Πηγές και άδεια
Δεδομένα χαρτών © OpenStreetMap contributors (ODbL). Κώδικας: MIT, δες το [`LICENSE`](LICENSE).
