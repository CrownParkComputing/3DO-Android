# GitHub Actions CI/CD Setup

This document explains how CI/CD is configured for the 4DO Android project.

## Workflow File

- Workflow: `.github/workflows/android-build.yml`
- Name: `Android CI/CD`

## Triggers

The workflow runs on:

- Push to `main` or `master`
- Pull requests targeting `main` or `master`
- Push of version tags matching `v*` (for example `v2.0.1`)
- Manual run from GitHub Actions (`workflow_dispatch`)

## Jobs and Behavior

### 1) CI job (`ci`)

Runs on every branch push/PR/tag/manual trigger and does:

1. Set up JDK 17
2. Set up Gradle using the project wrapper
3. Run unit tests (`./gradlew --no-daemon test`)
4. Run lint (`./gradlew --no-daemon lint`)
5. Build debug APK (`./gradlew --no-daemon assembleDebug`)
6. Upload debug APK artifact

If tests or lint fail, reports are uploaded as artifacts.

### 2) Release job (`release`)

Runs only when the ref is a tag starting with `v`.

1. Builds signed release APK (`./gradlew --no-daemon assembleRelease`)
2. Uploads release APK artifact
3. Publishes/updates GitHub release for the same tag
4. Attaches the release APK to that GitHub release

## Required Repository Secrets

Release signing uses these repository secrets:

- `KEYSTORE_PASSWORD`
- `KEY_PASSWORD`

Add them in GitHub:

1. Repository → **Settings**
2. **Secrets and variables** → **Actions**
3. **New repository secret**

> Note: `app/build.gradle` now reads these from environment variables with fallback defaults (`android`) for local development.

## Release Process

To create a production release via CI/CD:

1. Update app version in `app/build.gradle` (versionName/versionCode)
2. Commit and push changes
3. Create and push a tag, for example:

```bash
git tag v2.0.1
git push origin v2.0.1
```

This triggers the release job and publishes the signed APK as a GitHub Release asset.

## Optional: Generate New Signing Keys

Linux/macOS:

```bash
./scripts/create_signing_keys.sh
```

Windows:

```cmd
scripts\create_signing_keys.bat
```

After generating new keys, update secrets to match the new passwords.

## Security Recommendations

- Avoid default passwords (`android`) for production use
- Keep signing credentials in GitHub secrets
- Restrict who can create release tags

## Troubleshooting

### Release job didn’t run

- Confirm the ref is a tag like `v1.2.3`
- Confirm the tag was pushed to origin (`git push origin <tag>`)

### Signing failed

- Verify `KEYSTORE_PASSWORD` and `KEY_PASSWORD` are set and correct
- Confirm keystore alias is `4do-key`
- Confirm `4do-release-key.keystore` exists at repository root

### Build/test/lint failed

- Download the workflow artifacts (debug APK/report artifacts)
- Inspect test and lint reports for detailed failure output