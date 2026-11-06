# Finance Flux

Finance Flux is the Android capture app in the Finance project. It accepts normalized candidates from separately installed companion connectors, applies a user profile, and writes immutable schema 2 events and read-only Markdown cards into a user-selected Obsidian vault folder. Finance desktop remains the only writer of the Excel workbook.

## Current beta scope

- Android app with a small Kotlin Multiplatform core for protocol models and validation.
- SAF access to a user-selected `Finance Sync` folder. Flux does not request broad storage access.
- Per-connector profiles tied to active account IDs from `config/lists.json`.
- Before polling a connector, Flux reads `config/protocol.json` and requires operation schema 2 plus `automatic-operation-review.v1`; missing or incompatible protocol data blocks the sync and is explained in the app.
- Explicit bound AIDL IPC, with package and signing-certificate trust recorded by the user.
- Queue publication through `incoming/<id>.pending` followed by a provider rename to `.json` after the stream is closed and synced.
- Markdown cards use the Finance Capture frontmatter contract and remain read-only summaries.
- Public GitHub release notes can be checked without a token. APKs are not uploaded to public releases and are installed manually.

The selected SAF provider must support renaming documents for an event to become visible as `.json`. When a rename is refused, Flux leaves the `.pending` file and reports that the operation is not yet queued.

The shared module contains no Android APIs. Future iOS work can add a SwiftUI app and an iOS storage adapter without moving Android code into the shared core. iOS is not included in this beta.

`samples/test-connector` is a synthetic-only IPC fixture. It emits one clearly labeled RUB expense and contains no bank or personal data. Build it with `./gradlew.bat :samples:test-connector:assembleDebug`; only use it with a disposable test vault and test account.

## Build

Use JDK 17 and the Gradle wrapper:

```powershell
# Windows PowerShell
.\gradlew.bat :shared:testAndroidHostTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :samples:test-connector:assembleDebug
```

On macOS or Linux, use `./gradlew` with the same task names.

On Windows, keep the checkout, Android SDK, and Gradle user home under paths containing only ASCII characters. The Android AIDL tool writes absolute paths into generated files and depfiles; non-ASCII path segments can break a build.

The debug APK is a local beta artifact. Do not publish it as an asset of this public source repository. Before a production release, complete the security milestone for event signatures, trusted producer configuration on desktop, key rotation, and tamper/replay tests. The beta must use a synthetic workbook and an isolated sync folder.

## Contracts

`contracts/` and `examples/` contain generated copies of Finance desktop's schema 2 operation contract and a synthetic example. Update them from the canonical desktop source; do not hand-edit the copies.
