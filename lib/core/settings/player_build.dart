import 'package:flutter/services.dart' show appFlavor;

/// Android's product flavor is the single source of truth for player features.
bool get softwarePlayerBuild => appFlavor == 'softwareAv1';
