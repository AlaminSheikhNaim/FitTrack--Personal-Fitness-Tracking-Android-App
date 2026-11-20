package com.example.fittrack

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.zIndex
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Change the name shown at the top of the dashboard here
const val APP_NAME = "FitTrack"

// How visible the dashboard picture is: 0.25 = 25% visible, 1.0 = fully visible
private const val BACKGROUND_ALPHA = 0.25f

// Shown on the Developer Details screen
private const val DEV_NAME = "Alamin Sheikh Naim"
private const val DEV_DEPARTMENTS = "CSE, ECE"
private const val DEV_UNIVERSITY = "North South University"
private const val DEV_CITY = "Dhaka"
private const val DEV_EMAIL = "alaminsheikhnaim2013@gmail.com"

private val MutedGray = Color(0xFF9AA0AA)
private val CardColor = Color(0xFF1A1D23)

enum class Section {
    STEPS, WATER, CALORIES, WORKOUT, SLEEP, SETTINGS, DEVELOPER,
    WEIGHT, HEIGHT, VACCINATION, MEDICAL, SLEEP_MONITOR, RESET, PRAYER, MENTAL, TARGETS, APPEARANCE
}

private fun hm(totalMinutes: Int): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h == 0 -> "${m}m"
        m == 0 -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

private fun timeOf(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("h:mm a"))

@Composable
fun AppRoot(
    tracker: StepTracker,
    water: WaterStore,
    food: FoodStore,
    workouts: WorkoutStore,
    sleep: SleepStore,
    health: HealthStore,
    settings: AppSettings,
    openMental: Boolean = false,
    onNewDay: () -> Unit
) {
    var section by rememberSaveable { mutableStateOf<Section?>(if (openMental) Section.MENTAL else null) }
    var devFromHome by rememberSaveable { mutableStateOf(false) }
    val appCtx = LocalContext.current
    val medical = remember { MedicalStore(appCtx) }
    val prayer = remember { PrayerStore(appCtx) }
    val mental = remember { MentalStore(appCtx) }
    val shownDay = remember { LocalDate.now() }
    val shownSig = remember { ResetTimes.signature() }

    // While the app is open, notice when the clock passes 12:00 am
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            if (LocalDate.now() != shownDay || ResetTimes.signature() != shownSig) onNewDay()
            tracker.checkReset()    // steps start again from 0 at the steps reset time
            workouts.checkReset()   // workouts start again from 0 at 5:00 am
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // The chosen background photo sits behind the dashboard and every inside screen
        DashboardBackground(settings)
        Box(modifier = Modifier.fillMaxSize().lightFilter(settings.lightMode)) {
        when (section) {
            null -> Dashboard(
                tracker, water, food, workouts, sleep, health, settings, medical, prayer, mental,
                onOpen = { devFromHome = (it == Section.DEVELOPER); section = it }
            )
            Section.STEPS -> StepsDetail(tracker, water, food, workouts, sleep, settings) { section = null }
            Section.WATER -> WaterDetail(water, settings) { section = null }
            Section.CALORIES -> CaloriesDetail(food, settings) { section = null }
            Section.WORKOUT -> WorkoutDetail(workouts, settings) { section = null }
            Section.SLEEP -> SleepDetail(sleep, settings) { section = null }
            Section.WEIGHT -> WeightDetail(health) { section = null }
            Section.HEIGHT -> HeightDetail(health) { section = null }
            Section.VACCINATION -> VaccinationDetail(health) { section = null }
            Section.MEDICAL -> MedicalDetail(medical) { section = null }
            Section.SLEEP_MONITOR -> DetailScreen("Sleep Quality Monitor", { section = null }) {
                MonitorSection()
            }
            Section.SETTINGS -> SettingsDetail(
                settings,
                onOpenTargets = { section = Section.TARGETS },
                onOpenAppearance = { section = Section.APPEARANCE },
                onOpenDeveloper = { devFromHome = false; section = Section.DEVELOPER },
                onOpenReset = { section = Section.RESET }
            ) { section = null }
            Section.PRAYER -> PrayerDetail(prayer) { section = null }
            Section.MENTAL -> MentalDetail(mental, tracker, water, food, workouts, sleep, prayer, settings) { section = null }
            Section.TARGETS -> SetTargetsDetail(settings) { section = Section.SETTINGS }
            Section.APPEARANCE -> AppearanceDetail(settings) { section = Section.SETTINGS }
            Section.RESET -> ResetScheduleDetail { section = Section.SETTINGS }
            Section.DEVELOPER -> DeveloperDetail(settings.lightMode) {
                section = if (devFromHome) null else Section.SETTINGS
            }
        }
        }
    }

    // After you wake up: asks whether to record the estimated sleep (on any screen)
    WakeUpPrompt(sleep)

    // A walk or run in progress stays on top until End is pressed
    if (WalkTracker.active) WalkDialog(workouts, health)
}

