package dev.crm.screentranslator.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import dev.crm.screentranslator.detection.TextRegion
import java.io.File

/**
 * Hayai OCR v2.1 via its LiteRT/TFLite backend — the manga-trained OCR.
 *
 * Model artefacts: Hugging Face `JustANormalTinkerer/hayai-ocr-v2-tflite`
 * (`litert_exports/{none,wi4,wi8_afp32,dynamic_wi4,dynamic_wi8}`).
 * Default quant is `wi4` (int4). v2.1 LiteRT dynamic-int4 scores CER 3.96%
 * on JMangaBench_Mixed (vs 3.23% for the full model). It reads vertical +
 * horizontal text, furigana, SFX and multi-line bubbles in ONE forward
 * pass — no line segmentation, no rotation hack.
 *
 * LICENCE (verified 2026-11-15, see README.md):
 * - Code: Apache-2.0 — LICENSE file read at
 *   https://github.com/NopeNopeGuy/hayai-ocr/blob/master/LICENSE
 *   (repo owner renamed from JustANormalTinkerer to NopeNopeGuy).
 * - v2 weights: Apache-2.0 (Hugging Face `license:apache-2.0` tag on
 *   `JustANormalTinkerer/hayai-ocr-v2`).
 * - v2-tflite repo: NO licence field declared on Hugging Face — it is a
 *   derivative of the Apache-2.0 v2 weights, but until the repo declares a
 *   licence the exports are RUNTIME-DOWNLOAD-ONLY and are never bundled.
 *
 * STATUS — loader ready, decode loop TODO:
 * - [load] is REAL: it scans the downloaded model dir, finds the exported
 *   `.tflite` graphs (encoder / prefill / decode, matched by filename),
 *   and creates LiteRT [CompiledModel] instances with the verified
 *   CompiledModel API (`CompiledModel.create(path, Options(Accelerator))`,
 *   `createInputBuffers()`, `run()`, `TensorBuffer.readFloat()`).
 * - The autoregressive decode loop + detokenization is NOT implemented
 *   yet: the exact export input shapes, normalization and the KV-cache
 *   stepping must be ported from the Python reference implementation
 *   (`hayai_ocr` package, LiteRT backend — see the GitHub repo above).
 *   Until [DECODE_IMPLEMENTED] is true, [isAvailable] returns false and
 *   the pipeline never selects this engine. [MlKitOcr] is the working
 *   default in the meantime.
 *
 * Hayai v2.5 Nova (~150M params) exists but has NO LiteRT export, so V1
 * uses v2.1 LiteRT. Do NOT point this loader at v2.5 weights.
 */
