# BUILD_NOTES.md — Screen Translator V1 scaffold

What was verified, what was NOT verified, and what must be checked on
first compile. This file is honest on purpose: nothing here was built in
this VM, because the Gradle daemon IPC is broken here (daemon starts and
listens, but the client always fails with "Could not receive a message
from the daemon" and the daemon dies within seconds — see the workspace
AGENTS.md). **Do not attempt a Gradle build in this VM; build in Android
Studio on a real machine.**

## Verified on 2026-11-15 (web, before writing code)

### Licences (read directly, not from summaries)
- **Hayai OCR code — Apache-2.0.** LICENSE file fetched from
  `https://raw.githubusercontent.com/NopeNopeGuy/hayai-ocr/master/LICENSE`
  — first line "Apache License, Version 2.0, January 2004". GitHub repo
  page also lists "License: Apache License 2.0 (Apache-2.0)". Repo owner
  is now `NopeNopeGuy` (renamed from JustANormalTinkerer); the HF model
  repos still use the old name.
- **Hayai v2 weights — Apache-2.0.** Hugging Face API for
  `JustANormalTinkerer/hayai-ocr-v2` returns the tag
  `license:apache-2.0`.
- **Hayai v2-tflite repo — NO licence declared.** Hugging Face API for
  `JustANormalTinkerer/hayai-ocr-v2-tflite` returns no `license:` tag.
  Treated as runtime-download-only.
- **YOLO11n bubble model — CONTRADICTORY licence.** Hugging Face API for
  `huyvux3005/manga109-segmentation-bubble` returns the tag
  `license:cc-by-nc-4.0`, and the README frontmatter says
  `license: cc-by-nc-4.0` — but the README body claims "released under
  the Apache 2.0 License" with an Apache badge. The task brief said
  "Apache-2.0 confirmed"; that claim matches the README body only.
  Decision: treat the weights as **CC BY-NC-4.0 (non-commercial)** until
  the author clarifies; runtime-download-only, never bundled. This
  changes nothing about the app's own Apache-2.0 licence.
- Hayai README note (useful): "Hayai OCR v2 is NOT based on manga-ocr
  in any way. v1 is heavily inspired by manga-ocr, but is however not
  finetuned from it." — v2 has a clean lineage.

### Dependency versions (Maven metadata, latest stable on 2026-11-15)
- `com.microsoft.onnxruntime:onnxruntime-android` **1.32.0** (Maven Central
  `<latest>`/`<release>`).
- `com.google.ai.edge.litert:litert` **2.3.2** and `litert-gpu` **2.3.2**
  (Google Maven metadata; also the LiteRT docs version table, 2026-10-26).
- `com.google.android.gms:play-services-mlkit-text-recognition` **19.0.2**,
  `play-services-mlkit-text-recognition-japanese` **16.0.2**,
  `com.google.mlkit:translate` **17.0.3**,
  `com.google.mlkit:language-id` **17.0.6** (Google Maven metadata).
- `com.squareup.retrofit2:retrofit` **2.12.0**,
  `converter-gson` **2.12.0**, `com.squareup.okhttp3:okhttp` **4.12.0**
  (Maven Central version lists).
- `org.jetbrains.kotlin:kotlin-gradle-plugin` **2.4.21** (latest stable;
  2.5.0 is still RC).
- Android Gradle Plugin **9.5.1** (latest stable; 9.6.0 is alpha).
  AGP 9.5 requires **Gradle 9.6.0** minimum, JDK 17, max API 37, default
  SDK Build Tools 36.0.0 (developer.android.com release notes).
  Gradle 9.6.0 distribution confirmed to exist (services.gradle.org 200).
- Compose BOM **2026.09.00**, `androidx.activity:activity-compose`
  **1.13.0**, `androidx.core:core-ktx` **1.19.1** (Google Maven metadata).
- LiteRT CompiledModel Kotlin API confirmed from the official docs page
  (`developers.google.com/edge/litert/android`):
  `CompiledModel.create(path, CompiledModel.Options(Accelerator.CPU))`,
  `createInputBuffers()`, `createOutputBuffers()`,
  `run(inputBuffers, outputBuffers)`, `TensorBuffer.writeFloat/readFloat()`.

