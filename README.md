# EVCtree — Android Video Editor

Repository: `EVCtree`  
Application label: `EVCtree`  
Current project version: `0.2.184` (`versionCode 384`)

## Project setup

This repository is a cleaned, independent working copy of the EVCtow application.

- Android application module: `app`
- Gradle root project: `EVCtree`
- Application ID / namespace: `com.evc.app` (kept unchanged to avoid unnecessary package and migration risk)
- Minimum Android SDK: 24
- Target / compile SDK: 35
- Java and Kotlin JVM target: 17
- Native ABI filter: `armeabi-v7a` only (32-bit ARM)
- Debug APK shrinking is disabled for the current stability/debugging stage.

## GitHub Actions

The workflow is at `.github/workflows/android.yml`.

It is configured to run for:
- pushes to **all branches** (`branches: ["**"]`)
- pull requests targeting all branches
- manual runs through `workflow_dispatch`

This avoids the common situation where MGit pushes to a branch other than `main` or `master` and Actions therefore does not start.

After creating the new GitHub repository:
1. Upload/push the contents of this ZIP as the repository's initial commit.
2. In MGit, confirm the remote URL points to the new `EVCtree` repository.
3. Push to any branch; the workflow should start from the Actions tab.
4. If the workflow does not appear, confirm `.github/workflows/android.yml` exists on the repository's default branch and that Actions are enabled in repository settings.

## Clean-up performed

- Removed the nested duplicate `EVC/` project.
- Removed embedded `.git` repositories/history so this can be initialized as a new, independent GitHub repository.
- Excluded generated build/cache folders and temporary build outputs.
- Kept the root Android application source, resources, Gradle configuration, and GitHub Actions workflow.
- Kept the existing application ID and Kotlin package names to reduce migration risk.

## Build

The GitHub Actions workflow runs `assembleDebug` and uploads the ARM32 debug APK as an artifact.

This ZIP has been structurally cleaned and configured. A successful Android build and on-device runtime behavior have **not** been verified here; the first Actions run is required to confirm dependency resolution and compilation.
