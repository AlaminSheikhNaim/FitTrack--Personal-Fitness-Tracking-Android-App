# FitTrack

**A native Android health companion that brings movement, sleep, nutrition, mental wellbeing, and medical records into one dashboard, so you can see how your daily habits shape the way you feel.**

![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)
![Language](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
![Min SDK](https://img.shields.io/badge/minSdk-26-blue)
![Version](https://img.shields.io/badge/version-5.0-green)

---

## Why FitTrack?

Most health apps track one thing well and leave you to connect the dots yourself. FitTrack puts everything in one place and then compares it: how you slept, what you ate and drank, how much you moved, and how you felt at the end of the day. It also turns the sensors already in your phone into a sleep-quality monitor, so no wearable is needed.

## Features

### Daily tracking
- **Steps:** live step counting with a daily goal.
- **Walk and run recording:** GPS tracking with distance, speed, maximum speed, route points, and outdoor temperature. A low-power background service keeps recording with the screen off.
- **Water:** log intake and get a hydration rating against your goal.
- **Food and calories:** log meals and see a score based on how close you are to your calorie goal.
- **Workouts:** log workout minutes against a daily target.
- **Activity rating:** steps, water, food, workouts, and sleep are combined into one overall score, and your weakest area is highlighted.

### Sleep
- **Sleep and nap tracking:** record sleep manually and review it against your sleep goal.
- **Sleep Quality Monitor:** put the phone beside your bed and it samples your room through the night using the **light sensor**, **proximity sensor**, **accelerometer**, and **microphone**. It detects restless movement, sudden sounds, brightness changes, and phone use.
- **Fair scoring:** instead of fixed limits, the scorer learns what a normal dark night looks like for *your* room, so a pitch-black room and one with a little street light are both judged fairly.
- **Clear reports:** each night gets a 0 to 100 score (Excellent, Good, Fair, Poor), a list of disturbances, and practical tips.
- **Passive estimate:** if you do not run the monitor, FitTrack can estimate sleep from the longest phone-free rest of the night (requires Usage Access).
- **Morning notification:** a daily prompt to review last night's sleep.

### Mental wellbeing
- **Daily mood check-in:** a notification at 8:30 pm asks how you feel. Pick a mood from 1 to 5 and the reasons behind it.
- **Mood analysis:** the mental health report compares your mood with your sleep, sleep quality, food, water, workouts, steps, and prayers across daily, weekly, monthly, and yearly views, and offers comments and tips.
- **Eye health:** screen time per app since your reset time, so you can see where your day goes.
- **Prayer tracker:** tick the five daily prayers and Tahajjud.

### Health records
- **Weight, height, and BMI:** log measurements over time and see your BMI category.
- **Vaccination records:** keep a dated list of your vaccines.
- **Medical vault:** upload prescriptions, lab results, and scans as PDFs or images, with a title and report date. Edit, share, or delete them at any time.

### Reports and personalization
- **Charts:** daily, weekly, monthly, and yearly bar charts with colors that show how close you are to each goal.
- **Custom targets:** set your own goals for steps, water, calories, workouts, and sleep.
- **Reset schedule:** choose when each card starts again from zero (midnight by default, 5:00 am for workout, eye health, and prayer).
- **Appearance:** dark and light themes and a customizable dashboard background.

## Tech stack

| Area | Details |
| --- | --- |
| Language | Kotlin |
| UI | Jetpack Compose, Material 3, custom Canvas charts |
| Storage | On-device SQLite databases and SharedPreferences |
| Background work | Foreground services (location, microphone), AlarmManager, boot receivers |
| Sensors and system APIs | Step counter and activity recognition, GPS, light, proximity, accelerometer, microphone, UsageStatsManager |
| Dependencies | AndroidX only, no third-party libraries |
| Build | Gradle (Kotlin DSL), compileSdk 37, targetSdk 37, minSdk 26 |

## Getting started

### Requirements
- Android Studio (latest stable)
- An Android device or emulator running Android 8.0 (API 26) or newer
- A physical device is recommended, because step counting, GPS, and the sleep sensors do not work well on emulators

### Build and run

```bash
git clone https://github.com/AlaminSheikhNaim/FitTrack.git
cd FitTrack
./gradlew assembleDebug
```

Or open the project in Android Studio, let Gradle sync, and press **Run**.

## Permissions

FitTrack asks only for what each feature needs.

| Permission | Used for |
| --- | --- |
| `ACTIVITY_RECOGNITION` | Step counting |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE_LOCATION` | GPS walk and run recording |
| `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE` | Sound-level detection in the Sleep Quality Monitor |
| `FOREGROUND_SERVICE`, `WAKE_LOCK` | Keeping the sleep monitor and walk recording running |
| `POST_NOTIFICATIONS` | Morning sleep summary and evening mood check-in |
| `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED` | Rescheduling reminders after a restart |
| `PACKAGE_USAGE_STATS` | Eye health screen time and passive sleep estimate (enabled by you in Settings) |
| `INTERNET` | Outdoor temperature during walks |

## Privacy

Your logs, mood entries, sleep reports, and medical documents are stored on your device. Medical files are copied into the app's private storage and only leave the device when you choose to share them.

## Project structure

```
app/src/main/java/com/example/fittrack/
├── MainActivity.kt        App entry point and permission handling
├── Screens.kt             Dashboard and navigation
├── *Store.kt              SQLite storage (steps, water, food, workouts, sleep, health, mood, prayer, medical)
├── SleepMonitorService.kt Foreground service that samples the room overnight
├── SleepAnalyzer.kt       Turns raw readings into a sleep quality report
├── SleepScorer.kt         Scoring for movement, noise, and light
├── MentalUi.kt            Mood check-in and mood analysis reports
├── Reports.kt             Daily, weekly, monthly, and yearly charts
├── WalkTracker.kt         GPS walk and run recording
└── ResetSchedule.kt       Per-card reset times
```

## Tuning the sleep score

If the sleep score feels too harsh or too easy after a few nights, adjust the thresholds in `SleepScorer.kt` (movement, sound events, and light ratio) and `SleepAnalyzer.kt`. They are starting values meant to be tuned to your room.

## Contributing

Bug reports, ideas, and feature requests are welcome: please open an issue. If you would like to contribute code, open an issue first to discuss the change, then fork the repository, create a feature branch, and send a pull request.

## License

No license has been chosen for this project yet, so all rights are reserved by the author. If you would like to reuse or redistribute any part of it, please open an issue to ask first.

## Author

Created by [@AlaminSheikhNaim](https://github.com/AlaminSheikhNaim).
