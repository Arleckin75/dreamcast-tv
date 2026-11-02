# Dream Cast TV — v0.6 top navigation prototype

- On wide screens (>=720 logical pixels), remove sidebar and reclaim entire width.
- Move Library, Calendar and Settings to the Home AppBar, ahead of existing notifications, grid options and search.
- Retain the small-screen bottom navigation.
- Add a visible Back-to-catalog button on Library, Calendar and Settings TV screens.
- Preserve player and viewing history code without changes.

## Validation
The files have been modified and packaged. Flutter SDK is not available in this environment, so compilation and D-pad behavior must be validated using the GitHub Actions build on branch `develop` and a real TV.

## Test
1. Open Home, navigate UP to the toolbar; arrow left/right among the six icons; OK opens selected action.
2. Library / Calendar / Settings: press top-left back button to return to Home.
3. Verify Grid, Search, Notifications, cards, playback, and progress still work.

Do not merge into main until confirmed.
