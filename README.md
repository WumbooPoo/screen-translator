# Screen Translator

An open-source Android screen translator — tap a floating bubble, and whatever
is on screen gets translated into an overlay. **Mobile-first, lightweight,
no premium features, no ads, no API keys.**

> **Status: V1 scaffold (0.1.0).** The app shell, capture pipeline, ML Kit
> engines and UI are real and working. The YOLO bubble detector and Hayai
> manga OCR are wired behind on-demand downloads with clearly marked TODOs
> (see [What works vs what is TODO](#what-works-vs-what-is-todo)).

## Designed for low-end devices

Built to run well on low-end phones (the reference target is a phone with
~4 GB RAM and an entry-level octa-core chipset). Every design decision
follows from that:

- **Small APK (target < 25 MB).** No model weights bundled — ever. ML Kit
  uses the unbundled Play-services variants (zero APK weight). ONNX Runtime
  and LiteRT are the only native inference deps, shipped per-ABI.
- **Event-driven pipeline, never OCR-per-frame:** tap / screen-settle →
  one still frame → detect → OCR → translate → overlay. 300 ms throttle,
  frame-hash change detection, last overlay kept until a new result is
  ready (anti-flicker).
- **Detection ≤ 1024 px longest side; OCR crops at native resolution**
  (characters ~32–48 px tall). Inference on a 2-thread background
  executor, XNNPack CPU default, GPU/NNAPI probed at runtime with CPU
  fallback. int4/int8 models only.

## Architecture

```
User taps floating bubble
        │
        ▼
ScreenCaptureService (foreground, type=mediaProjection)
  ├─ MediaProjection + VirtualDisplay + ImageReader  (one still frame)
  ├─ CaptureController — "screen settle" check (max 3 attempts, 400 ms)
  │
  ▼
TranslationPipeline  (2-thread executor, 300 ms throttle, frame-hash skip)
  ├─ Detect regions (chain, first hit wins)
  │    1. YoloBubbleDetector  (ONNX Runtime, YOLO11n bubble seg, 1600×1600)
  │    2. MlKitRegionProvider (ML Kit OCR blocks as geometry)
  │    3. BrightRegionDetector (pure-geometry white-balloon fallback)
  ├─ OCR (per region crop)
  │    • MlKitOcr — WORKING default (Japanese + Latin, rotate-if-tall,
  │      furigana size filter)
  │    • HayaiLiteRtOcr — loader ready, decode loop TODO
  ├─ Manga: ReadingOrder (RTL/top-to-bottom) → join split sentences
  │         → SfxFilter (SFX dropped unless the toggle is ON)
  ├─ Translate (Settings engine, fallback offline → online)
  │    • MlKitTranslator — WORKING offline default (EN→ID default,
  │      any pair, auto-detect source)
  │    • LibreTranslateEngine — Retrofit, user-configurable instance
  │    • GoogleCloudTranslator — TODO (needs an API key)
  │
  ▼
OverlayWindowManager (classic Views in TYPE_APPLICATION_OVERLAY windows)
  ├─ Floating bubble (draggable, snaps to edge, tap = translate)
  └─ One window per translated bubble: opaque cover sampled from the
     bubble fill colour + text fitted by binary-search font size,
     draggable, tap = expand (original + translation)
```

In-app UI (Home / Settings / ModelManager / engine picker) is Jetpack
Compose. The floating windows are classic Views on purpose — a ComposeView
in a Service window needs manual LifecycleOwner/Recomposer wiring; see
`OverlayWindowManager.kt`.

## Models

| Model | Source | Licence | Size | Status |
|---|---|---|---|---|
| ML Kit OCR (Latin + Japanese) | Play services (`play-services-mlkit-text-recognition` 19.0.2, `-japanese` 16.0.2) | Google ML Kit ToS | 0 MB in APK (Play services) | **Working** (default OCR) |
| ML Kit Translate models | Play services (`com.google.mlkit:translate` 17.0.3) | Google ML Kit ToS | ~30 MB / language, on demand | **Working** (default engine) |
| ML Kit Language ID | Play services (`com.google.mlkit:language-id` 17.0.6) | Google ML Kit ToS | via Play services | **Working** (auto-detect) |
| YOLO11n bubble detector | [huyvux3005/manga109-segmentation-bubble](https://huggingface.co/huyvux3005/manga109-segmentation-bubble) | **CONTRADICTORY — see below** | Verify at download time | Download-on-demand (optional) |
| Hayai OCR v2.1 LiteRT (int4) | [JustANormalTinkerer/hayai-ocr-v2-tflite](https://huggingface.co/JustANormalTinkerer/hayai-ocr-v2-tflite) | Code Apache-2.0; v2 weights Apache-2.0; tflite repo declares no licence | Verify at download time | Download-on-demand; engine **TODO** (decode loop) |

### Licence findings (verified 2026-11-15)

- **Hayai OCR — Apache-2.0.** The LICENSE file was read at
  https://github.com/NopeNopeGuy/hayai-ocr/blob/master/LICENSE
  (repo owner renamed from JustANormalTinkerer to NopeNopeGuy). The v2
  weights carry the `license:apache-2.0` tag on Hugging Face. The v2-tflite
  repo declares **no licence field** — its exports are therefore
  runtime-download-only and are never bundled until the repo declares one.
- **YOLO11n bubble model — licence contradiction, treat as non-commercial.**
  The repo's Hugging Face metadata and README frontmatter say
  `license: cc-by-nc-4.0`, but the README body claims "released under the
  Apache 2.0 License". Until the author clarifies, the weights are treated
  as **CC BY-NC-4.0**: runtime-download-only, non-commercial use only, never
  bundled. The app's own code stays Apache-2.0 either way.
- **Do NOT use:** comic-text-detector (GPL-3.0 — would force the whole app
  to GPL-3.0) and manga-ocr-nar-small weights (CC BY-NC-SA, non-commercial).

## How to build (Android Studio)

1. Open this folder in Android Studio (Ladybug / Meerkat or newer —
   AGP 9.5 needs Android Studio 2026.x "Rabbit 2" or later).
2. Let Gradle sync (Gradle 9.6.0, Kotlin 2.4.21, AGP 9.5.1 — all pinned in
   `gradle/libs.versions.toml`; every version was verified against Maven
   Central / Google Maven on 2026-11-15, see `BUILD_NOTES.md`).
3. Run on a device (minSdk 26, targetSdk 35, compileSdk 36).
4. Build a release APK: **Build → Generate Signed App Bundle / APK**,
   or `./gradlew assembleArm64-v8aRelease` (per-ABI splits are on, so
   install the APK matching the device ABI — arm64-v8a on most modern
   phones). If the Gradle Wrapper jar is not in the repo yet, generate it
   once with `gradle wrapper` on a machine where Gradle runs (Android
   Studio does this on first sync); CI instead provisions Gradle 9.6.0
   directly, see `.github/workflows/android.yml`.

No API keys, no accounts, no backend. The only network use is model
downloads (Wi-Fi only by default) and the optional LibreTranslate engine.

## How to test

1. Install the arm64-v8a debug APK on a test device.
2. Open the app → tap the big ON button:
   - Grant "display over other apps" (overlay permission).
   - Grant notifications (Android 13+).
   - Accept the "record screen" dialog (MediaProjection — asked every
     session by Android, by design).
3. A small bubble (文) appears at the right edge. Open any app (browser,
   manga reader, game), tap the bubble, wait ~1–5 s: translated bubbles
   appear over the text.
4. Drag a translated bubble if it covers something; tap it to see the
   original + translation.
5. Settings → Mode manga ON (default), SFX OFF (default).
   ModelManager → download the YOLO bubble model for better manga
   detection (watch the size shown before downloading).

## What works vs what is TODO

| Area | Status |
|---|---|
| App shell, Home / Settings / engine picker / ModelManager UI | Working |
| MediaProjection single-frame capture + settle detection | Working |
| Floating bubble (drag, snap-to-edge, tap to translate) | Working |
| ML Kit OCR (Japanese + Latin, rotate-if-tall, furigana filter) | Working |
| ML Kit offline translation (any pair, auto-detect, ~30 MB/lang) | Working |
| LibreTranslate online engine (Retrofit, configurable instance) | Working |
| Reading order (RTL/top-to-bottom), SFX filter (default OFF) | Working |
| Bubble-fitted overlay (opaque cover, binary-search font size, drag, tap-expand) | Working |
| Model downloads (size-first, Wi-Fi-only default, delete) | Working |
| YOLO11n bubble detector (ONNX Runtime, NNAPI→CPU fallback) | Wired; needs the model downloaded + first-run verification of the output layout (see BUILD_NOTES.md) |
| Hayai OCR v2.1 LiteRT | Loader + CompiledModel wiring done; **decode loop + detokenization TODO** — ML Kit stays the default until then |
| Google Cloud Translation | TODO stub (needs a user-supplied API key) |
| Mask-polygon overlay fitting (YOLO masks) | TODO (boxes only in V1) |
| Webtoon / long-strip support, Chinese/Korean manga OCR | Explicitly out of V1 scope |

## Licence

Apache License 2.0 — see `LICENSE`. The app's own code is Apache-2.0.
Third-party models keep their own licences (table above); the app never
bundles model weights, so their licences do not change the app's licence —
but the YOLO bubble weights' unclear licence is why they are
download-on-demand only.
