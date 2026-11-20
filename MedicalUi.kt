package com.example.fittrack

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import android.net.Uri
import android.widget.Toast
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val MCard = Color(0xFF1A1D23)
private val MMuted = Color(0xFF9AA0AA)
private val Red = Color(0xFFE57373)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MedDatePick(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val ms = state.selectedDateMillis
                if (ms != null) onPick(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate())
                else onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) { DatePicker(state = state) }
}

@Composable
fun MedicalDetail(store: MedicalStore, onBack: () -> Unit) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Uri?>(null) }
    var title by remember { mutableStateOf("") }
    var day by remember { mutableStateOf(LocalDate.now()) }
    var pickDate by remember { mutableStateOf(false) }
    var editDoc by remember { mutableStateOf<MedicalDoc?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editDay by remember { mutableStateOf(LocalDate.now()) }
    var editPick by remember { mutableStateOf(false) }
    var deleteDoc by remember { mutableStateOf<MedicalDoc?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pending = uri
            title = store.displayName(uri).substringBeforeLast('.')
            day = LocalDate.now()
        }
    }

    DetailScreen("Medical Reports", onBack) {
        Button(
            onClick = { picker.launch("*/*") },
            colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth()
        ) { Text("+ Upload document (PDF / image)") }

        if (store.docs.isEmpty()) {
            Text("No documents yet. Upload prescriptions, lab results or scans.", color = MMuted, fontSize = 13.sp)
        }
        store.docs.forEach { d ->
            Column(
                modifier = Modifier.fillMaxWidth().background(MCard, RoundedCornerShape(20.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                var menu by remember { mutableStateOf(false) }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(
                        d.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).padding(top = 8.dp)
                    )
                    Box {
                        TextButton(onClick = { menu = true }) {
                            Text("⋮", color = Color.White, fontSize = 22.sp)
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Edit") }, onClick = {
                                menu = false
                                editTitle = d.title
                                editDay = LocalDate.parse(d.reportDay)
                                editDoc = d
                            })
                            DropdownMenuItem(text = { Text("Share") }, onClick = {
                                menu = false
                                try {
                                    val uri = FileProvider.getUriForFile(
                                        context, context.packageName + ".fileprovider", File(d.path)
                                    )
                                    val send = Intent(Intent.ACTION_SEND).setType(d.mime)
                                        .putExtra(Intent.EXTRA_STREAM, uri)
                                        .putExtra(Intent.EXTRA_SUBJECT, d.title)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    context.startActivity(Intent.createChooser(send, "Share report"))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Could not share this file", Toast.LENGTH_SHORT).show()
                                }
                            })
                            DropdownMenuItem(text = { Text("Delete", color = Red) }, onClick = {
                                menu = false
                                deleteDoc = d
                            })
                        }
                    }
                }
                Text(d.fileName, color = MMuted, fontSize = 12.sp)
                Text(
                    "Report date: " + LocalDate.parse(d.reportDay).format(DateTimeFormatter.ofPattern("d MMM yyyy")),
                    color = Color(0xFFD0D3D9), fontSize = 13.sp
                )
                Text(
                    "Uploaded: " + Instant.ofEpochMilli(d.uploadedMs).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")),
                    color = MMuted, fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        try {
                            val uri = FileProvider.getUriForFile(
                                context, context.packageName + ".fileprovider", File(d.path)
                            )
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW).setDataAndType(uri, d.mime)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            )
                        } catch (e: Exception) {
                            Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("Open", color = Red) }
                }
            }
        }
    }

    if (pending != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Save document") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = title, onValueChange = { title = it },
                        label = { Text("Title") }, singleLine = true
                    )
                    OutlinedButton(onClick = { pickDate = true }) {
                        Text("Report date: " + day.format(DateTimeFormatter.ofPattern("d MMM yyyy")))
                    }
                    Text("Upload time is saved automatically.", color = MMuted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ok = store.add(pending!!, title, day.toString())
                    if (!ok) Toast.makeText(context, "Could not save file", Toast.LENGTH_SHORT).show()
                    pending = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } }
        )
    }
    editDoc?.let { d ->
        AlertDialog(
            onDismissRequest = { editDoc = null },
            title = { Text("Edit document") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editTitle, onValueChange = { editTitle = it },
                        label = { Text("Title") }, singleLine = true
                    )
                    OutlinedButton(onClick = { editPick = true }) {
                        Text("Report date: " + editDay.format(DateTimeFormatter.ofPattern("d MMM yyyy")))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    store.update(d.id, editTitle.ifBlank { d.fileName }, editDay.toString())
                    editDoc = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editDoc = null }) { Text("Cancel") } }
        )
    }
    if (editPick) MedDatePick(editDay, { editDay = it; editPick = false }, { editPick = false })
    deleteDoc?.let { d ->
        AlertDialog(
            onDismissRequest = { deleteDoc = null },
            title = { Text("Delete document?") },
            text = { Text("\"${d.title}\" will be removed from this app.") },
            confirmButton = {
                TextButton(onClick = { store.delete(d); deleteDoc = null }) { Text("Delete", color = Red) }
            },
            dismissButton = { TextButton(onClick = { deleteDoc = null }) { Text("Cancel") } }
        )
    }
    if (pickDate) MedDatePick(day, { day = it; pickDate = false }, { pickDate = false })
}