// The user's own photo (Settings > Background) or the built-in dashboard_bg picture
@Composable
private fun DashboardBackground(settings: AppSettings) {
    val context = LocalContext.current
    val custom = remember(settings.bgVersion) {
        runCatching {
            val f = settings.bgFile
            if (!f.exists()) null else {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                var sample = 1
                while (o.outWidth / sample > 2000 || o.outHeight / sample > 2000) sample *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
            }
        }.getOrNull()
    }
    if (custom != null) {
        Image(
            bitmap = custom, contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop, alpha = BACKGROUND_ALPHA
        )
        return
    }
    val resId = remember {
        context.resources.getIdentifier("dashboard_bg", "drawable", context.packageName)
    }
    if (resId != 0) {
        Image(
            painter = painterResource(id = resId), contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop, alpha = BACKGROUND_ALPHA
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Dashboard(
    tracker: StepTracker,
    water: WaterStore,
    food: FoodStore,
    workouts: WorkoutStore,
    sleep: SleepStore,
    health: HealthStore,
    settings: AppSettings,
    medical: MedicalStore,
    prayer: PrayerStore,
    mental: MentalStore,
    onOpen: (Section) -> Unit
) {
    var dialog by remember { mutableStateOf<String?>(null) }
    var editMode by remember { mutableStateOf(false) }
    var order by remember { mutableStateOf(mergeOrder(settings.dashOrder)) }
    val scroll = rememberScrollState()
    BackHandler(enabled = editMode) { editMode = false }
    val dayFormat = DateTimeFormatter.ofPattern("d MMM yyyy")

    // Short lines shown under the Health Track values
    val latestW = health.weights.lastOrNull()
    val latestH = health.heights.lastOrNull()
    val weightSub = if (latestW != null && latestH != null) {
        val bmi = bmiOf(latestW.value, latestH.value)
        "BMI ${cleanNum(bmi)} · ${bmiLabel(bmi)}"
    } else {
        "Tap +Add to start"
    }
    val heightSub = if (latestH != null) {
        "Last: " + Instant.ofEpochMilli(latestH.tsMs).atZone(ZoneId.systemDefault())
            .toLocalDate().format(dayFormat)
    } else {
        "Tap +Add to start"
    }
    val lastVaccine = health.vaccines.firstOrNull()
    val vaccineSub = if (lastVaccine != null) {
        "${lastVaccine.name} · ${LocalDate.parse(lastVaccine.day).format(dayFormat)}"
    } else {
        "Tap +Add to start"
    }
    val vaccineCount = health.vaccines.size
    val medicalCount = medical.docs.size

    Box(modifier = Modifier.fillMaxSize()) {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(scroll, enabled = true)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        APP_NAME, color = Color.White,
                        fontSize = 30.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM")),
                        color = MutedGray, fontSize = 13.sp
                    )
                }
                if (editMode) {
                    Button(
                        onClick = { editMode = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00E5A0), contentColor = Color.Black
                        )
                    ) { Text("Done") }
                } else {
                    TextButton(onClick = { onOpen(Section.SETTINGS) }) {
                        Text("⚙", color = Color.White, fontSize = 26.sp)
                    }
                }
            }

            DashboardCards(
                editMode = editMode, scroll = scroll, order = order, onOrder = { order = it },
                onSave = { settings.saveDashOrder(order) }, onReset = {
                    order = DASH_DEFAULT; settings.saveDashOrder(order)
                },
                onEnterEdit = { editMode = true },
                prayerOn = settings.prayerEnabled
            ) { key ->
                when (key) {
                    "steps" -> Box(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                            .clickable { onOpen(Section.STEPS) }
                    ) { StepRing(steps = tracker.stepsToday, goal = settings.stepGoal) }
                    "water" -> SummaryTile(
                        "Water", formatWater(water.mlToday),
                        water.mlToday / settings.waterGoalMl.toFloat(),
                        Color(0xFF4FC3F7), Modifier.fillMaxWidth(),
                        actionLabel = "+1 Glass",
                        onAction = { water.addGlass(settings.cupMl) }
                    ) { onOpen(Section.WATER) }
                    "calories" -> SummaryTile(
                        "Calories", "%,d kcal".format(food.caloriesToday),
                        food.caloriesToday / settings.calorieGoal.toFloat(),
                        Color(0xFFFFB74D), Modifier.fillMaxWidth(),
                        actionLabel = "+Meal", onAction = { dialog = "meal" }
                    ) { onOpen(Section.CALORIES) }
                    "workout" -> SummaryTile(
                        "Workout", "${workouts.minutesToday} min",
                        workouts.minutesToday / settings.workoutGoalMin.toFloat(),
                        Color(0xFF81C784), Modifier.fillMaxWidth(),
                        actionLabel = "+Add", onAction = { dialog = "workout" }
                    ) { onOpen(Section.WORKOUT) }
                    "sleep" -> SummaryTile(
                        "Sleep", hm(sleep.nightMinutesToday),
                        sleep.nightMinutesToday / 480f,
                        Color(0xFFB388FF), Modifier.fillMaxWidth()
                    ) { onOpen(Section.SLEEP) }
                    "nap" -> SummaryTile(
                        "Power Nap", hm(sleep.napMinutesToday),
                        null, Color(0xFFF48FB1), Modifier.fillMaxWidth(),
                        actionLabel = "+Add", onAction = { dialog = "nap" }
                    ) { onOpen(Section.SLEEP) }
                    "total" -> SummaryTile(
                        "Total Sleep", hm(sleep.minutesToday),
                        sleep.minutesToday / 480f,
                        Color(0xFF9FA8DA), Modifier.fillMaxWidth()
                    ) { onOpen(Section.SLEEP) }
                    "monitor" -> SummaryTile(
                        "Sleep Quality Monitor", "Track Sleep Quality",
                        null, Color(0xFF80CBC4), Modifier.fillMaxWidth(),
                        subtitle = "Monitor and quality reports"
                    ) { onOpen(Section.SLEEP_MONITOR) }
                    "weight" -> SummaryTile(
                        "Weight",
                        if (latestW != null) "${cleanNum(latestW.value)} kg" else "—",
                        null, Color(0xFFFF8A65), Modifier.fillMaxWidth(),
                        actionLabel = "+Add", onAction = { dialog = "weight" },
                        subtitle = weightSub
                    ) { onOpen(Section.WEIGHT) }
                    "height" -> SummaryTile(
                        "Height",
                        if (latestH != null) formatHeight(latestH.value) else "—",
                        null, Color(0xFF4DD0E1), Modifier.fillMaxWidth(),
                        actionLabel = "+Add", onAction = { dialog = "height" },
                        subtitle = heightSub
                    ) { onOpen(Section.HEIGHT) }
                    "vaccine" -> SummaryTile(
                        "Vaccination",
                        if (vaccineCount == 1) "1 vaccine" else "$vaccineCount vaccines",
                        null, Color(0xFFBA68C8), Modifier.fillMaxWidth(),
                        actionLabel = "+Add", onAction = { dialog = "vaccine" },
                        subtitle = vaccineSub
                    ) { onOpen(Section.VACCINATION) }
                    "medical" -> SummaryTile(
                        "Medical Reports",
                        if (medicalCount == 1) "1 document" else "$medicalCount documents",
                        null, Color(0xFFE57373), Modifier.fillMaxWidth(),
                        subtitle = "Upload & keep reports"
                    ) { onOpen(Section.MEDICAL) }
                    "mental" -> SummaryTile(
                        "Mental Health",
                        if (mental.todayMood > 0) "${MOOD_FACES[mental.todayMood - 1]} ${MOOD_LABELS[mental.todayMood - 1]}"
                        else "Check in",
                        null, MentalPurple, Modifier.fillMaxWidth(),
                        subtitle = if (mental.todayMood > 0) "Today's check-in done" else "How are you feeling today?"
                    ) { onOpen(Section.MENTAL) }
                    "eye" -> EyeHealthCard()
                    "prayer" -> PrayerDashboardCard(prayer) { onOpen(Section.PRAYER) }
                    else -> {}
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "This app is developed by $DEV_NAME",
                    color = MutedGray, fontSize = 12.sp, textAlign = TextAlign.Center,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { if (!editMode) onOpen(Section.DEVELOPER) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Text(
                    "© ${LocalDate.now().year} $DEV_NAME. All rights reserved.",
                    color = MutedGray, fontSize = 11.sp, textAlign = TextAlign.Center
                )
            }
        }
    }

    when (dialog) {
        "meal" -> AddMealDialog(
            onDismiss = { dialog = null },
            onSave = { name, kcal ->
                food.add(name, kcal)
                dialog = null
            }
        )
        "workout" -> AddWorkoutDialog(
            onDismiss = { dialog = null },
            onSave = { type, min, kcal ->
                workouts.add(type, min, kcal)
                dialog = null
            }
        )
        "nap" -> SleepEditDialog(
            type = SleepType.NAP,
            initial = null,
            onDismiss = { dialog = null },
            onSave = { start, end ->
                sleep.addManual(SleepType.NAP, start, end)
                dialog = null
            }
        )
        "weight" -> WeightDialog(health) { dialog = null }
        "height" -> HeightDialog(health) { dialog = null }
        "vaccine" -> VaccineDialog(health) { dialog = null }
    }
}

