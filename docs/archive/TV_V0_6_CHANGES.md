# Dream Cast TV v0.6 — sidebar remote navigation

Changes only `lib/app/widgets/adaptive_app_shell.dart`:

- D-pad Left on a control in the first content column moves focus to the current sidebar destination.
- Up/Down moves between focusable sidebar buttons using Flutter's directional focus traversal.
- OK/Select activates a sidebar destination via `InkWell`.
- D-pad Right restores the previous content focus when possible.
- Focused menu destination has a distinct primary-coloured highlight and border; selected destination retains an accent.
- Narrow-screen bottom navigation remains available.

## Verification on Google TV / Android TV

1. Build the `develop` branch (not `main`) in GitHub Actions.
2. On Home, focus the leftmost anime card, then press Left: focus should move to the sidebar.
3. Press Down/Up through all four destinations and OK to open Library/Calendar/Settings.
4. Press Right from the sidebar: focus should return to the content.
5. Check content card navigation, playback, Back, and watching history.

Note: Compilation and real-device behaviour must be validated in GitHub Actions and on the TV. If pressing Left while inside a horizontally scrolling control unexpectedly moves focus to the rail, report the affected screen so the boundary rule can be narrowed.
