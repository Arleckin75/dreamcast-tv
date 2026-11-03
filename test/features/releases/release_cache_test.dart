import 'dart:async';

import 'package:dio/dio.dart';
import 'package:dream_cast/app/bootstrap/dream_cast_app.dart';
import 'package:dream_cast/core/cache/cache_repository.dart';
import 'package:dream_cast/core/cache/cache_retention.dart';
import 'package:dream_cast/core/database/app_database.dart';
import 'package:dream_cast/features/releases/data/dream_cast_api.dart';
import 'package:dream_cast/features/releases/data/dream_cast_parser_service.dart';
import 'package:dream_cast/features/releases/data/release_repository.dart';
import 'package:dream_cast/features/releases/data/release_providers.dart';
import 'package:dream_cast/features/releases/presentation/release_list_providers.dart';
import 'package:drift/drift.dart' show Value;
import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  late AppDatabase database;
  late CacheRepository cache;
  late ReleaseRepository repository;
  late Dio dio;
  var requests = 0;
  var fail = false;
  const key = 'dreamcast:releases:ongoing:1:16';
  setUp(() {
    database = AppDatabase(NativeDatabase.memory());
    cache = CacheRepository(database, retention: CacheRetentionOption.forever);
    requests = 0;
    fail = false;
    dio = Dio(BaseOptions(baseUrl: 'https://example.com'));
    dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) {
          requests++;
          if (fail) {
            handler.reject(
              DioException(
                requestOptions: options,
                type: DioExceptionType.badResponse,
                response: Response(requestOptions: options, statusCode: 400),
              ),
            );
          } else {
            handler.resolve(
              Response(
                requestOptions: options,
                statusCode: 200,
                data: <String, Object?>{'releases': [], 'count': requests},
              ),
            );
          }
        },
      ),
    );
    repository = ReleaseRepository(
      api: DreamCastApi(dio),
      cache: cache,
      parser: const DreamCastParserService(),
    );
  });
  tearDown(() async {
    dio.close();
    await database.close();
  });

  test(
    'fresh catalogue avoids HTTP; explicit refresh bypasses cache',
    () async {
      await repository.ongoingCached();
      final cached = await repository.ongoingCached();
      expect(requests, 1);
      expect(cached.value.totalCount, 1);
      expect(cached.isStale, isFalse);
      final refreshed = await repository.ongoingCached(refresh: true);
      expect(requests, 2);
      expect(refreshed.value.totalCount, 2);
    },
  );

  test('forever retention still revalidates catalogue after its TTL', () async {
    await repository.ongoingCached();
    await (database.update(
      database.cacheEntries,
    )..where((row) => row.key.equals(key))).write(
      CacheEntriesCompanion(
        updatedAt: Value(DateTime.now().subtract(const Duration(hours: 1))),
      ),
    );
    expect((await cache.getJsonMap(key))!.isStale, isFalse);
    await repository.ongoingCached();
    expect(requests, 2);
  });

  test('failed refresh returns cached catalogue marked stale', () async {
    await repository.ongoingCached();
    fail = true;
    final fallback = await repository.ongoingCached(refresh: true);
    expect(requests, 2);
    expect(fallback.isStale, isTrue);
    expect(fallback.value.totalCount, 1);
  });

  test('cancelled requests cannot turn into cache hits', () async {
    await repository.ongoingCached();
    final token = CancelToken()..cancel();
    await expectLater(
      repository.ongoingCached(cancelToken: token),
      throwsA(isA<DioException>()),
    );
    expect(requests, 1);
  });

  test('malformed catalogue cache is replaced from network', () async {
    await cache.putJson(key, {'releases': 'broken'});
    await repository.ongoingCached();
    expect(requests, 1);
    expect((await cache.getJsonMap(key))!.value['releases'], isEmpty);
  });

  test('fresh schedule uses cache and refresh attempts the network', () async {
    await cache.putJson('dreamcast:schedule', {
      'days': [
        {
          'title': 'Понедельник',
          'releases': [
            {'id': 1, 'title': 'TV', 'originalTitle': '', 'url': '/1'},
          ],
        },
      ],
    });
    expect((await repository.getSchedule()).isStale, isFalse);
    expect(requests, 0);
    fail = true;
    expect((await repository.getSchedule(refresh: true)).isStale, isTrue);
    expect(requests, 1);
  });

  test('cancelled older search cannot overwrite the latest result', () async {
    SharedPreferences.setMockInitialValues({});
    final preferences = await SharedPreferences.getInstance();
    final started = Completer<void>();
    RequestInterceptorHandler? slowHandler;
    RequestOptions? slowOptions;
    dio.interceptors.insert(
      0,
      InterceptorsWrapper(
        onRequest: (options, handler) {
          if ((options.data as Map)['search'] == 'slow') {
            slowOptions = options;
            slowHandler = handler;
            started.complete();
          } else {
            handler.next(options);
          }
        },
      ),
    );
    final container = ProviderContainer(
      overrides: [
        releaseRepositoryProvider.overrideWithValue(repository),
        sharedPreferencesProvider.overrideWithValue(preferences),
      ],
    );
    addTearDown(container.dispose);
    await container.read(searchReleasesProvider.future);
    final controller = container.read(searchReleasesProvider.notifier);
    final slow = controller.submit('slow');
    await started.future;
    await controller.submit('fast');
    await slow;
    slowHandler!.resolve(
      Response(
        requestOptions: slowOptions!,
        statusCode: 200,
        data: <String, Object?>{'releases': [], 'count': 99},
      ),
    );
    final result = container.read(searchReleasesProvider).requireValue;
    expect(result.query, 'fast');
    expect(result.error, isNull);
    expect(result.isSearching, isFalse);
  });

  test('malformed schedule cache does not prevent a network attempt', () async {
    await cache.putJson('dreamcast:schedule', {
      'days': [
        {
          'title': 'Monday',
          'releases': [
            {'id': 'broken'},
          ],
        },
      ],
    });
    fail = true;
    await expectLater(repository.getSchedule(), throwsA(anything));
    expect(requests, 1);
  });
}
