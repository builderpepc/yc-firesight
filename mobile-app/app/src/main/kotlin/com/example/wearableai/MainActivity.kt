package com.example.wearableai

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.io.File
import com.example.wearableai.databinding.ActivityMainBinding
import com.example.wearableai.shared.NoteCategory
import com.example.wearableai.ui.FloorPlanScreen
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            viewModel.onPermissionsGranted()
        } else {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
        }
    }

    private val floorPlanPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.loadFloorPlan(it) } }

    private val docsPicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> -> viewModel.ingestDocs(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnInspection.setOnClickListener { viewModel.toggleInspection() }
        binding.btnLoadFloorPlan.setOnClickListener {
            floorPlanPicker.launch(arrayOf("image/png", "image/jpeg", "image/*"))
        }
        binding.btnLoadDocs.setOnClickListener {
            docsPicker.launch(arrayOf("text/plain", "text/markdown", "text/*"))
        }
        binding.btnSummary.setOnClickListener { viewModel.speakSummary() }
        binding.btnCapture.setOnClickListener { viewModel.captureNow() }
        binding.btnExportPdf.setOnClickListener { viewModel.exportPdf() }
        binding.cbLocalInference.setOnCheckedChangeListener { _, checked ->
            viewModel.setForceLocal(checked)
        }

        binding.floorPlanCompose.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val path by viewModel.floorPlanPath.collectAsState()
                val pins by viewModel.pins.collectAsState()
                FloorPlanScreen(
                    floorPlanPath = path,
                    pins = pins,
                    onAddPinAtNorm = { x, y -> viewModel.addManualPin(x, y) },
                    onPinTap = { /* no-op for now; could show details sheet */ },
                )
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.status.collect { binding.tvStatus.text = it } }
                launch {
                    viewModel.notes.collect { list ->
                        binding.tvNotes.text = renderNotes(list)
                        binding.notesScroll.post {
                            binding.notesScroll.fullScroll(android.view.View.FOCUS_DOWN)
                        }
                    }
                }
                launch { viewModel.inspectionEnabled.collect { binding.btnInspection.isEnabled = it } }
                launch { viewModel.inspectionLabel.collect { binding.btnInspection.text = it } }
                launch { viewModel.forceLocal.collect { binding.cbLocalInference.isChecked = it } }
                launch {
                    viewModel.docsIndexedChunks.collect { count ->
                        binding.btnLoadDocs.contentDescription =
                            if (count > 0) "Load documents ($count chunks indexed)" else "Load documents"
                    }
                }
                launch { viewModel.pdfExported.collect { openPdf(it) } }
            }
        }

        requestPermissions()
    }

    private fun openPdf(file: File) {
        val uri: Uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, "Can't share PDF: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No PDF viewer installed", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderNotes(list: List<com.example.wearableai.shared.Note>): String {
        if (list.isEmpty()) return ""
        val byCat = list.groupBy { it.category }
        val sb = StringBuilder()
        for (cat in NoteCategory.entries) {
            val items = byCat[cat] ?: continue
            sb.append("## ").append(cat.heading).append('\n')
            for (n in items) {
                sb.append("• ").append(n.markdown)
                if (n.photoPath != null) sb.append(" [📷]")
                sb.append('\n')
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    private fun requestPermissions() {
        val needed = arrayOf(
            Manifest.permission.RECORD_AUDIO,
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) {
            viewModel.onPermissionsGranted()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
