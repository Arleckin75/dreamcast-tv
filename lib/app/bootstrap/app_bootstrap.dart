import 'dart:async';

import 'package:dream_cast/core/database/app_database.dart';
import 'package:dream_cast/core/logging/app_logger.dart';
import 'package:dream_cast/core/logging/performance_trace.dart';
import 'package:dream_cast/features/notifications/data/episode_notification_service.dart';
import 'package:dream_cast/features/notifications/data/episode_notification_worker.dart';
import 'package:flutter/widgets.dart';
import 'package:shared_preferences/shared_preferences.dart';

final class AppBootstrapResult {
  const AppBootstrapResult({required this.preferences, required this.database});

  final SharedPreferences preferences;
  final AppDatabase database;
}

final class AppBootstrap {
  const AppBootstrap._();

  static Future<AppBootstrapResult> start() async {
    final trace = PerformanceTrace('startup.bootstrap');
    WidgetsFlutterBinding.ensureInitialized();
    configureAppLogging();

    final preferences = await SharedPreferences.getInstance();
    final database = AppDatabase();
    // Notification scheduling is not needed to render the catalogue.
    // syncFromPreferences initializes WorkManager itself when it runs below.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      unawaited(_startBackgroundServices(preferences));
    });

    trace.finish();
    return AppBootstrapResult(preferences: preferences, database: database);
  }

  static Future<void> _startBackgroundServices(
    SharedPreferences preferences,
  ) async {
    try {
      await EpisodeNotificationService.initialize().timeout(
        const Duration(seconds: 3),
      );
      await EpisodeNotificationScheduler.syncFromPreferences(
        preferences,
      ).timeout(const Duration(seconds: 3));
    } catch (error, stackTrace) {
      appLogger(
        'bootstrap',
      ).warning('Background services startup failed: $error', stackTrace);
    }
  }
}
