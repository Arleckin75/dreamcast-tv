# Dream Cast TV v0.5 — automatic local profile

- First-run wizard has three steps: welcome, theme, and accent.
- Pressing Continue on the accent step starts completion directly; the profile input and permissions confirmation screens are removed from onboarding.
- A default local profile named `TV` is created only when no active profile exists, using the existing `ActiveProfileController.save` / repository path.
- Existing active profiles and their history/bookmarks are retained.
- If profile creation or settings persistence fails, the wizard displays an error instead of marking onboarding complete.
- No video playback or catalog parsing logic changed.

## Verification
- Source checked for removed references and ZIP integrity.
- Flutter SDK is not installed here; compile with existing GitHub Actions workflow.
- Install the new build on the TV, choose Start, then Continue on Theme and Accent. After the third step the catalog should open.
