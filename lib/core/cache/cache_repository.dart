import 'dart:convert';
import 'package:dream_cast/core/utils/json_record.dart';

import 'package:dream_cast/core/cache/cache_entry.dart';
import 'package:dream_cast/core/cache/cache_retention.dart';
import 'package:dream_cast/core/database/app_database.dart';
import 'package:drift/drift.dart';

final class CacheStats {
  const CacheStats({
    required this.entriesCount,
    required this.approximateBytes,
    required this.maxRetention,
  });

  final int entriesCount;
  final int approximateBytes;
  final Duration? maxRetention;
}

final class CacheRepository {
  const CacheRepository(
    this._database, {
    required CacheRetentionOption retention,
  }) : _retention = retention;

  final AppDatabase _database;
  final CacheRetentionOption _retention;

  Future<void> putJson(
    String key,
    Object value, {
    Duration? ttl,
    String? etag,
  }) {
    final now = DateTime.now();
    final expiresAt = _expiresAt(now, ttl);
    return _database.putCacheEntry(
      CacheEntriesCompanion(
        key: Value(key),
        valueJson: Value(jsonEncode(value)),
        updatedAt: Value(now),
        expiresAt: Value(expiresAt),
        etag: Value(etag),
      ),
    );
  }

  Future<CachedValue<Map<String, Object?>>?> getJsonMap(
    String key, {
    Duration? maxAge,
  }) async {
    final row = await _database.cacheEntry(key);
    if (row == null) return null;

    final decoded = readJsonRecord(row.valueJson, (value) => value);
    if (decoded == null) return null;

    final expiresAt = row.expiresAt;
    return CachedValue<Map<String, Object?>>(
      value: decoded.cast<String, Object?>(),
      updatedAt: row.updatedAt,
      isStale:
          (expiresAt != null && !DateTime.now().isBefore(expiresAt)) ||
          (maxAge != null &&
              !DateTime.now().isBefore(row.updatedAt.add(maxAge))),
    );
  }

  Future<int> clearExpired() => _database.clearExpiredCache(DateTime.now());

  Future<int> clearAll() => _database.clearAllCache();

  Future<int> applyCurrentRetentionToExistingEntries() {
    return _database.rewriteCacheExpiration(_retention.duration);
  }

  Future<CacheStats> stats() async {
    // Aggregate inside SQLite instead of loading every cached JSON document
    // into Dart memory just to display a byte count.
    final row = await _database
        .customSelect(
          '''
      SELECT COUNT(*) AS entry_count,
        COALESCE(SUM(length(CAST(key AS BLOB)) + length(CAST(value_json AS BLOB))
          + length(CAST(COALESCE(etag, '') AS BLOB))), 0) AS byte_count,
        MAX(CASE WHEN expires_at > updated_at
          THEN expires_at - updated_at END) AS retention_seconds
      FROM cache_entries
    ''',
          readsFrom: {_database.cacheEntries},
        )
        .getSingle();
    final seconds = row.readNullable<int>('retention_seconds');
    return CacheStats(
      entriesCount: row.read<int>('entry_count'),
      approximateBytes: row.read<int>('byte_count'),
      maxRetention: seconds == null ? null : Duration(seconds: seconds),
    );
  }

  DateTime? _expiresAt(DateTime now, Duration? ttl) {
    if (_retention == CacheRetentionOption.forever) return null;
    final duration = _retention.duration ?? ttl;
    return duration == null ? null : now.add(duration);
  }
}
