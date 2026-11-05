# Yggdrasil Android Scheduler Host 0.1

A native Android host for local-first scheduled checks. This is a separate
Android implementation of the scheduler's mobile execution boundary; it does
not embed or run the Node.js desktop service.

## Current capabilities

- Stores task definitions and recent execution records locally in SQLite.
- Uses Android WorkManager for persistent background polling with network
  connectivity constraints.
- Creates daily GitHub Actions monitors using the phone's current IANA timezone.
- Establishes a baseline on the first successful check; historic failures are
  not replayed as new alerts.
- Detects newly completed workflow failures and posts Android notifications.
- Supports pause, resume, delete, manual check and recent activity display.
- Retains at most 500 execution records.

## Android timing limitation

WorkManager periodic work has a minimum interval of 15 minutes. Android may
delay execution further because of Doze, battery optimization, network
availability, and OEM background restrictions. A daily time is therefore a
"check after this local time, when Android next runs the worker" target, not an
exact alarm or guaranteed minute. This app intentionally does not request
exact-alarm privileges or run a permanent foreground service.

The first version supports daily schedules only. Weekly schedules, richer
task types, encrypted GitHub credentials for private repositories, backup/
restore, and Builder-to-Builder communication remain future work.

## Build

Requires JDK 17, Android SDK platform 35, and Gradle 8.9:

```sh
gradle --no-daemon :app:assembleDebug
```

The CI workflow compiles the debug APK on every relevant branch change.

## Privacy and network behavior

Task metadata and execution history stay in the app's private SQLite database.
The monitor calls the public GitHub REST API over HTTPS and currently supports
public repositories without a token. No GitHub credential is stored or sent.
Private repositories and higher-rate authenticated monitoring are not yet
supported.

## Installation status

Source and CI build only. No APK has been installed on a device. The current
repository branch is isolated from main.
