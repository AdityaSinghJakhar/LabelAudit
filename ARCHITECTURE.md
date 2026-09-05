# Label Guard — architecture and deployment

Compliance checking for packaged commodities under the Legal Metrology
(Packaged Commodities) Rules, 2011, and the Food Safety and Standards
labelling regulations. SIH problem statement 26034.

---

## The governing rule

**Never emit a verdict the pipeline cannot substantiate.**

Every design decision below follows from it. The system is allowed to say "I
could not determine this"; it is not allowed to guess and present the guess as
a finding. An inspector acting on a false violation, or a shopper reassured by
a false pass, is worse served than one told plainly that the photograph did not
settle the question.

Two consequences run through the whole codebase:

- **Uncertain quantities are ranges, not numbers.** A date read as `12/2025`
  means a month. A character height measured through the camera's optics is
  known to about ±30%. Both are carried as ranges, and a check may only assert
  a violation when the *entire* range clears the threshold.
- **Not-knowing has two distinct causes**, and conflating them broke the app
  once already. See the status vocabulary below.

---

## Runtime shape

Everything runs on the phone. There is no server, no network call, and no
account. The OCR model is bundled in the APK, so the first scan works offline
with nothing to download.

```
CameraX (3–5 frames per side)   or   gallery upload (bulk)
        │
        ▼
  OcrEngine ......... ML Kit Devanagari recogniser, on-device
        │             reads Devanagari AND Latin in one pass
        ▼
  FieldExtractor .... 10 declarations, by caption + geometry
        │
        ▼
  Consensus ......... agreement across frames becomes the confidence
        │
        ▼
  RulesEngine ....... 17 checks, exemptions first, one finding each
        │
        ▼
  ScanReport ........ per-field groups → screen, PDF, CSV, history
```

### Why one OCR model

Only the Devanagari recogniser is used, because it reads both scripts in a
single pass. This is measured, not assumed: `OcrScriptCoverageTest` shows the
Latin-only model returning just the English half of a bilingual label while the
Devanagari model reads both. Indian labels are routinely bilingual, so the
Latin model would silently drop Hindi declarations.

### Why several frames

ML Kit exposes no per-reading confidence. Rather than invent one, the pipeline
photographs the same side several times and uses **how many frames agreed** as
the confidence. That is a real measurement. Fields the frames disagree on are
reported as unresolved, never silently resolved.

---

## Status vocabulary

Six statuses. The middle two carry most of the design.

| Status | Meaning |
|---|---|
| `PASS` | The check ran and the pack satisfies it |
| `FAIL` | The check ran and the pack violates it |
| `NEEDS_REVIEW` | Evidence is real but does not settle the question |
| `NOT_ASSESSABLE` | A fact about **this photograph** — something could not be read |
| `NOT_APPLICABLE` | A fact about **the setup** — no registry loaded, device cannot measure |
| `EXEMPT` | An exemption applies to this pack |

`NOT_ASSESSABLE` vs `NOT_APPLICABLE` is the distinction that matters most. An
unpopulated registry, or a handset that cannot report focus distance, is not a
failure to read the label — it is a property of the deployment. Treating it as
unassessable made every scan report "not assessable" and hid real violations.

### Verdict precedence

```
FAIL  >  NOT_ASSESSABLE  >  NEEDS_REVIEW  >  PASS
```

`FAIL` outranks `NOT_ASSESSABLE` deliberately: a violation the pipeline
substantiated stays substantiated even though some other check could not run.
Failing to read the expiry does not un-prove that the price is missing. The
report carries a caveat naming every check that went unassessed, so a `FAIL` is
never mistaken for a complete audit.

---

## Rule catalogue

17 checks over roughly 11 statutory provisions. Several provisions get more
than one check because a requirement can be broken in more than one way.

| Provision | Checks |
|---|---|
| LMPC r.6(1)(a) manufacturer name & address | `MFR-01` |
| r.6(1)(b) common/generic name | `BRAND-01` |
| r.6(1)(c) net quantity | `QTY-01`, `QTY-02` |
| r.6(1)(d) month & year of manufacture | `MFG-01`, `MFG-02` |
| r.6(1)(e) retail sale price | `MRP-01`, `MRP-02` |
| r.6(1)(f) consumer care | `CARE-01` |
| r.2(m) + 6(1)(e) inclusive of taxes | `TAX-01` |
| r.9 size of letters and numerals | `CAP-01`, `CAP-02` |
| FSS Labelling reg.5 lot/batch | `BATCH-01` |
| FSS Labelling reg.5 date marking | `EXPIRY-01`, `EXPIRY-02`, `EXPIRY-03` |
| FSS Licensing 2011 FSSAI licence | `FSSAI-01` |

