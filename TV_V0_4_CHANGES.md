# Dream Cast TV v0.4 — onboarding / remote fixes

Changed `lib/features/onboarding/presentation/onboarding_screen.dart`:
- On entering the profile screen, request keyboard focus on the name input.
- OK/Enter/Select on the name input requests Android's soft keyboard.
- Down from the name input moves focus to the Save Profile button.
- Added busy-state guard and error feedback when saving a local profile.
- On entering the final permission screen, focus the actual Finish button.
- Removed the broad parent-level OK handler to avoid double dispatch.

No changes were made to the first three onboarding pages or Dream Cast site parsing.

## Build

Push the project to the existing GitHub repository and let the included GitHub Actions workflow build `app-debug.apk`.
The patch is source-reviewed, **not yet compiled or tested on Android TV**.

## Remote test

On step 4: name field focused -> OK opens keyboard -> type `TV` -> keyboard Done (or Back) -> Down -> OK on Save Profile.
On step 5: Finish focused -> OK -> Home/catalog.
If it fails, capture a photo and the Android/Flutter runtime log, if available.
