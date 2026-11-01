# Bag Timer

Interval workout timer for Android phones and Fire TV. No ads, no account, no internet permission.

## Features

- Built-in workouts: Bag Ladder (6 rounds, 39:00), 4-3-2 Ladder, Tabata, EMOM 10 min, AMRAP 12 min.
- Editor: rounds, get-ready time, and any list of Work, Rest and Bag intervals.
- Distinct beeps: Work = two high beeps, Rest = one low tone, Bag = three fast beeps, 3-2-1 pips before each change.
- Beeps lower other audio (music, a match stream) for about 1 s, then let it return.
- Keeps running with the screen off: foreground notification with Pause, Skip and Stop.
- Picture-in-picture ("Float"): the clock floats in a corner over other apps, where the device supports it.
- Fire TV remote: D-pad for every control, Play/Pause pauses, Fast-forward skips, Rewind goes back.

## Get the APK

Every push to `main` builds a new APK with GitHub Actions. The newest APK is always at:

```
https://github.com/5ilidons/workout-timer/releases/latest/download/bag-timer.apk
```

Replace `5ilidons/workout-timer` with this repository's name.

## Install on an Android phone

1. Open the link above in Chrome on the phone.
2. Open the downloaded file.
3. If Android asks, allow Chrome to install unknown apps.
4. Tap **Install**.
5. Open Bag Timer and allow notifications.

## Install on a Fire TV Stick

1. On the Fire TV, open the Amazon Appstore.
2. Search for **Downloader** (by AFTVnews) and install it.
3. Go to **Settings > My Fire TV > Developer options > Install unknown apps**.
4. Set **Downloader** to **On**.
5. Open Downloader.
6. Enter the APK link above and select **Go**.
7. Select **Install** when the download finishes.
8. Select **Delete** to remove the APK file and free space.

If **Developer options** is not visible, go to **Settings > My Fire TV > About**. Then select the device name 7 times.

The Fire TV Stick 4K Select and newer sticks run Vega OS. Vega OS cannot install this app.

## Update

Install the new APK the same way. It installs over the old version and keeps your workouts.

## Use picture-in-picture on the TV

1. Start a workout.
2. Select **Float**, or press the Home button.
3. Open your rugby stream.

The clock stays in the corner and keeps beeping. To return to the full clock, open Bag Timer again.

## If the timer stops with the screen off (phone)

Some phone brands stop background apps to save battery.

1. Open **Settings > Apps > Bag Timer > Battery**.
2. Set it to **Unrestricted**.

## Build locally

Requires JDK 17 and the Android SDK.

```
./gradlew assembleRelease
```

Engine tests run without Android:

```
S=app/src/main/java/app/bagtimer/engine
kotlinc $S/Timeline.kt $S/Cues.kt app/src/test/java/app/bagtimer/engine/EngineTest.kt -include-runtime -d t.jar
java -cp t.jar app.bagtimer.engine.EngineTestKt
```

## Signing

The signing key is in `signing/`, so every CI build installs over the last one. This is fine for a personal app. Do not reuse this key for an app you publish.
