# Label Guard — where the project stands

For the team, and for any AI assistant picking this up cold. Read
[ARCHITECTURE.md](ARCHITECTURE.md) for how the system works; this is what has
been *done*, what is *deliberately not done*, and what to do next.

SIH problem statement **26034** — compliance checking for packaged commodities
under the Legal Metrology (Packaged Commodities) Rules, 2011.

---

## The one rule that explains every other decision

> **Never emit a verdict the pipeline cannot substantiate.**

The app may say "I could not determine this". It may not guess and present the
guess as a finding. An inspector acting on a false violation, or a shopper
reassured by a false pass, is worse served than one told plainly that the
photograph did not settle the question.

If you change one thing in this codebase, check it against that sentence
first. Most of the odd-looking decisions below are it, applied.

Its two standing consequences:

1. **Uncertain quantities are ranges, not numbers** — dates, character
   heights. A check may only assert a violation when the *whole* range clears
   the threshold.
2. **Not-knowing has two causes and they are not interchangeable** —
   `NOT_ASSESSABLE` is a fact about *this photograph*, `NOT_APPLICABLE` a fact
   about *the setup*. Conflating them once made every scan report "not
   assessable" and hid real violations.

---

## Current state

| | |
|---|---|
| Android app | Kotlin, Compose, `minSdk 28`, APK ≈ 54 MB, fully offline |
| Tests | **258 JVM**, **89 device** (10 not yet run on hardware) |
| Rules | 17 checks + 2 exemptions, over ~11 statutory provisions |
| Python | accuracy harness, **77 tests** |
| Repo | `LabelAudit/` is git, on `main`. **`SIH-2026/` is NOT versioned — fix this** |

```bash
./gradlew testDebugUnitTest          # 258
./gradlew connectedDebugAndroidTest  # 89, needs a phone
./gradlew installDebug
```

---

## What is built

### The scan pipeline
Camera (3–5 frames per side, multiple sides) or bulk gallery upload → focus
gating → on-device OCR → consensus → field extraction → rules → report.

- **One OCR model.** ML Kit's Devanagari recogniser reads Devanagari *and*
  Latin in one pass. Measured, not assumed: `OcrScriptCoverageTest` shows the
  Latin model silently dropping the Hindi half of a bilingual label.
- **Confidence is measured, not invented.** ML Kit exposes no per-reading
  confidence, so the pipeline photographs each side several times and uses
  *how many frames agreed*.
- **Focus gating** (`measure/Sharpness.kt`). Variance of the Laplacian, ranked
  within a burst rather than against a constant — the raw figure depends as
  much on how much detail a pack prints as on how well it was photographed. A
  soft frame does not contribute nothing; it contributes a *wrong* reading,
  and consensus counts that as a vote.

### 17 rules, in `app/src/main/assets/ruleset.yaml`
Not in code. Every rule carries a citation and **the loader throws at startup
if one does not** — a finding with no statutory source must be impossible to
ship.

`MFG-02`, `EXPIRY-02`, `EXPIRY-03` are **derived consistency checks**, not
separate statutory requirements: a declaration stating something impossible
has not properly been made. They cite the underlying provision.

### Dates as ranges (`pipeline/LabelDate.kt`)
`12/2025` is a month. `06/07/2025` is 6 July here and 7 June in America, and
OCR cannot tell which press printed it. Both are held as ranges, so a pack is
"manufactured in the future" only if its *earliest* possible day is ahead, and
"expired" only if its *latest* has gone. Relative markings resolve: *"best
before 9 months from packing"* + *"MFG 03/2025"* gives a real expiry.

### Character height from the lens (`measure/Scale.kt`)
Rule 9 wants millimetres; a photo knows pixels. A marker in every shot is
accurate and unusable in a shop, so the scale comes from sensor size, focal
length and focus distance. A phone reporting `UNCALIBRATED` yields **no
measurement at all**. Your CPH2619 reports `APPROXIMATE` — ±30%, narrowed to
±5% by a one-time calibration against a bank card.

### Reference data and trust
| Tier | Source | Can substantiate |
|---|---|---|
| Authoritative | Brand product master / regulator | `FAIL` |
| Asserted | Enrolled from a scan, or typed | `NEEDS_REVIEW` only |