Rules live in `app/src/main/assets/ruleset.yaml`, not in code. Each carries a
citation, and the loader **throws at startup** if any rule lacks one — a
finding with no statutory source must not be possible to ship.

`MFG-02`, `EXPIRY-02` and `EXPIRY-03` are **internal-consistency checks derived
from** the provision requiring the declaration, not separate statutory
requirements. A declaration stating something impossible has not properly been
made. They cite the underlying provision rather than inventing one.

### Two flags that hold a rule back from accusing

- `needs_legal_confirmation` — the numeric threshold is transcribed but
  unverified by a legal reviewer.
- `needs_calibration` — the measurement tolerance it rests on is assumed, not
  measured on real handsets.

While either stands, the rule reports its measurement and returns
`NEEDS_REVIEW`. `CAP-01` and `CAP-02` currently carry both.

---

## Measurement

### Dates as ranges — `pipeline/LabelDate.kt`

`12/2025` names a month. `06/07/2025` is 6 July under the Indian convention and
7 June under the American one, and OCR cannot tell which press printed it. Both
are held as the range of days they could mean, so:

- manufactured in the future ⟹ only if the **earliest** possible day is ahead
- expired ⟹ only if the **latest** possible day has gone
- a range that straddles the answer ⟹ `NEEDS_REVIEW`

Relative markings resolve: *"best before 9 months from packing"* plus
*"MFG 03/2025"* yields an expiry, and an expired pack becomes a substantiated
finding.

### Character height — `measure/Scale.kt`

Rule 9 asks for millimetres; a photograph knows pixels. A printed marker in
every shot is accurate and unusable in a shop, so the scale comes from the lens:

```
character mm = pixels × (sensor mm / image px) × (u − f) / f
```

`u` is the reported focus distance, `f` the focal length, both from
`CameraCharacteristics` and each frame's `TotalCaptureResult`. Android's
`LENS_INFO_FOCUS_DISTANCE_CALIBRATION` warns that on many phones `u` is not in
real units — a device reporting `UNCALIBRATED` yields **no measurement at all**
rather than a confident wrong one.

Measured on a CPH2619: `APPROXIMATE`, i.e. ±30%. A one-time calibration against
a bank card (ISO/IEC 7810, 85.60 × 53.98 mm) narrows that to ±5%. Indian coins
are deliberately not offered as references: their diameters have changed
between minting series, and a reference that is sometimes wrong is worse than
none.

---

## Reference data and trust

A comparison is only as good as what it compares against.

| Tier | Source | Can substantiate |
|---|---|---|
| Authoritative | Brand product master, regulator dataset | `FAIL` |
| Asserted | Enrolled from a scan, or typed by hand | `NEEDS_REVIEW` only |

An enrolled reference **can never fail another pack**. Nothing established that
the enrolled pack was itself correct, so a disagreement says one of the two is
wrong without saying which. Without this, a relabeller could enrol their own
repasted pack and have every later fake pass against it.

The app is useful with an empty registry: **only 3 of the 17 checks compare
against a reference.** The other 14 — every declaration's presence, the date
arithmetic, the tax wording, the FSSAI number, character height — need none.

**Conflict detection** needs no reference and nobody's word: scans of the same
product that disagree are surfaced directly. Eleven packs reading ₹20 and one
reading ₹35 is evidence the packs generate themselves.

---

## Roles

| | Shopper (default) | Inspector |
|---|---|---|
| Scan, read findings, export own report | ✓ | ✓ |
| Own history | ✓ | ✓ |
| Register a reference pack | — | ✓ |
| Export/clear the inspection history | — | ✓ |
| Calibrate the camera | — | ✓ |

**The passcode is not authentication.** It is checked on the same device that
stores it (as a salted SHA-256 digest, never in the clear — there is a device
test asserting that). It keeps an honest user in their lane; it stops nobody
holding the phone. Real authentication needs a server to authenticate against —
`Role` is what such a server would issue. This is why the role governs *what an
assertion is worth* rather than being trusted on its own.

---

## Storage

All app-private internal storage; removed on uninstall; nothing leaves the
device.

| Path | Contents |
|---|---|
| `filesDir/sku_registry.json` | Registered SKUs |
| `filesDir/scan_history.json` | Inspection history, newest first, capped at 500 |
| `SharedPreferences labelguard_role` | Current role, passcode digest + salt |
| `SharedPreferences labelguard_calibration` | Camera correction factor |
| `getExternalFilesDir/reports/` | Exported PDFs and CSVs |
| `getExternalFilesDir/corpus/` | Kept scans for evaluation — off by default |

