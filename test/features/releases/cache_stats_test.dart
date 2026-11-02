import 'dart:convert';
import 'package:dream_cast/core/cache/cache_repository.dart';
import 'package:dream_cast/core/cache/cache_retention.dart';
import 'package:dream_cast/core/database/app_database.dart';
import 'package:drift/drift.dart' show Value;
import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test(
    'SQL cache stats preserve UTF-8 bytes and retention without reading payloads',
    () async {
      final db = AppDatabase(NativeDatabase.memory());
      addTearDown(db.close);
      final cache = CacheRepository(
        db,
        retention: CacheRetentionOption.forever,
      );
      final empty = await cache.stats();
      expect(empty.entriesCount, 0);
      expect(empty.approximateBytes, 0);
      expect(empty.maxRetention, isNull);
      final now = DateTime(2026, 1, 1);
      await db.putCacheEntry(
        CacheEntriesCompanion(
          key: const Value('ключ'),
          valueJson: const Value('{"текст":"🎬"}'),
          etag: const Value('версия'),
          updatedAt: Value(now),
          expiresAt: Value(now.add(const Duration(hours: 8))),
        ),
      );
      await db.putCacheEntry(
        CacheEntriesCompanion(
          key: const Value('forever'),
          valueJson: const Value('{}'),
          updatedAt: Value(now),
        ),
      );
      final stats = await cache.stats();
      expect(stats.entriesCount, 2);
      expect(
        stats.approximateBytes,
        utf8.encode('ключ{"текст":"🎬"}версияforever{}').length,
      );
      expect(stats.maxRetention, const Duration(hours: 8));
    },
  );
}