**An enrolled reference can never fail another pack.** Otherwise a relabeller
enrols their own repasted pack and every later fake passes against it. Only 3
of the 17 checks use a reference — the app is useful with an empty registry.

### Roles
Shopper (default) and Inspector. Enrolling references, exporting history,
calibrating and keeping a corpus are inspector-only. **The passcode is not
authentication** — it is checked on the device that stores it. It keeps an
honest user in their lane. Real auth needs the sync server.

### History, reports, dashboard
Every scan recorded and searchable; PDF and CSV export; summary counted over
*scans, not checks*; **conflict detection** — packs of the same product that
disagree, which needs no reference and nobody's word.

### Accuracy measurement — the part that matters for judging
The app used to delete every photograph the instant a scan finished, which
made evaluation impossible: nobody can write down what a pack declared with no
image, and no improved reader can be *shown* to be an improvement.

Now: **Inspector → Keep scans for evaluation** stores frames beside the
prediction, and `SIH-2026/labelguard` scores them.

```bash
python -m labelguard.eval.annotate --corpus corpus/ --truth truth/a1 --annotator a1
python -m labelguard.eval --corpus corpus/ --truth truth/a1
```

Annotation is **blind by design** — it never shows what the app read. An
annotator shown a plausible answer agrees with it, and ground truth collected
that way measures how persuasive the app is, not how right. Records carry
`blind: true`; the escape hatch stamps `blind: false` so anchored work cannot
be pooled with honest work.

---

## What is deliberately NOT built

Do not "fix" these without reading why.

| | Why |
|---|---|
| Counterfeit / authenticity detection | Needs GS1 and hologram registries we cannot access. A classifier that cannot be evaluated is worse than none. |
| GTIN / barcode lookup | Excluded from scope. SKU identification runs on printed fields instead. |
| Print-technology forensics (inkjet vs offset) | Laplacian variance on a handheld shot of a flexible pouch measures *depth of field*, not press type. It produces confident numbers that mean nothing. |
| Absolute sharpness threshold | Content-dependent — see above. Only relative comparison within a burst is sound. |
| Purchase bills, licence documents | Documentary. Not in any photograph. Permanently out of scope, and saying so is more credible than implying coverage. |
| Coins as calibration references | Indian coin diameters changed between minting series. A reference that is sometimes wrong is worse than none. |

---

## Known limits — state these, do not hide them

1. **Citations are provisional.** Transcribed but not verified against the
   current amended text by a legal reviewer.
2. **Rule 9's Second Schedule is not transcribed.** `CAP-01`/`CAP-02` carry a
   1 mm placeholder and *cannot fail a pack* until it is reviewed.
3. **Placement of declarations is not checked** (PS asks for it). The bounding
   boxes to do it exist; the statutory requirement is not transcribed.
4. **No country-of-origin check** for imports.
5. **Registry is per-device.** Crowd corroboration needs shared storage.
6. **Sharpness `RELATIVE_FLOOR = 0.40` is assumed, not measured.** Validating
   it needs the corpus.
7. **Scale tolerances (±30% / ±10%) are assumed.** Same.

---

## What to do next, in order

1. **`git init` the Python repo.** ~1500 lines of tested code with no history.
   ```bash
   cd SIH-2026 ; printf '.venv*/\n__pycache__/\n*.pyc\ncorpus/\ntruth/\n' > .gitignore ; git init -q ; git add -A ; git commit -m "Add the accuracy evaluation harness"
   ```
2. **Build the annotated set.** Turn on corpus capture, photograph 20–50 packs,
   annotate blind. This is the single highest-value task and it is unglamorous
   manual work. *"n=40 labels, per-field accuracy with 95% CI, κ=0.8 against a
   human annotator"* is a different species of claim from "95% accurate", and
   nobody else will have one.
3. **Calibrate the camera** against a bank card and record the correction. It
   is the last unknown in the height feature.
4. **Get the citations legally reviewed**, and transcribe Rule 9's Second
   Schedule. That unblocks CAP-01/CAP-02 from deferring forever.
5. **Then** consider: signed reference sync, placement checks, country of
   origin.

## For the demo

Scan a deliberately blurred or half-cropped label in front of the judges and
show the app **refusing to answer**, with the reason. Every competing demo will
confidently show green. Thirty seconds, and it is the strongest argument you
have.
