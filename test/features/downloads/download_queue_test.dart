import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:dream_cast/core/database/app_database.dart';
import 'package:dream_cast/features/downloads/data/download_service.dart';
import 'package:dream_cast/features/releases/domain/release.dart';
import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:path_provider_platform_interface/path_provider_platform_interface.dart';

class _Documents extends PathProviderPlatform {
  _Documents(this.path);
  final String path;
  @override
  Future<String?> getApplicationDocumentsPath() async => path;
}

class _HlsAdapter implements HttpClientAdapter {
  final paths = <String>[];
  final firstSegment = Completer<void>();
  Completer<void>? holdSegment;
  String playlist = '#EXTM3U\n#EXTINF:1,\nsegment.ts\n#EXT-X-ENDLIST\n';
  bool failSegment = false;
  bool cancelled = false;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    paths.add(options.uri.path);
    if (options.uri.path.endsWith('.m3u8')) {
      return ResponseBody.fromString(playlist, 200);
    }
    if (!firstSegment.isCompleted) firstSegment.complete();
    if (holdSegment != null) {
      await Future.any([
        holdSegment!.future,
        if (cancelFuture != null)
          cancelFuture.then((_) {
            cancelled = true;
            throw DioException(
              requestOptions: options,
              type: DioExceptionType.cancel,
            );
          }),
      ]);
    }
    return ResponseBody.fromString(
      failSegment ? '' : 'media',
      failSegment ? 500 : 200,
    );
  }

  @override
  void close({bool force = false}) {}
}

const _release = DreamRelease(
  id: 9,
  title: 'Release',
  originalTitle: '',
  url: '/home/release/9',
);
DreamEpisode _episode(int ordinal) => DreamEpisode(
  id: '$ordinal',
  releaseId: 9,
  ordinal: ordinal,
  title: 'Episode $ordinal',
  file: 'https://example.com/$ordinal/playlist.m3u8',
);
DreamStream _stream(int ordinal) => DreamStream(
  id: '$ordinal',
  releaseId: 9,
  episodeId: '$ordinal',
  url: Uri.parse('https://example.com/$ordinal/playlist.m3u8'),
  type: DreamStreamType.hls,
  quality: 720,
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late AppDatabase database;
  late AppDatabase workerDatabase;
  late Directory directory;
  late _HlsAdapter adapter;
  late DownloadService ui;
  late DownloadService worker;
  late PathProviderPlatform originalPaths;

  setUp(() async {
    directory = await Directory.systemTemp.createTemp(
      'dreamcast-download-test-',
    );
    originalPaths = PathProviderPlatform.instance;
    PathProviderPlatform.instance = _Documents(directory.path);
    final file = File('${directory.path}/downloads.sqlite');
    database = AppDatabase(
      NativeDatabase.createInBackground(
        file,
        setup: (sqlite) {
          sqlite.execute('PRAGMA busy_timeout = 15000');
          sqlite.execute('PRAGMA journal_mode = WAL');
        },
      ),
    );
    workerDatabase = AppDatabase(
      NativeDatabase.createInBackground(
        file,
        setup: (sqlite) {
          sqlite.execute('PRAGMA busy_timeout = 15000');
          sqlite.execute('PRAGMA journal_mode = WAL');
        },
      ),
    );
    adapter = _HlsAdapter();
    final dio = Dio()..httpClientAdapter = adapter;
    ui = DownloadService(database, dio, useForegroundTask: false);
    worker = DownloadService(workerDatabase, dio, useForegroundTask: false);
  });
  tearDown(() async {
    PathProviderPlatform.instance = originalPaths;
    await workerDatabase.close();
    await database.close();
    await directory.delete(recursive: true);
  });

  Future<void> enqueue(int ordinal) => ui.startDownload(
    release: _release,
    episode: _episode(ordinal),
    stream: _stream(ordinal),
  );

  test(
    'a second request joins the durable queue without interrupting the first',
    () async {
      adapter.holdSegment = Completer<void>();
      await enqueue(1);
      final draining = worker.drainQueue(onIdle: () async {});
      await adapter.firstSegment.future;
      await enqueue(2);
      expect((await database.allDownloadedEpisodes()).length, 2);
      adapter.holdSegment!.complete();
      await draining;
      expect(adapter.cancelled, isFalse);
      expect(
        (await database.allDownloadedEpisodes()).every(
          (row) => row.status == 'completed',
        ),
        isTrue,
      );
      expect(
        adapter.paths.indexOf('/1/segment.ts'),
        lessThan(adapter.paths.indexOf('/2/segment.ts')),
      );
      final row = await database.downloadedEpisode(9, '1');
      expect(
        await File(row!.localFilePath).readAsString(),
        contains('segment_00000.ts'),
      );
      expect(await database.select(database.settingsRows).get(), isEmpty);
    },
  );

  test(
    'UI cancellation reaches a different worker instance and removes files',
    () async {
      adapter.holdSegment = Completer<void>();
      await enqueue(1);
      final draining = worker.drainQueue(onIdle: () async {});
      await adapter.firstSegment.future;
      await ui.cancelDownload(9, '1');
      await draining;
      expect(adapter.cancelled, isTrue);
      expect(await database.downloadedEpisode(9, '1'), isNull);
      expect(
        await Directory('${directory.path}/downloads/ep_9_1').exists(),
        isFalse,
      );
    },
  );

  test('failed download reports failure and cleans partial media', () async {
    adapter.failSegment = true;
    await enqueue(1);
    final succeeded = await worker.runDownload(
      release: _release,
      episode: _episode(1),
      stream: _stream(1),
      cancelToken: CancelToken(),
    );
    expect(succeeded, isFalse);
    expect((await database.downloadedEpisode(9, '1'))!.status, 'failed');
    expect(
      await Directory('${directory.path}/downloads/ep_9_1').exists(),
      isFalse,
    );
  });

  test(
    'unsupported byte ranges fail instead of saving a broken offline copy',
    () async {
      adapter.playlist =
          '#EXTM3U\n#EXT-X-BYTERANGE:10@0\nsegment.ts\n#EXT-X-ENDLIST';
      await enqueue(1);
      expect(
        await worker.runDownload(
          release: _release,
          episode: _episode(1),
          stream: _stream(1),
          cancelToken: CancelToken(),
        ),
        isFalse,
      );
      expect(adapter.paths.where((path) => path.endsWith('.ts')), isEmpty);
    },
  );

  test(
    'a batch is persisted as individual queue entries and completes',
    () async {
      await ui.startBatchDownload(
        release: _release,
        requests: [
          DreamEpisodeDownloadRequest(episode: _episode(1), stream: _stream(1)),
          DreamEpisodeDownloadRequest(episode: _episode(2), stream: _stream(2)),
        ],
      );
      expect((await database.select(database.settingsRows).get()).length, 2);
      await worker.drainQueue(onIdle: () async {});
      expect(
        (await database.allDownloadedEpisodes()).every(
          (row) => row.status == 'completed',
        ),
        isTrue,
      );
    },
  );
}
