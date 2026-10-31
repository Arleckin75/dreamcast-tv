# TV remote Back navigation (development)

- Short press: pop active route, honoring player PopScope; if no route to pop, go to /home; on /home do nothing.
- Long press (1500 ms): Android Activity finish().
- Search is pushed; backing out from a release restores the same SearchScreen state (query, scroll position where maintained by Flutter).
- Top-level sections opened via go(...) return to /home.

## Testing checklist
- Home short Back does not exit.
- Search -> release -> Back restores search query/results; second Back returns home.
- Player -> Back saves playback progress and returns to episode/release.
- Settings -> Information -> Back returns settings; Back returns home.
- Long Back exits from the home screen and from a nested screen.
- Android system navigation gesture/back on different TV hardware may bypass key events; verify on target TV.

Note: native back interception is Android-specific, Flutter compilation and hardware tests are pending.