## NOT verified here — check on first compile / first run

1. **Nothing in this repo was compiled.** Kotlin syntax, Gradle Kotlin
   DSL, the manifest and the Compose code were reviewed by hand only.
   First Android Studio sync + build will surface any typos. Expect a
   few; fix them there, do not fight the VM's broken Gradle daemon.
2. **YOLO11n output layout.** `YoloBubbleDetector` assumes the Ultralytics
   YOLO11-seg ONNX layout: output0 `[1, 4+nc+32, 8400]` or transposed
   `[1, 8400, 4+nc+32]`, input name discovered at runtime, mask
   coefficients = last 32 channels. On first run, log
   `session.inputNames / outputNames` and the output tensor shape, and
   confirm nc (channels − 36) matches the model (expect nc = 1 for a
   bubble-only model). If the export differs, adjust `parseDetections()`.
   Mask-prototype decoding (output1) is a TODO.
3. **YOLO model file size.** Never fetched (no weights were downloaded).
   The download manager shows the real size from the HEAD Content-Length
   before downloading. The `best.pt` filename comes from the repo's file
   list as reported in the research notes — if the resolve URL 404s,
   check the repo page and update `ModelRegistry.YOLO_BUBBLE.downloadUrl`.
4. **Hayai LiteRT export filenames.** The loader matches files by name
   containing `encoder` / `prefill` / `decode`. The exact filenames in
   `litert_exports/wi4` were not listed here. If the match fails, list
   the HF repo files and update the matchers in `HayaiLiteRtOcr.load()`.
5. **Hayai decode loop.** Not implemented (see the TODO in
   `HayaiLiteRtOcr.recognize()`): encoder preprocessing (exact input size
   + normalization), prefill/decode stepping with KV-cache, and
   detokenization must be ported from the Python reference
   (`hayai_ocr` package, LiteRT backend). `DECODE_IMPLEMENTED` stays
   `false` until that is done and tested on real manga crops.
6. **ONNX Runtime `addNnapi()` signature.** The code calls the no-arg
   `addNnapi()` and falls back to CPU-only session creation on failure.
   If the 1.32.0 Java API changed this method, the fallback path still
   works (CPU-only), but fix the call to match the published API.
7. **LiteRT `Accelerator.GPU`.** `Accelerator.CPU` is confirmed from the
   docs snippet; `GPU`/`NPU` are the other documented enum values. The
   GPU attempt is wrapped in try/catch with CPU fallback, so a wrong
   enum member fails loudly at compile time, not at runtime.
8. **APK size estimate (< 25 MB target) is an estimate.** Measure the
   real release APK per ABI in Android Studio (Build → Analyze APK).
   The biggest contributors will be ONNX Runtime native libs and LiteRT.
9. **ML Kit Japanese recogniser class** (`JapaneseTextRecognizerOptions`)
   and the unbundled Play-services artefact names come from ML Kit's
   official docs; re-confirm in Android Studio if the sync complains.
10. **LibreTranslate default instance.** `https://libretranslate.com`
    is the official public instance, but it may rate-limit or require an
    API key under heavy use. The URL is user-configurable in Settings;
    self-hosting is the robust path.

## First-compile checklist (Android Studio)

- [ ] Gradle sync succeeds (Gradle 9.6.0, AGP 9.5.1, JDK 17).
- [ ] `assembleArm64-v8aDebug` builds; install on a test device.
- [ ] Overlay permission → notification permission → MediaProjection
      dialog → bubble appears.
- [ ] Tap bubble on a Japanese manga page: ML Kit OCR + ML Kit translate
      produce overlay bubbles; tap a bubble to expand it.
- [ ] ModelManager: YOLO download shows a real size first; after
      download, logcat shows YOLO session creation + detection boxes.
- [ ] Release APK per ABI: check size with Analyze APK (target < 25 MB).
- [ ] Leave Hayai disabled (`DECODE_IMPLEMENTED = false`) until its
      decode loop is implemented and tested.