Plain JSON rather than a database: an inspector's registry is tens of products,
the whole file is read once per scan, and JSON can be pulled off the device,
read by a person, edited and diffed — which is what evidence has to allow. Room
becomes right when the data must be *searched* rather than scanned.

Every store treats a corrupt file as empty. An unreadable registry makes
comparisons inapplicable; it must never stop the scanner from running.

---

## Build and deployment

- Kotlin, Jetpack Compose, Material 3
- `minSdk 28`, `targetSdk 36`
- CameraX + Camera2 interop (for lens metadata), ML Kit Text Recognition
  (Devanagari, bundled), AndroidX ExifInterface
- ABI filters `arm64-v8a`, `armeabi-v7a` — without them the bundled native
  libraries take the APK past 200 MB
- Release APK ≈ 54 MB, dominated by the bundled OCR model

```bash
./gradlew testDebugUnitTest          # 241 JVM tests
./gradlew connectedDebugAndroidTest  # 79 device tests
./gradlew installDebug
```

No backend to deploy. Distribution is the APK. The only network dependency the
design anticipates is the optional reference sync described below, which is a
**signed static file over HTTPS** — object storage, not a live API — so a scan
never blocks on it.

---

## Test strategy

Tests are split by what they can honestly prove.

- **JVM (241)** — everything with no Android dependency: normalisation, date
  parsing, consensus, field extraction, the rules engine, scale arithmetic,
  role capabilities, CSV escaping. The pipeline is deliberately free of Android
  types so this is possible; conversion happens once, at the OCR boundary.
- **Device (79)** — anything needing real `org.json`, real storage, real
  camera metadata, real PDF rendering.
- **Capability probe** — `CameraCapabilityTest` reports what the handset can
  actually tell us about its optics. A failure there is a finding about the
  hardware, not a defect.

### Measuring accuracy

Scans are normally discarded the moment they finish, which is right for a
shopper's phone and fatal for evaluation. Two things are impossible without
the images: nobody can say what a pack really declared, and no improved reader
can be shown to be an improvement — it could only be run over *new*
photographs, which measures something else.

So an inspector can turn on **Keep scans for evaluation**. Each scan is then
kept with its frames beside the prediction, in a layout the Python harness
reads directly (`corpus/<id>/scan.json` + `frame-NN.jpg`).

```
python -m labelguard.eval.annotate --corpus corpus/ --truth truth/a1 --annotator a1
python -m labelguard.eval --corpus corpus/ --truth truth/a1
```

The annotation tool is **blind by design**: it shows the image path and asks
what the pack declares, never what the app read. An annotator shown a
plausible answer agrees with it, and ground truth collected that way measures
how persuasive the pipeline is rather than how right it is — inflating every
figure without leaving a mark. Records carry `blind: true`; the escape hatch
stamps `blind: false` so anchored annotations cannot be pooled with honest
ones.

Two annotators over the same corpus give Cohen's kappa, which says whether the
task is well defined at all. If two careful readers disagree about what a pack
declares, no pipeline can be scored against either of them.

Regression tests for field-extraction bugs are built from **the raw OCR of the
label that exhibited them**, transcribed from a real report, not from invented
input. When a fix is written, it is verified by disabling it and confirming the
new tests fail.

---

## Known limits

Stated plainly, because a system that overstates its coverage is the thing this
project is built against.

1. **Citations are provisional.** Transcribed from the Rules but not verified
   against the current amended text by a legal reviewer.
2. **Rule 9's Second Schedule is not transcribed.** `CAP-01` and `CAP-02` carry
   a 1 mm placeholder and cannot fail a pack until the real table is reviewed.
3. **Documentary evidence is out of scope, permanently.** Missing FSSAI
   licences and absent purchase bills — the decisive evidence in the relabelling
   raids — are not in any photograph.
4. **Placement of declarations is not checked.** The principal-display-panel
   requirements are not implemented, though the bounding boxes to do it exist.
5. **No country-of-origin check** for imported packages.
6. **The registry is per-device.** Crowd corroboration needs shared storage.
7. **Counterfeit and authenticity detection are explicitly excluded.** They
   need external registries (GS1, hologram libraries) the project has no access
   to, and a classifier that cannot be evaluated is worse than no classifier.

---

## Planned, not built

- **Signed reference sync.** Ed25519, public key pinned in the APK, so a bundle
  can be proven to come from the key holder and to be unaltered. Download of
  references and upload of observations are separate opt-ins — the second sends
  a purchase-and-location trail and must never ride on the first.
- **Second Schedule transcription** — the blocker on the height rules.
- **An annotated evaluation set.** The corpus capture, the blind annotation
  tool and the scorer are all built and tested end to end; what remains is the
  unglamorous part, which is photographing packs and writing down what they
  say. Nothing else on this list changes the project's standing as much.
