package ai.pipestream.samples.courtsearch

import android.content.res.AssetManager
import java.io.File

/**
 * Text to unit vector, on the device: the sample's own Rust embedder
 * (embedder-ffi) over a Model2Vec table. The engine ships no embedder by design,
 * so the app is the caller that supplies vectors.
 */
class Embedder(directory: File) : AutoCloseable {
    private val handle: Long = nativeOpen(directory.path)
    val dimensions: Int

    init {
        if (handle == 0L) throw EngineException("embedder", -1, nativeLastError())
        dimensions = nativeDim(handle)
    }

    /** WordPiece pieces the text tokenizes to, `[UNK]`s included. */
    fun pieces(text: String): Int = nativePieces(handle, text)

    /** Null when the text has no vector: empty, or entirely out of vocabulary. */
    fun embed(text: String): FloatArray? = nativeEmbed(handle, text)

    override fun close() = nativeClose(handle)

    companion object {
        const val MODEL = "potion-retrieval-32M"

        init {
            System.loadLibrary("court_embedder_ffi")
        }

        /**
         * The embedder maps the table from a file, and APK assets are not files, so
         * the model is copied into app storage once. Returns null when the APK was
         * built without the model (scripts/fetch-model.sh): search by meaning is
         * then simply not offered.
         */
        fun install(assets: AssetManager, filesDir: File): File? {
            val names = listOf("tokenizer.json", "model.safetensors")
            if (assets.list(MODEL).orEmpty().toSet().containsAll(names).not()) return null
            val target = File(filesDir, "models/$MODEL").apply { mkdirs() }
            for (name in names) {
                val out = File(target, name)
                val length = assets.openFd("$MODEL/$name").use { it.length }
                if (out.length() == length) continue
                assets.open("$MODEL/$name").use { input -> out.outputStream().use { input.copyTo(it, 1 shl 20) } }
            }
            return target
        }

        @JvmStatic private external fun nativeOpen(directory: String): Long
        @JvmStatic private external fun nativeDim(handle: Long): Int
        @JvmStatic private external fun nativeEmbed(handle: Long, text: String): FloatArray?
        @JvmStatic private external fun nativePieces(handle: Long, text: String): Int
        @JvmStatic private external fun nativeClose(handle: Long)
        @JvmStatic private external fun nativeLastError(): String
    }
}
