package com.example.fittrack

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun kcalPerMinute(type: String): Int = when (type) {
    "Run" -> 10
    "Cycle" -> 7
    "Gym" -> 6
    else -> 4
}

@Composable
fun AddWorkoutDialog(onDismiss: () -> Unit, onSave: (String, Int, Int) -> Unit) {
    val types = listOf("Walk", "Run", "Gym", "Cycle")
    var type by remember { mutableStateOf("Walk") }
    var minutes by remember { mutableStateOf("") }
    var cal by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log workout") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    types.forEach { t ->
                        val selected = t == type
                        Box(
                            modifier = Modifier
                                .background(
                                    if (selected) Color(0xFF81C784) else Color(0xFF2A2E36),
                                    RoundedCornerShape(50)
                                )
                                .clickable { type = t }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                t,
                                color = if (selected) Color.Black else Color.White,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it.filter(Char::isDigit).take(4) },
                    label = { Text("Duration (minutes)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = cal,
                    onValueChange = { cal = it.filter(Char::isDigit).take(5) },
                    label = { Text("Calories burned (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val m = minutes.toInt()
                    val kcal = if (cal.isNotEmpty()) cal.toInt() else m * kcalPerMinute(type)
                    onSave(type, m, kcal)
                },
                enabled = minutes.isNotEmpty() && minutes.toInt() > 0
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun WorkoutCard(
    minutes: Int,
    goalMinutes: Int,
    workouts: List<Workout>,
    onAddClick: () -> Unit,
    onDelete: (Long) -> Unit,
    onStart: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1D23), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Workout", color = Color(0xFF9AA0AA), fontSize = 14.sp)
                Text(
                    "$minutes / $goalMinutes min",
                    color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold
                )
            }
            Button(
                onClick = onAddClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF81C784),
                    contentColor = Color.Black
                )
            ) { Text("+ Log workout") }
        }
        LinearProgressIndicator(
            progress = { (minutes / goalMinutes.toFloat()).coerceIn(0f, 1f) },
            color = Color(0xFF81C784),
            trackColor = Color(0xFF2A2E36),
            modifier = Modifier.fillMaxWidth()
        )
        if (onStart != null) {
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5A0), contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth()
            ) { Text("▶  Start walk / run") }
        }
        workouts.forEach { w ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${w.type}  ·  ${w.minutes} min  ·  ${w.calories} kcal" +
                            if (w.tracked) "  ·  %.2f km".format(w.distanceM / 1000.0) else "",
                    color = Color(0xFFD0D3D9), fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onDelete(w.id) }) {
                    Text("Remove", color = Color(0xFF9AA0AA), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun AddMealDialog(onDismiss: () -> Unit, onSave: (String, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var cal by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add meal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Meal (e.g. Rice and chicken)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = cal,
                    onValueChange = { cal = it.filter(Char::isDigit).take(5) },
                    label = { Text("Calories (kcal)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), cal.toInt()) },
                enabled = name.isNotBlank() && cal.isNotEmpty()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun CalorieCard(
    calories: Int,
    goal: Int,
    meals: List<Meal>,
    onAddClick: () -> Unit,
    onDelete: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1D23), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Calories", color = Color(0xFF9AA0AA), fontSize = 14.sp)
                Text(
                    "%,d / %,d kcal".format(calories, goal),
                    color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold
                )
            }
            Button(
                onClick = onAddClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFB74D),
                    contentColor = Color.Black
                )
            ) { Text("+ Add meal") }
        }
        LinearProgressIndicator(
            progress = { (calories / goal.toFloat()).coerceIn(0f, 1f) },
            color = Color(0xFFFFB74D),
            trackColor = Color(0xFF2A2E36),
            modifier = Modifier.fillMaxWidth()
        )
        meals.forEach { meal ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${meal.name}  ·  ${meal.calories} kcal",
                    color = Color(0xFFD0D3D9), fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onDelete(meal.id) }) {
                    Text("Remove", color = Color(0xFF9AA0AA), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun WaterCard(ml: Int, goalMl: Int, onAdd: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1D23), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Water", color = Color(0xFF9AA0AA), fontSize = 14.sp)

                Text(
                    text = if (ml < 1000) {
                        "$ml ml"
                    } else if (ml % 1000 == 0) {
                        "${ml / 1000} L"
                    } else {
                        "%.2f L".format(ml / 1000f)
                    },
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )

            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { onAdd(-250) }) {
                    Text("-250", color = Color(0xFF9AA0AA))
                }
                Button(
                    onClick = { onAdd(250) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4FC3F7),
                        contentColor = Color.Black
                    )
                ) { Text("+1 Glass") }
            }
        }
        LinearProgressIndicator(
            progress = { (ml / goalMl.toFloat()).coerceIn(0f, 1f) },
            color = Color(0xFF4FC3F7),
            trackColor = Color(0xFF2A2E36),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun StepRing(steps: Int, goal: Int) {
    var start by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { start = true }
    val progress by animateFloatAsState(
        targetValue = if (start) steps / goal.toFloat() else 0f,
        animationSpec = tween(1200),
        label = "ring"
    )
    Box(
        modifier = Modifier.fillMaxWidth().height(260.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(240.dp)) {
            val stroke = 26.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = Color(0xFF23262D), startAngle = -90f, sweepAngle = 360f,
                useCenter = false, topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = Color(0xFF00E5A0), startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false, topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("%,d".format(steps), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Text("of %,d steps".format(goal), color = Color(0xFF9AA0AA), fontSize = 14.sp)
        }
    }
}