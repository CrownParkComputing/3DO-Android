# GitHub Actions CI/CD Setup

This document explains how to configure GitHub Actions for the 4DO Android project.

## Required Secrets

To enable the CI/CD pipeline, you need to add the following secrets to your GitHub repository:

### 1. KEYSTORE_PASSWORD
- **Purpose**: Password for the keystore file (`4do-release-key.keystore`)
- **Value**: `android` (default password used in the project)
- **How to add**: 
  1. Go to your repository on GitHub
  2. Click on "Settings" tab
  3. In the left sidebar, click "Secrets and variables" → "Actions"
  4. Click "New repository secret"
  5. Name: `KEYSTORE_PASSWORD`
  6. Value: `android`
  7. Click "Add secret"

### 2. KEY_PASSWORD
- **Purpose**: Password for the signing key within the keystore
- **Value**: `android` (default password used in the project)
- **How to add**: Same steps as above, but with:
  - Name: `KEY_PASSWORD`
  - Value: `android`

### Signing Key Configuration
The project already includes a signing key:
- **File**: `4do-release-key.keystore` (located in project root)
- **Key Alias**: `4do-key`
- **Store Password**: `android`
- **Key Password**: `android`
- **Configuration**: Already set up in `app/build.gradle`

## Workflow Features

### Build Triggers
The workflow runs automatically on:
- Push to `main` or `master` branches
- Pull requests to `main` or `master` branches
- New releases (tags)

### Version Management
- **Automatic Version Bumping**: After successful builds on main/master, the version is automatically incremented
- **Manual Version Bumping**: Use the provided scripts to manually bump versions
- **Version Scripts**: 
  - `scripts/bump_version.sh` (Linux/macOS)
  - `scripts/bump_version.bat` (Windows)
- **Usage**: `./scripts/bump_version.sh [major|minor|patch]` (default: patch)

### Build Process
1. **Setup**: Installs JDK 17 and Gradle 8.2.2
2. **Caching**: Caches Gradle and Android dependencies for faster builds
3. **Build**: Creates both debug and release APKs
4. **Testing**: Runs unit tests and lint checks
5. **Signing**: Signs release APKs for production releases
6. **Deployment**: Uploads APKs to GitHub releases for tagged releases

### Artifacts
- Debug APK: Available as workflow artifact
- Release APK: Available as workflow artifact and GitHub release asset
- **Automatic Releases**: Successful builds on main/master are automatically published as GitHub releases

## Security Notes

- The keystore file (`4do-release-key.keystore`) is already committed to the repository
- The passwords are set to the default Android values (`android`)
- For production use, consider:
  - Using a more secure keystore password
  - Storing the keystore file as a GitHub secret instead of in the repository
  - Using different passwords for keystore and key

## Manual Build

You can also trigger a manual build by:
1. Going to the "Actions" tab in your repository
2. Selecting "Android CI/CD" workflow
3. Clicking "Run workflow"

## Troubleshooting

### Build Failures
- Check that all required secrets are properly configured
- Verify the keystore file exists and is not corrupted
- Ensure the passwords match the keystore configuration

### Signing Issues
- Verify `KEYSTORE_PASSWORD` and `KEY_PASSWORD` secrets are correct
- Check that the keystore alias (`4do-key`) matches the workflow configuration
- Ensure the keystore file exists in the repository root
- Verify the keystore file has the correct permissions
- Check that the passwords match the keystore configuration in `app/build.gradle`

### Gradle Issues
- The workflow uses Gradle 8.2.2 to match the project configuration
- Caching helps speed up dependency resolution
- Clean builds are performed to ensure consistency