package com.example.smarttagtitlesync

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.mpatric.mp3agic.ID3v24Tag
import com.mpatric.mp3agic.Mp3File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

data class FileResult(
    val name: String,
    val title: String,
    val success: Boolean,
    val message: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var treeUri by remember { mutableStateOf<Uri?>(null) }
    var processing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("یک پوشه آلبوم انتخاب کن.") }
    var results by remember { mutableStateOf<List<FileResult>>(emptyList()) }
    var stripLeadingNumbers by remember { mutableStateOf(true) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: SecurityException) {
                // بعضی فایل‌منیجرها این مجوز را نمی‌دهند؛ برنامه باز هم تلاش می‌کند
            }
            treeUri = uri
            status = "پوشه انتخاب شد."
            results = emptyList()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Smart Tag Title Sync") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "اسم فایل MP3 را می‌گیرد و در Title می‌نویسد.",
                style = MaterialTheme.typography.bodyLarge
            )

            AssistChip(
                onClick = {},
                label = { Text(treeUri?.toString() ?: "هنوز پوشه‌ای انتخاب نشده") }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { folderPicker.launch(null) }) {
                    Text("انتخاب پوشه")
                }

                Button(
                    onClick = {
                        val selected = treeUri ?: return@Button
                        processing = true
                        status = "در حال پردازش..."
                        results = emptyList()
                        scope.launch {
                            val out = processFolder(context, selected, stripLeadingNumbers)
                            results = out
                            status = "تمام شد: ${out.count { it.success }} موفق، ${out.count { !it.success }} ناموفق"
                            processing = false
                        }
                    },
                    enabled = treeUri != null && !processing
                ) {
                    Text("شروع")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = stripLeadingNumbers,
                    onClick = { stripLeadingNumbers = !stripLeadingNumbers },
                    label = { Text("حذف شماره اول نام") }
                )
            }

            if (processing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Text(status, style = MaterialTheme.typography.bodyMedium)

            if (results.isNotEmpty()) {
                Text("گزارش فایل‌ها", style = MaterialTheme.typography.titleMedium)
                results.take(50).forEach { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(item.name, style = MaterialTheme.typography.titleSmall)
                            Text("Title: ${item.title}")
                            Text(if (item.success) "OK" else "Error: ${item.message}")
                        }
                    }
                }
                if (results.size > 50) {
                    Text("و ${results.size - 50} مورد دیگر...")
                }
            }
        }
    }
}

private suspend fun processFolder(
    context: Context,
    treeUri: Uri,
    stripLeadingNumbers: Boolean
): List<FileResult> = withContext(Dispatchers.IO) {
    val root = DocumentFile.fromTreeUri(context, treeUri)
        ?: return@withContext listOf(
            FileResult("", "", false, "پوشه قابل خواندن نیست")
        )

    val mp3Files = mutableListOf<DocumentFile>()
    collectMp3Files(root, mp3Files)

    if (mp3Files.isEmpty()) {
        return@withContext listOf(
            FileResult("", "", false, "فایلی با پسوند mp3 پیدا نشد")
        )
    }

    val out = mutableListOf<FileResult>()

    for (doc in mp3Files) {
        val fileName = doc.name ?: continue
        val title = buildTitleFromFilename(fileName, stripLeadingNumbers)

        try {
            val tempInput = File(context.cacheDir, "in_${UUID.randomUUID()}.mp3")
            val tempOutput = File(context.cacheDir, "out_${UUID.randomUUID()}.mp3")

            context.contentResolver.openInputStream(doc.uri)?.use { input ->
                FileOutputStream(tempInput).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("نشد فایل را باز کرد")

            val mp3 = Mp3File(tempInput.absolutePath)

            val id3v2 = when {
                mp3.hasId3v2Tag() -> mp3.id3v2Tag
                else -> ID3v24Tag().also { mp3.id3v2Tag = it }
            }

            id3v2.title = title
            mp3.save(tempOutput.absolutePath)

            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { output ->
                tempOutput.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("نشد روی فایل بنویسد")

            out += FileResult(fileName, title, true, "ذخیره شد")
            tempInput.delete()
            tempOutput.delete()
        } catch (e: Exception) {
            out += FileResult(fileName, title, false, e.message ?: "خطای ناشناخته")
        }
    }

    out
}

private fun collectMp3Files(folder: DocumentFile, target: MutableList<DocumentFile>) {
    for (child in folder.listFiles()) {
        if (child.isDirectory) {
            collectMp3Files(child, target)
        } else if (child.isFile) {
            val name = child.name?.lowercase(Locale.ROOT).orEmpty()
            if (name.endsWith(".mp3")) {
                target += child
            }
        }
    }
}

private fun buildTitleFromFilename(filename: String, stripLeadingNumbers: Boolean): String {
    val noExt = filename.substringBeforeLast('.').trim()
    val cleaned = if (stripLeadingNumbers) {
        noExt.replace(
            Regex("""^\s*\d+\s*[-._ ]\s*"""),
            ""
        ).trim()
    } else {
        noExt
    }

    return cleaned.ifBlank { noExt }
}