@Composable
private fun TinyButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.22f))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, softWrap = false
        )
    }
}

@Composable
private fun SummaryTile(
    title: String,
    value: String,
    progress: Float?,
    color: Color,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(CardColor.copy(alpha = 0.92f))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 26.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title, color = MutedGray, fontSize = 12.sp,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(end = 4.dp)
            )
            if (actionLabel != null && onAction != null) {
                TinyButton(actionLabel, color, onAction)
            }
        }
        Text(
            value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                color = color,
                trackColor = Color(0xFF2A2E36),
                modifier = Modifier.fillMaxWidth()
            )
        } else if (subtitle != null) {
            Text(
                subtitle, color = MutedGray, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        } else {
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
fun DetailScreen(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("←", color = Color.White, fontSize = 22.sp)
            }
            Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

@Composable
private fun InfoCard(title: String, rows: List<Pair<String, String>>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardColor, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, color = MutedGray, fontSize = 14.sp)
        rows.forEach { (label, value) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(label, color = Color(0xFFD0D3D9), fontSize = 14.sp)
                Text(
                    value, color = Color.White, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f).padding(start = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun StepsDetail(
    tracker: StepTracker, water: WaterStore, food: FoodStore,
    workouts: WorkoutStore, sleep: SleepStore,
    settings: AppSettings, onBack: () -> Unit
) {
    val steps = tracker.stepsToday
    DetailScreen("Steps", onBack) {
        StepRing(steps = steps, goal = settings.stepGoal)
        ActivityRatingCard(tracker, water, food, workouts, sleep, settings)
        InfoCard(
            "Today (approximate)",
            listOf(
                "Goal reached" to "${steps * 100 / settings.stepGoal}%",
                "Distance" to "%.2f km".format(steps * 0.00075),
                "Calories burned" to "${(steps * 0.04).toInt()} kcal"
            )
        )
        StepsReportSection(tracker, settings)
    }
}

@Composable
private fun GlassesCard(water: WaterStore, settings: AppSettings) {
    val blue = Color(0xFF4FC3F7)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardColor, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Water", color = MutedGray, fontSize = 14.sp)
                Text(
                    "${formatWater(water.mlToday)} / ${formatWater(settings.waterGoalMl)}",
                    color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    "${water.glassesToday} glasses  ·  cup ${settings.cupMl} ml",
                    color = MutedGray, fontSize = 12.sp
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { water.removeLast() },
                    enabled = water.glassesToday > 0
                ) { Text("-1", color = MutedGray) }
                Button(
                    onClick = { water.addGlass(settings.cupMl) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = blue,
                        contentColor = Color.Black
                    )
                ) { Text("+1 glass") }
            }
        }
        LinearProgressIndicator(
            progress = { (water.mlToday / settings.waterGoalMl.toFloat()).coerceIn(0f, 1f) },
            color = blue,
            trackColor = Color(0xFF2A2E36),
            modifier = Modifier.fillMaxWidth()
        )
        water.entriesToday.forEachIndexed { index, e ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Glass ${water.entriesToday.size - index}  ·  ${timeOf(e.tsMs)}  ·  ${formatWater(e.ml)}",
                    color = Color(0xFFD0D3D9), fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { water.delete(e.id) }) {
                    Text("Remove", color = MutedGray, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun WaterDetail(water: WaterStore, settings: AppSettings, onBack: () -> Unit) {
    DetailScreen("Water", onBack) {
        GlassesCard(water, settings)
        WaterReportSection(water, settings)
    }
}

@Composable
private fun CaloriesDetail(food: FoodStore, settings: AppSettings, onBack: () -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    DetailScreen("Calories", onBack) {
        CalorieCard(
            calories = food.caloriesToday,
            goal = settings.calorieGoal,
            meals = food.mealsToday,
            onAddClick = { showDialog = true },
            onDelete = { food.delete(it) }
        )
        CalorieReportSection(food, settings)
    }
    if (showDialog) {
        AddMealDialog(
            onDismiss = { showDialog = false },
            onSave = { name, kcal ->
                food.add(name, kcal)
                showDialog = false
            }
        )
    }
}

@Composable
private fun WorkoutDetail(workouts: WorkoutStore, settings: AppSettings, onBack: () -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    val startWalk = rememberWalkStarter(settings.walkBackground)
    DetailScreen("Workout", onBack) {
        WorkoutCard(
            onStart = startWalk,
            minutes = workouts.minutesToday,
            goalMinutes = settings.workoutGoalMin,
            workouts = workouts.workoutsToday,
            onAddClick = { showDialog = true },
            onDelete = { workouts.delete(it) }
        )
        WorkoutReportSection(workouts, settings)
    }
    if (showDialog) {
        AddWorkoutDialog(
            onDismiss = { showDialog = false },
            onSave = { type, min, kcal ->
                workouts.add(type, min, kcal)
                showDialog = false
            }
        )
    }
}

@Composable
private fun SleepDetail(sleep: SleepStore, settings: AppSettings, onBack: () -> Unit) {
    DetailScreen("Sleep", onBack) {
        SleepSection(store = sleep)
        SleepReportSection(sleep, settings)
        MonitorSection()
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardColor, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(title, color = MutedGray, fontSize = 14.sp)
        content()
    }
}

@Composable
private fun StepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label, color = Color(0xFFD0D3D9), fontSize = 15.sp,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(
            onClick = onMinus,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(40.dp)
        ) { Text("-") }
        Text(
            value, color = Color.White, fontSize = 15.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.width(96.dp)
        )
        OutlinedButton(
            onClick = onPlus,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(40.dp)
        ) { Text("+") }
    }
}

@Composable
private fun SettingsDetail(
    settings: AppSettings,
    onOpenTargets: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenDeveloper: () -> Unit,
    onOpenReset: () -> Unit,
    onBack: () -> Unit
) {
    DetailScreen("Settings", onBack) {
        SettingsLink("Set Targets", onOpenTargets)
        SettingsLink("Appearance", onOpenAppearance)
        SettingsLink("Reset Schedule", onOpenReset)
        SettingsLink("Developer Details", onOpenDeveloper)
        Text(
            "$APP_NAME version ${BuildConfigVersion.NAME}",
            color = MutedGray, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// Settings > Set Targets: daily targets, cup size, prayer card and walk tracking options
@Composable
private fun SetTargetsDetail(settings: AppSettings, onBack: () -> Unit) {
    DetailScreen("Set Targets", onBack) {
        SettingsCard("Daily targets") {
            StepperRow(
                "Steps", "%,d".format(settings.stepGoal),
                { settings.changeStepGoal(-500) }, { settings.changeStepGoal(500) }
            )
            StepperRow(
                "Water", formatWater(settings.waterGoalMl),
                { settings.changeWaterGoal(-100) }, { settings.changeWaterGoal(100) }
            )
            StepperRow(
                "Calories", "${settings.calorieGoal} kcal",
                { settings.changeCalorieGoal(-100) }, { settings.changeCalorieGoal(100) }
            )
            StepperRow(
                "Workout", "${settings.workoutGoalMin} min",
                { settings.changeWorkoutGoal(-5) }, { settings.changeWorkoutGoal(5) }
            )
            StepperRow(
                "Sleep", "${settings.sleepGoalMin / 60}h ${settings.sleepGoalMin % 60}m",
                { settings.changeSleepGoal(-15) }, { settings.changeSleepGoal(15) }
            )
            Text(
                "Per day (sleep per night). Ratings and reports use these targets.",
                color = MutedGray, fontSize = 12.sp
            )
        }
        SettingsCard("Water cup") {
            StepperRow(
                "Cup (glass) size", "${settings.cupMl} ml",
                { settings.changeCup(-50) }, { settings.changeCup(50) }
            )
            Text(
                "A new cup size applies to glasses you add from now on. " +
                        "Earlier glasses keep the size they were logged with.",
                color = MutedGray, fontSize = 12.sp
            )
        }
        SettingsCard("Walk / run tracking") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Keep recording with screen off", color = Color(0xFFD0D3D9), fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = settings.walkBackground,
                    onCheckedChange = { settings.enableWalkBackground(it) }
                )
            }
            Text(
                "On: a low-power background service keeps recording your route when the screen is off. " +
                        "Off (saves battery): recording runs only while the app is open on screen.",
                color = MutedGray, fontSize = 12.sp
            )
        }
        SettingsCard("Prayer card") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Enable Prayer card", color = Color(0xFFD0D3D9), fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = settings.prayerEnabled,
                    onCheckedChange = { settings.enablePrayerCard(it) }
                )
            }
            Text(
                "Shows the five daily prayers on the dashboard with a tick beside each one. " +
                        "Your saved prayer history is kept when the card is turned off.",
                color = MutedGray, fontSize = 12.sp
            )
        }
    }
}