class HayaiLiteRtOcr(
    /** Directory holding the downloaded LiteRT export files (see ModelRegistry). */
    private val modelDir: File,
) : OcrEngine {

    override val name: String = "Hayai OCR v2.1 (LiteRT)"

    private var encoderModel: CompiledModel? = null
    private var prefillModel: CompiledModel? = null
    private var decodeModel: CompiledModel? = null
    private var loadError: String? = null

    /**
     * False until the decode loop is implemented. The pipeline checks this
     * before selecting Hayai — ML Kit stays the default until then.
     */
    override fun isAvailable(): Boolean = DECODE_IMPLEMENTED && load() is LoadResult.Ready

    /** Loads (lazily) the exported graphs. Safe to call repeatedly. */
    fun load(): LoadResult {
        if (encoderModel != null) return LoadResult.Ready
        if (loadError != null) return LoadResult.Failed(loadError!!)
        if (!modelDir.exists()) {
            return LoadResult.Failed("Hayai model dir missing: ${modelDir.absolutePath}")
        }
        val files = modelDir.listFiles { f -> f.extension == "tflite" }
            ?.toList().orEmpty()
        if (files.isEmpty()) {
            return LoadResult.Failed("No .tflite exports in ${modelDir.absolutePath}")
        }
        val encoderFile = files.firstOrNull { it.name.contains("encoder", ignoreCase = true) }
        val prefillFile = files.firstOrNull { it.name.contains("prefill", ignoreCase = true) }
        val decodeFile = files.firstOrNull { it.name.contains("decode", ignoreCase = true) }
        if (encoderFile == null || prefillFile == null || decodeFile == null) {
            // TODO: confirm the exact export filenames from the HF repo
            // file listing (litert_exports/wi4). The loader matches by
            // filename containing encoder/prefill/decode; if the exports
            // use different names, update the matchers here.
            return LoadResult.Failed(
                "Could not identify encoder/prefill/decode graphs in: " +
                    files.joinToString { it.name },
            )
        }
        return try {
            encoderModel = createModel(encoderFile)
            prefillModel = createModel(prefillFile)
            decodeModel = createModel(decodeFile)
            LoadResult.Ready
        } catch (e: Exception) {
            loadError = e.message
            Log.w(TAG, "Failed to create Hayai CompiledModels", e)
            LoadResult.Failed(e.message ?: "CompiledModel creation failed")
        }
    }

    /**
     * Creates one CompiledModel. GPU is probed first (LiteRT GPU delegate),
     * CPU (XNNPack) is the guaranteed fallback. NNAPI is deprecated from
     * Android 15 and is NOT used by the LiteRT path.
     */
    private fun createModel(file: File): CompiledModel {
        return try {
            CompiledModel.create(
                file.absolutePath,
                CompiledModel.Options(Accelerator.GPU),
            )
        } catch (e: Exception) {
            Log.i(TAG, "GPU accelerator unavailable for ${file.name}, using CPU", e)
            CompiledModel.create(
                file.absolutePath,
                CompiledModel.Options(Accelerator.CPU),
            )
        }
    }

    override suspend fun recognize(
        bitmap: Bitmap,
        region: TextRegion,
        preferJapanese: Boolean,
    ): OcrResult? {
        if (!DECODE_IMPLEMENTED) {
            // Deliberately unreachable while isAvailable() is false; kept as
            // a guard so a future caller cannot silently get empty results.
            throw UnsupportedOperationException(
                "Hayai decode loop not implemented yet — see HayaiLiteRtOcr.kt TODO. " +
                    "Use MlKitOcr until then.",
            )
        }
        // TODO (decode loop — the remaining work before enabling Hayai):
        //  1. Preprocess the region crop to the encoder's expected input:
        //     resize + normalize. The exact input size and normalization
        //     must be read from the export (Python reference: the
        //     `hayai_ocr` package's litert backend, `HayaiOcr(backend=
        //     "litert")` — encoder input is the SigLIP2 NaFlex processor
        //     output; check whether the export bakes the normalization in).
        //  2. Run the encoder CompiledModel (createInputBuffers ->
        //     writeFloat -> run -> readFloat) to get image features.
        //  3. Run prefill once with the BOS token, then step the decode
        //     graph with KV-cache until EOS / max tokens, collecting token
        //     ids. Greedy decoding (repetition_penalty = 1.0).
        //  4. Detokenize with the tokenizer files shipped in the export
        //     dir (tokenizer.json / vocab) and NFKC-normalize the text.
        //  5. Return OcrResult(region.id, text, isVertical = <from region
        //     aspect>, confidence = 1f). Hayai reads vertical text natively
        //     — no rotation needed.
        throw UnsupportedOperationException("Hayai decode loop not implemented yet")
    }

    fun close() {
        runCatching { encoderModel?.close() }
        runCatching { prefillModel?.close() }
        runCatching { decodeModel?.close() }
        encoderModel = null
        prefillModel = null
        decodeModel = null
    }

    sealed interface LoadResult {
        data object Ready : LoadResult
        data class Failed(val reason: String) : LoadResult
    }

    companion object {
        private const val TAG = "HayaiLiteRtOcr"

        /**
         * Flip to true ONLY when the decode loop in [recognize] is
         * implemented and tested against real manga crops. While false,
         * Hayai is download-only infrastructure, never a live engine.
         */
        const val DECODE_IMPLEMENTED: Boolean = false
    }
}
