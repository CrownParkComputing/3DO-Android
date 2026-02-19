# 4DO Android App - Orientation & CI/CD Implementation Plan

## Current Issues Identified

1. **Orientation Problems:**
   - App starts in portrait mode and cannot be rotated
   - EmulatorActivity is locked to landscape only
   - Controller mapping screen should only be in portrait
   - Need device-specific orientation handling

2. **Missing CI/CD:**
   - No GitHub Actions workflow
   - Need automated build and signing
   - Need to incorporate existing signing key

## Implementation Plan

### Phase 1: Orientation Handling

1. **Create Orientation Utility Class**
   - Detect device type (phone vs tablet)
   - Determine optimal orientation based on device
   - Handle orientation changes gracefully

2. **Update AndroidManifest.xml**
   - Remove hardcoded screenOrientation attributes
   - Add configChanges for orientation handling
   - Set appropriate launch modes

3. **Update Activities**
   - MainActivity: Dynamic orientation based on device
   - EmulatorActivity: Support both orientations with proper handling
   - NewControllerMapperActivity: Force portrait only
   - Other activities: Review and update as needed

4. **Add Runtime Orientation Management**
   - Handle orientation changes in onResume/onPause
   - Preserve game state during orientation changes
   - Update renderer when orientation changes

### Phase 2: GitHub Actions CI/CD

1. **Create GitHub Actions Workflow**
   - Build Android project
   - Sign APK with existing keystore
   - Generate release artifacts
   - Upload to GitHub releases

2. **Security Setup**
   - Store signing key in GitHub Secrets
   - Configure keystore password and key password as secrets
   - Ensure secure handling of credentials

3. **Build Optimization**
   - Use Gradle wrapper for consistency
   - Cache dependencies for faster builds
   - Generate both debug and release builds

## Technical Details

### Orientation Detection Logic
- Phones: Start in portrait, allow rotation to landscape for emulator
- Tablets: Start in landscape for better emulator experience
- Controller mapping: Always portrait for better UI layout

### CI/CD Workflow Structure
- Trigger on push to main/master and releases
- Build both debug and release variants
- Sign release APK with stored credentials
- Upload artifacts to GitHub releases