// Settings > Appearance: Dark / Light mode and the background photo
@Composable
private fun AppearanceDetail(settings: AppSettings, onBack: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) settings.setBackground(uri)
    }
    DetailScreen("Appearance", onBack) {
        SettingsCard("Theme") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = !settings.lightMode, onClick = { settings.chooseLightMode(false) },
                    label = { Text("Dark") }
                )
                FilterChip(
                    selected = settings.lightMode, onClick = { settings.chooseLightMode(true) },
                    label = { Text("Light") }
                )
            }
        }
        SettingsCard("Background photo") {
            Text(
                "Choose a photo from your gallery to use as the dashboard background.",
                color = MutedGray, fontSize = 12.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { picker.launch("image/*") },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E5A0), contentColor = Color.Black
                    )
                ) { Text("Choose photo") }
                OutlinedButton(
                    onClick = { settings.clearBackground() },
                    enabled = settings.bgFile.exists() || settings.bgVersion > 0
                ) { Text("Use default") }
            }
        }
    }
}

@Composable
private fun SettingsLink(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardColor)
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("›", color = MutedGray, fontSize = 22.sp)
    }
}

@Composable
private fun DeveloperDetail(light: Boolean, onBack: () -> Unit) {
    DetailScreen("Developer Details", onBack) {
        // Put your picture at app/src/main/res/drawable/developer_photo.jpg (or .png)
        val ctx = LocalContext.current
        val photoId = remember {
            ctx.resources.getIdentifier("developer_photo", "drawable", ctx.packageName)
        }
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (photoId != 0) {
                Image(
                    painter = painterResource(id = photoId),
                    contentDescription = "Developer photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(140.dp).clip(CircleShape).lightFilter(light)
                )
            } else {
                Box(
                    modifier = Modifier.size(96.dp).clip(CircleShape).background(Color(0xFF00E5A0)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("AN", color = Color.Black, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(
            DEV_NAME, color = Color.White, fontSize = 22.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        InfoCard(
            "About me",
            listOf(
                "Name" to DEV_NAME,
                "Departments" to DEV_DEPARTMENTS,
                "University" to DEV_UNIVERSITY,
                "City" to DEV_CITY,
                "Email" to DEV_EMAIL
            )
        )
        InfoCard(
            "About the app",
            listOf(
                "App" to APP_NAME,
                "Built with" to "Kotlin and Jetpack Compose",
                "Your data" to "Stored only on this phone"
            )
        )
    }
}

// ---------- Rearrangeable dashboard cards ----------

val DASH_DEFAULT = listOf(
    "steps", "water", "calories", "workout", "sleep", "nap", "total", "monitor",
    "weight", "height", "vaccine", "medical", "mental", "eye", "prayer"
)
private val DASH_HALF = setOf(
    "water", "calories", "workout", "sleep", "nap", "total", "weight", "height", "vaccine", "medical"
)

// Keeps the saved order, drops unknown cards and adds any new card at the end
private fun mergeOrder(saved: List<String>): List<String> =
    saved.filter { it in DASH_DEFAULT }.distinct().let { k -> k + DASH_DEFAULT.filter { it !in k } }

// Press any card for 1 second to start edit mode, then drag cards to new places.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DashboardCards(
    editMode: Boolean,
    scroll: ScrollState,
    order: List<String>,
    onOrder: (List<String>) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onEnterEdit: () -> Unit,
    prayerOn: Boolean,
    card: @Composable (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val screenH = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val slop = LocalViewConfiguration.current.touchSlop
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    val visible = order.filter { it != "prayer" || prayerOn }
    val isDefault = visible == DASH_DEFAULT.filter { it != "prayer" || prayerOn }

    // Scrolls the page while a card is dragged near the top or bottom edge
    LaunchedEffect(dragKey) {
        while (dragKey != null) {
            val y = pointer.y
            val dy = if (y < 220f) -22f else if (y > screenH - 220f) 22f else 0f
            if (dy != 0f) scroll.scrollBy(dy)
            kotlinx.coroutines.delay(16)
        }
    }

    if (editMode) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Drag cards to rearrange", color = MutedGray, fontSize = 13.sp)
            TextButton(onClick = onReset) { Text("Reset order", color = Color(0xFF00E5A0)) }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val half = (maxWidth - 12.dp) / 2
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            maxItemsInEachRow = 2
        ) {
            visible.forEach { key ->
                if (isDefault && (key == "steps" || key == "weight")) {
                    Box(Modifier.fillMaxWidth()) {
                        SectionTitle(if (key == "steps") "Daily Activity" else "Health Track")
                    }
                }
                androidx.compose.runtime.key(key) {
                    val isDrag = dragKey == key
                    Box(
                        modifier = (if (key in DASH_HALF) Modifier.width(half) else Modifier.fillMaxWidth())
                            .onGloballyPositioned { bounds[key] = it.boundsInRoot() }
                            .zIndex(if (isDrag) 1f else 0f)
                            .graphicsLayer {
                                if (isDrag) {
                                    val b = bounds[key]
                                    translationX = pointer.x - grab.x - (b?.left ?: 0f)
                                    translationY = pointer.y - grab.y - (b?.top ?: 0f)
                                    scaleX = 1.03f; scaleY = 1.03f
                                }
                                alpha = if (editMode && !isDrag) 0.85f else 1f
                            }
                            .then(
                                if (editMode) Modifier.border(
                                    2.dp, Color(0xFF00E5A0).copy(alpha = if (isDrag) 1f else 0.5f),
                                    RoundedCornerShape(22.dp)
                                ) else Modifier
                            )
                            // Press for 1 second (without moving) to enter edit mode
                            .pointerInput(editMode) {
                                if (editMode) return@pointerInput
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    val result = withTimeoutOrNull(1000L) {
                                        while (true) {
                                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!ch.pressed) break
                                            if ((ch.position - down.position).getDistance() > slop) break
                                        }
                                        true
                                    }
                                    if (result == null) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onEnterEdit()
                                        // Swallow the rest of this touch so the card does not open on release
                                        while (true) {
                                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                                            ev.changes.forEach { it.consume() }
                                            if (ev.changes.none { it.pressed }) break
                                        }
                                    }
                                }
                            }
                    ) {
                        card(key)
                        if (editMode) {
                            // Covers the card: taps do nothing, a drag moves the card
                            Box(
                                modifier = Modifier.matchParentSize().pointerInput(key) {
                                    detectDragGestures(
                                        onDragStart = { off ->
                                            dragKey = key
                                            grab = off
                                            pointer = (bounds[key]?.topLeft ?: Offset.Zero) + off
                                        },
                                        onDrag = { change, delta ->
                                            change.consume()
                                            pointer += delta
                                            val over = visible.firstOrNull { it != key && bounds[it]?.contains(pointer) == true }
                                            if (over != null) {
                                                val list = order.toMutableList()
                                                val from = list.indexOf(key)
                                                val to = list.indexOf(over)
                                                if (from >= 0 && to >= 0) {
                                                    list.removeAt(from); list.add(to, key)
                                                    onOrder(list)
                                                }
                                            }
                                        },
                                        onDragEnd = { dragKey = null; onSave() },
                                        onDragCancel = { dragKey = null; onSave() }
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// Shown at the bottom of Settings so you can see which build is installed
object BuildConfigVersion { const val NAME = "5.0" }
