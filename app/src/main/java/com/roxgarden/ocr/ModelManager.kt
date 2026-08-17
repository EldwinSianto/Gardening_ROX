package com.roxgarden.ocr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private data class ModelAsset(
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long
)

data class ModelFiles(
    val version: String,
    val baseDir: File,
    val detectionModel: File,
    val recognitionModel: File,
    val classificationModel: File,
    val dictionaryFile: File
)

class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"

        // ── PP-OCRv5 default (multilingual, di-download dari Internet) ──
        private const val BASE_URL = "https://models.ente.io/PP-OCRv5/"
        private const val MODEL_VERSION = "pp-ocrv5-202410"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val BUFFER_SIZE = 8 * 1024
        private const val VERSION_FILE_NAME = ".model_version"

        private val REQUIRED_ASSETS = listOf(
            ModelAsset("det.onnx", "d7fe3ea74652890722c0f4d02458b7261d9f5ae6c92904d05707c9eb155c7924", 4_748_769),
            ModelAsset("rec.onnx", "bf66820f48fa99f779974c4df78e5274a9d8e0458c4137e8c5357e40e2c3faf2", 16_517_247),
            ModelAsset("cls.onnx", "f4bb53707100c5f3d59ba834eb05bb400369f20aed35d4b26807b1bfadd2a70e", 582_663),
            ModelAsset("ppocrv5_dict.txt", "d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b", 74_012)
        )

        // ── English-only override (di-bundle di APK assets folder) ──
        // Letakkan 4 file ini di app/src/main/assets/ocr_english/ untuk pakai variant ini:
        //   - det.onnx       (mis. en_PP-OCRv4_mobile_det.onnx, di-rename)
        //   - rec.onnx       (mis. en_PP-OCRv4_mobile_rec.onnx, di-rename)
        //   - cls.onnx       (bisa pakai cls.onnx PP-OCRv5, language-agnostic)
        //   - dict.txt       (mis. en_dict.txt, di-rename)
        // Kalau salah satu file tidak ada, app akan fallback ke PP-OCRv5 download.
        private const val ASSETS_OVERRIDE_DIR = "ocr_english"
        private const val OVERRIDE_CACHE_DIR = "ocr_english"
        private val OVERRIDE_FILES = listOf("det.onnx", "rec.onnx", "cls.onnx", "dict.txt")
        private const val OVERRIDE_VERSION = "english-assets-v1"

        // ── Captcha-specific rec model (di-bundle di APK assets) ──
        // Model rec khusus untuk soal captcha (digit + operator + - *), di-train sendiri.
        // det.onnx & cls.onnx di-reuse dari model utama; cuma rec + dict yang diganti.
        private const val ASSETS_CAPTCHA_DIR = "ocr_captcha"
        private const val CAPTCHA_CACHE_DIR = "ocr_captcha"
        private val CAPTCHA_FILES = listOf("rec.onnx", "dict.txt")
        private const val CAPTCHA_VERSION = "captcha-rec-v1"
    }

    private val cacheDirectory: File by lazy {
        File(context.filesDir, "assets/mobile_ocr")
    }

    private val overrideDirectory: File by lazy {
        File(context.filesDir, OVERRIDE_CACHE_DIR)
    }

    private val captchaDirectory: File by lazy {
        File(context.filesDir, CAPTCHA_CACHE_DIR)
    }

    private val modelMutex = Mutex()

    suspend fun ensureModels(): ModelFiles {
        return modelMutex.withLock {
            // 1. Cek dulu English override di APK assets
            val overrideFiles = tryLoadFromAssets()
            if (overrideFiles != null) {
                Log.d(TAG, "Using English OCR models from assets/$ASSETS_OVERRIDE_DIR")
                return@withLock overrideFiles
            }

            // 2. Fallback: download PP-OCRv5 default
            Log.d(TAG, "English override tidak ditemukan, pakai PP-OCRv5 default")
            downloadDefaultModels()
        }
    }

    // ─────────────────────────────────────────────
    // CAPTCHA REC — model khusus soal captcha (assets)
    // Reuse det.onnx & cls.onnx dari model utama (base),
    // ganti rec.onnx + dict.txt dengan model captcha.
    // Return null kalau asset tidak lengkap (caller fallback ke base).
    // ─────────────────────────────────────────────

    suspend fun ensureCaptchaModels(base: ModelFiles): ModelFiles? {
        return modelMutex.withLock {
            val assetMan = context.assets
            val available = try {
                assetMan.list(ASSETS_CAPTCHA_DIR)?.toSet() ?: emptySet()
            } catch (e: Exception) {
                Log.w(TAG, "Cannot list assets/$ASSETS_CAPTCHA_DIR: ${e.message}")
                return@withLock null
            }
            if (!CAPTCHA_FILES.all { it in available }) {
                Log.d(TAG, "Captcha model files missing: ${CAPTCHA_FILES.filter { it !in available }}")
                return@withLock null
            }

            if (!captchaDirectory.exists()) captchaDirectory.mkdirs()
            for (filename in CAPTCHA_FILES) {
                val target = File(captchaDirectory, filename)
                if (!target.exists()) {
                    try {
                        assetMan.open("$ASSETS_CAPTCHA_DIR/$filename").use { input ->
                            FileOutputStream(target).use { output -> input.copyTo(output) }
                        }
                        Log.d(TAG, "Extracted assets/$ASSETS_CAPTCHA_DIR/$filename → ${target.absolutePath}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to extract captcha $filename: ${e.message}")
                        return@withLock null
                    }
                }
            }

            ModelFiles(
                version = CAPTCHA_VERSION,
                baseDir = captchaDirectory,
                detectionModel = base.detectionModel,
                recognitionModel = File(captchaDirectory, "rec.onnx"),
                classificationModel = base.classificationModel,
                dictionaryFile = File(captchaDirectory, "dict.txt")
            )
        }
    }

    // ─────────────────────────────────────────────
    // ENGLISH OVERRIDE — extract dari APK assets
    // ─────────────────────────────────────────────

    private fun tryLoadFromAssets(): ModelFiles? {
        val assetMan = context.assets
        val available = try {
            assetMan.list(ASSETS_OVERRIDE_DIR)?.toSet() ?: emptySet()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot list assets/$ASSETS_OVERRIDE_DIR: ${e.message}")
            return null
        }

        if (!OVERRIDE_FILES.all { it in available }) {
            val missing = OVERRIDE_FILES.filter { it !in available }
            Log.d(TAG, "Override files missing: $missing")
            return null
        }

        if (!overrideDirectory.exists()) overrideDirectory.mkdirs()

        for (filename in OVERRIDE_FILES) {
            val target = File(overrideDirectory, filename)
            if (!target.exists()) {
                try {
                    assetMan.open("$ASSETS_OVERRIDE_DIR/$filename").use { input ->
                        FileOutputStream(target).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Extracted assets/$ASSETS_OVERRIDE_DIR/$filename → ${target.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract $filename: ${e.message}")
                    return null
                }
            }
        }

        return ModelFiles(
            version = OVERRIDE_VERSION,
            baseDir = overrideDirectory,
            detectionModel = File(overrideDirectory, "det.onnx"),
            recognitionModel = File(overrideDirectory, "rec.onnx"),
            classificationModel = File(overrideDirectory, "cls.onnx"),
            dictionaryFile = File(overrideDirectory, "dict.txt")
        )
    }

    // ─────────────────────────────────────────────
    // PP-OCRv5 DEFAULT — download dari Internet
    // ─────────────────────────────────────────────

    private fun downloadDefaultModels(): ModelFiles {
        prepareDirectory()
        val needsRefresh = shouldRefreshAssets()

        val resolvedFiles = REQUIRED_ASSETS.associateWith { asset ->
            val target = File(cacheDirectory, asset.fileName)
            val valid =
                !needsRefresh && target.exists() && target.length() == asset.sizeBytes && verifySha256(
                    target,
                    asset.sha256
                )

            if (valid) target else downloadAsset(asset, target)
        }

        writeVersionMarker()

        return ModelFiles(
            version = MODEL_VERSION,
            baseDir = cacheDirectory,
            detectionModel = resolvedFiles.getValue(REQUIRED_ASSETS[0]),
            recognitionModel = resolvedFiles.getValue(REQUIRED_ASSETS[1]),
            classificationModel = resolvedFiles.getValue(REQUIRED_ASSETS[2]),
            dictionaryFile = resolvedFiles.getValue(REQUIRED_ASSETS[3])
        )
    }

    private fun prepareDirectory() {
        if (!cacheDirectory.exists()) {
            cacheDirectory.mkdirs()
        }
    }

    private fun shouldRefreshAssets(): Boolean {
        val versionFile = File(cacheDirectory, VERSION_FILE_NAME)
        if (!versionFile.exists()) {
            return true
        }

        val storedVersion = runCatching { versionFile.readText().trim() }.getOrNull()
        return storedVersion != MODEL_VERSION
    }

    private fun downloadAsset(asset: ModelAsset, target: File): File {
        val tempFile = File.createTempFile(asset.fileName, ".download", cacheDirectory)
        var connection: HttpURLConnection? = null
        try {
            connection = URL("$BASE_URL${asset.fileName}").openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                throw IOException("Failed to download ${asset.fileName}: HTTP $responseCode")
            }

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    copyStream(input, output)
                }
            }

            if (tempFile.length() != asset.sizeBytes) {
                throw IOException(
                    "Size mismatch for ${asset.fileName}: expected ${asset.sizeBytes}, got ${tempFile.length()}"
                )
            }

            val actualSha = computeSha256(tempFile)
            if (!actualSha.equals(asset.sha256, ignoreCase = true)) {
                throw IOException("Checksum mismatch for ${asset.fileName}: expected ${asset.sha256}, got $actualSha")
            }

            if (target.exists()) {
                target.delete()
            }
            if (!tempFile.renameTo(target)) {
                throw IOException("Failed to move ${asset.fileName} into cache directory")
            }

            return target
        } finally {
            connection?.disconnect()
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun verifySha256(target: File, expectedSha: String): Boolean {
        val actual = computeSha256(target)
        return actual.equals(expectedSha, ignoreCase = true)
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun copyStream(input: InputStream, output: FileOutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
        output.flush()
    }

    private fun writeVersionMarker() {
        val versionFile = File(cacheDirectory, VERSION_FILE_NAME)
        versionFile.writeText(MODEL_VERSION)
    }
}