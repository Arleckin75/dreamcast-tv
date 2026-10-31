# Android TV: direct launch without setup screens

- Removed the `/onboarding` route and onboarding UI.
- On initial launch the splash screen checks for a local profile. If none exists, it creates `TV` without asking for a name.
- Existing local profiles and watch progress are not overwritten.
- Existing system theme, system accent and dynamic color defaults are retained; they can still be changed in Settings.
- The existing onboarding-completed preference is set for backward compatibility, but no longer controls routing.
- If database initialization fails, a visible error and remote-focusable Retry button are shown instead of silently entering an unusable home screen.
- `main`, `stable-v0.5` are not modified by this archive. Apply to `develop` only.
