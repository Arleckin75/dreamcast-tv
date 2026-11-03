import 'dart:convert';

import 'package:flutter/foundation.dart';

/// Low-volume timings; never include URLs, search text or credentials.
final class PerformanceTrace {
  PerformanceTrace(this.name) {
    if (enabled) _clock.start();
  }

  static const enabled = bool.fromEnvironment(
    'DREAM_CAST_PERFORMANCE',
    defaultValue: kDebugMode,
  );

  final String name;
  final Stopwatch _clock = Stopwatch();
  bool _finished = false;

  static Future<T> measure<T>(String name, Future<T> Function() action) async {
    final trace = PerformanceTrace(name);
    try {
      final result = await action();
      trace.finish();
      return result;
    } catch (_) {
      trace.finish(outcome: 'error');
      rethrow;
    }
  }

  void finish({String outcome = 'ok', String? source, int? items}) {
    if (!enabled || _finished) return;
    _finished = true;
    _clock.stop();
    final record = {
      'event': name,
      'elapsedUs': _clock.elapsedMicroseconds,
      'outcome': outcome,
      'source': ?source,
      'items': ?items,
    };
    // Use print so records also work before application logging is configured.
    // ignore: avoid_print
    print('DCTV_PERF ${jsonEncode(record)}');
  }
}
