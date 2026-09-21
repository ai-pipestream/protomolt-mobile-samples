package ai.pipestream.samples.courtsearch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    private val model by lazy { SearchModel(lifecycleScope) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val launchQuery = intent.getStringExtra("query")
        val resetIndex = intent.getBooleanExtra("resetIndex", false)
        val launchMode = intent.getStringExtra("mode")
        val launchOpen = intent.getIntExtra("open", 0).takeIf { it > 0 }
        val jump = intent.getBooleanExtra("jump", false)
        val parity = intent.getBooleanExtra("parity", false)
        setContent {
            CourtSearchTheme {
                LaunchedEffect(Unit) { model.open(applicationContext, launchQuery, resetIndex, launchMode, launchOpen, parity) }
                CourtSearchApp(model, jumpOnOpen = jump)
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) runBlocking { model.close() }
        super.onDestroy()
    }
}
