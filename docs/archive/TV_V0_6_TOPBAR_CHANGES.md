# Dream Cast TV v0.6 — top navigation adjustment

For the `develop` branch only.

- Home toolbar order: Search, Library, Calendar, Notifications, Settings (for TV/wide layouts).
- Removed the release-grid layout button from the home toolbar. The existing grid layout code and stored preference remain intact.
- Removed the GPLv3/GitHub support banner from the home screen, including its hide/open handlers; original LICENSE and GPLv3 mention in Settings remain available.
- Playback, persistence, release retrieval, and navigation routes unchanged.

Build via existing GitHub Actions workflow on `develop`. Not compiled or tested on an Android TV in this environment.
