import 'package:dio/dio.dart';
import 'package:dream_cast/features/releases/domain/catalog_options.dart';
import 'package:dream_cast/core/logging/performance_trace.dart';
import 'package:dream_cast/core/cache/cache_repository.dart';
import 'package:dream_cast/core/errors/app_exception.dart';
import 'package:dream_cast/features/releases/data/dream_cast_api.dart';
import 'package:dream_cast/features/releases/data/dream_cast_diagnostics.dart';
import 'package:dream_cast/features/releases/data/dream_cast_parser_service.dart';
import 'package:dream_cast/features/releases/data/dto/dream_release_dto.dart';
import 'package:dream_cast/features/releases/domain/release.dart';
import 'package:dream_cast/features/schedule/domain/release_schedule.dart';

final class DreamData<T> {
  const DreamData({
    required this.value,
    required this.isStale,
    this.diagnostics,
  });

  final T value;
  final bool isStale;
  final String? diagnostics;
}

final class ReleaseRepository {
  const ReleaseRepository({
    required DreamCastApi api,
    required CacheRepository cache,
    required DreamCastParserService parser,
  }) : _api = api,
       _cache = cache,
       _parser = parser;

  static const releasesTtl = Duration(minutes: 20);
  static const detailsTtl = Duration(hours: 8);
  static const streamsTtl = Duration(hours: 2);
  static const scheduleTtl = Duration(minutes: 30);

  final DreamCastApi _api;
  final CacheRepository _cache;
  final DreamCastParserService _parser;

  Future<DreamData<CatalogOptions>> catalogOptions({
    CancelToken? cancelToken,
  }) async {
    const key = 'dreamcast:catalog:options';
    _checkCancellation(cancelToken);
    final cached = await _cache.getJsonMap(
      key,
      maxAge: const Duration(days: 1),
    );
    CatalogOptions? options;
    try {
      if (cached != null) {
        options = CatalogOptions.fromHtml(cached.value['html'] as String);
      }
    } on Object {
      // A changed or damaged cache is replaced from the site.
    }
    _checkCancellation(cancelToken);
    if (options != null && !cached!.isStale) {
      return DreamData(value: options, isStale: false);
    }
    try {
      final source = await _api.getReleaseHtml('/', cancelToken: cancelToken);
      final result = CatalogOptions.fromHtml(source);
      _checkCancellation(cancelToken);
      await _cache.putJson(key, {'html': source}, ttl: const Duration(days: 1));
      return DreamData(value: result, isStale: false);
    } catch (_) {
      _checkCancellation(cancelToken);
      if (options != null) return DreamData(value: options, isStale: true);
      rethrow;
    }
  }

  Future<DreamData<ReleasePage<DreamRelease>>> catalogCached({
    int? year,
    List<String> genreIds = const [],
    int page = 1,
    int pageSize = 16,
    CancelToken? cancelToken,
  }) {
    final sortedGenres = [...genreIds]..sort();
    return _getReleasePage(
      cacheKey:
          'dreamcast:catalog:${year ?? 'all'}:${sortedGenres.join(',')}:$page:$pageSize',
      page: page,
      pageSize: pageSize,
      cancelToken: cancelToken,
      request: () => _api.getReleases(
        year: year,
        genreIds: sortedGenres,
        page: page,
        pageSize: pageSize,
        cancelToken: cancelToken,
      ),
    );
  }

  void _checkCancellation(CancelToken? token) {
    if (token?.isCancelled == true) throw token!.cancelError!;
  }

  Future<ReleasePage<DreamRelease>> ongoing({
    int page = 1,
    int pageSize = 16,
    CancelToken? cancelToken,
  }) async {
    return (await ongoingCached(
      page: page,
      pageSize: pageSize,
      cancelToken: cancelToken,
    )).value;
  }

  Future<DreamData<ReleasePage<DreamRelease>>> ongoingCached({
    int page = 1,
    int pageSize = 16,
    bool refresh = false,
    CancelToken? cancelToken,
  }) {
    return _getReleasePage(
      cacheKey: 'dreamcast:releases:ongoing:$page:$pageSize',
      refresh: refresh,
      cancelToken: cancelToken,
      page: page,
      pageSize: pageSize,
      request: () => _api.getReleases(
        page: page,
        pageSize: pageSize,
        cancelToken: cancelToken,
      ),
    );
  }

  Future<ReleasePage<DreamRelease>> search(
    String query, {
    int page = 1,
    int pageSize = 16,
    CancelToken? cancelToken,
  }) async {
    return (await searchCached(
      query,
      page: page,
      pageSize: pageSize,
      cancelToken: cancelToken,
    )).value;
  }

  Future<DreamData<ReleasePage<DreamRelease>>> searchCached(
    String query, {
    int page = 1,
    int pageSize = 16,
    CancelToken? cancelToken,
  }) {
    final normalized = query.trim().toLowerCase();
    return _getReleasePage(
      cacheKey: 'dreamcast:releases:search:$normalized:$page:$pageSize',
      cancelToken: cancelToken,
      page: page,
      pageSize: pageSize,
      request: () => _api.getReleases(
        query: normalized,
        page: page,
        pageSize: pageSize,
        cancelToken: cancelToken,
      ),
    );
  }

  Future<DreamData<DreamReleaseDetail>> getDetail(
    DreamRelease release, {
    CancelToken? cancelToken,
  }) async {
    final cacheKey = 'dreamcast:detail:${release.id}';

    try {
      final html = await _api.getReleaseHtml(
        release.url,
        cancelToken: cancelToken,
      );
      final parsed = _parser.parseReleasePage(html);
      logDreamCastDiagnostic(
        'Repository detail parsed: id=${release.id}, title="${parsed.title}", '
        'thumb="${parsed.thumbnailUrl}", script="${parsed.playerScriptUrl}", '
        'payloadLength=${parsed.playerPayload.length}, payloadPreview="${_snippet(parsed.playerPayload)}"',
      );
      final hydratedRelease = _hydrateRelease(
        release,
        title: parsed.title,
        thumbnailUrl: parsed.thumbnailUrl,
        description: parsed.description,
      );
      final detail = DreamReleaseDetail(
        release: hydratedRelease,
        title: parsed.title,
        description: parsed.description,
        thumbnailUrl: parsed.thumbnailUrl,
        playerPayload: parsed.playerPayload,
        playerScriptUrl: parsed.playerScriptUrl,
        fetchedAt: DateTime.now(),
      );
      await _cache.putJson(cacheKey, _detailToJson(detail), ttl: detailsTtl);
      return DreamData(value: detail, isStale: false);
    } catch (error, stackTrace) {
      logDreamCastDiagnostic(
        'Repository detail parse failed: release=${release.id}, '
        'errorType=${error.runtimeType}, error=$error, stackTrace=$stackTrace',
      );
      _checkCancellation(cancelToken);
      final cached = await _detailFromCache(cacheKey, release);
      if (cached != null) return cached;
      rethrow;
    }
  }

  /// Restores a release by numeric id after process death or a deep link.
  /// [seed] is the in-memory object from GoRouter extra when the user just
  /// opened the card from a list; it is ignored if the id does not match.
  Future<DreamRelease> resolveRelease(int id, {DreamRelease? seed}) async {
    if (seed != null && seed.id == id && seed.url.trim().isNotEmpty) {
      return seed;
    }

    final cached = await _releaseFromDetailCache(id);
    if (cached != null && cached.url.trim().isNotEmpty) return cached;

    return DreamRelease(
      id: id,
      title: cached?.title ?? '',
      originalTitle: cached?.originalTitle ?? '',
      url: '/home/release/$id',
      posterUrl: cached?.posterUrl,
      wallUrl: cached?.wallUrl,
      description: cached?.description,
    );
  }

  Future<DreamData<DreamReleaseDetail>> getDetailById(
    int id, {
    DreamRelease? seed,
    CancelToken? cancelToken,
  }) async {
    final release = await resolveRelease(id, seed: seed);
    return getDetail(release, cancelToken: cancelToken);
  }

  Future<DreamData<ReleaseSchedule>> getSchedule({
    CancelToken? cancelToken,
    bool refresh = false,
  }) async {
    const cacheKey = 'dreamcast:schedule';
    _checkCancellation(cancelToken);
    final cached = await _scheduleFromCache(cacheKey);
    _checkCancellation(cancelToken);
    if (!refresh && cached != null && !cached.isStale) return cached;

    try {
      final html = await _api.getScheduleHtml(cancelToken: cancelToken);
      final schedule = _parser.parseSchedule(html);
      final itemCount = schedule.days.fold<int>(
        0,
        (sum, day) => sum + day.releases.length,
      );
      if (itemCount == 0) {
        throw const ParserException(
          'Расписание загрузилось, но релизы в нём не найдены.',
        );
      }
      logDreamCastDiagnostic(
        'Repository schedule parsed: days=${schedule.days.length}, '
        'items=$itemCount',
      );
      await _cache.putJson(
        cacheKey,
        _scheduleToJson(schedule),
        ttl: scheduleTtl,
      );
      return DreamData(value: schedule, isStale: false);
    } catch (error, stackTrace) {
      logDreamCastDiagnostic(
        'Repository schedule failed: errorType=${error.runtimeType}, '
        'error=$error, stackTrace=$stackTrace',
      );
      _checkCancellation(cancelToken);
      if (cached != null) return DreamData(value: cached.value, isStale: true);
      rethrow;
    }
  }

  Future<DreamData<List<DreamEpisode>>> getEpisodes(
    DreamReleaseDetail detail, {
    CancelToken? cancelToken,
  }) async {
    final cacheKey = 'dreamcast:episodes:${detail.release.id}';

    try {
      final script = await _api.getPlayerScript(
        detail.playerScriptUrl,
        cancelToken: cancelToken,
      );
      logDreamCastDiagnostic(
        'Repository episodes decode start: release=${detail.release.id}, '
        'payloadLength=${detail.playerPayload.length}, payloadPreview="${_snippet(detail.playerPayload)}", '
        'scriptLength=${script.length}, scriptPreview="${_snippet(script)}"',
      );
      final result = _parser.extractEpisodesWithDiagnostics(
        releaseId: detail.release.id,
        playerScript: script,
        encodedPayload: detail.playerPayload,
      );
      final episodes = result.episodes;
      logDreamCastDiagnostic(
        'Repository episodes parsed: release=${detail.release.id}, count=${episodes.length}, '
        'first="${episodes.isEmpty ? null : episodes.first.title}", '
        'diagnostics=${result.diagnostics}',
      );
      await _cache.putJson(cacheKey, {
        'episodes': episodes.map(_episodeToJson).toList(),
        'diagnostics': result.diagnostics,
      }, ttl: detailsTtl);
      return DreamData(
        value: episodes,
        isStale: false,
        diagnostics: result.diagnostics,
      );
    } catch (error, stackTrace) {
      logDreamCastDiagnostic(
        'Repository episodes failed: release=${detail.release.id}, '
        'errorType=${error.runtimeType}, error=$error, stackTrace=$stackTrace, '
        'payloadSnippet="${_snippet(detail.playerPayload)}"',
      );
      _checkCancellation(cancelToken);
      final cached = await _episodesFromCache(cacheKey);
      if (cached != null) {
        logDreamCastDiagnostic(
          'Repository episodes returning stale cache after failure: '
          'release=${detail.release.id}, cachedCount=${cached.value.length}',
        );
        return DreamData(
          value: cached.value,
          isStale: true,
          diagnostics:
              'Ошибка свежего декодирования: ${error.runtimeType}: $error\n'
              'Возвращён кэш: ${cached.value.length} серий.\n'
              '${cached.diagnostics ?? ''}',
        );
      }
      rethrow;
    }
  }

  Future<DreamData<List<DreamStream>>> getStreams(
    DreamEpisode episode, {
    bool refresh = false,
  }) async {
    final cacheKey = 'dreamcast:streams:${episode.releaseId}:${episode.id}';

    final cached = await _streamsFromCache(cacheKey);
    if (!refresh &&
        cached != null &&
        !cached.isStale &&
        cached.value.every(
          (stream) =>
              stream.expiresAt == null ||
              stream.expiresAt!.isAfter(DateTime.now()),
        )) {
      return cached;
    }

    final streams = _parser.extractStreams(episode);
    logDreamCastDiagnostic(
      'Repository streams extracted: release=${episode.releaseId}, episode=${episode.id}, '
      'count=${streams.length}, first="${streams.isEmpty ? null : streams.first.url}"',
    );
    if (streams.isEmpty) {
      throw const ParserException('Для серии не найдены потоки видео.');
    }

    await _cache.putJson(cacheKey, {
      'streams': streams.map(_streamToJson).toList(),
    }, ttl: streamsTtl);
    return DreamData(value: streams, isStale: false);
  }

  Future<DreamData<ReleasePage<DreamRelease>>> _getReleasePage({
    required String cacheKey,
    bool refresh = false,
    CancelToken? cancelToken,
    required int page,
    required int pageSize,
    required Future<DreamReleaseListDto> Function() request,
  }) async {
    final trace = PerformanceTrace('catalogue.load');
    try {
      _checkCancellation(cancelToken);
      final cached = await _cache.getJsonMap(cacheKey, maxAge: releasesTtl);
      ReleasePage<DreamRelease>? cachedPage;
      if (cached != null) {
        try {
          cachedPage = _releasePageFromDto(
            DreamReleaseListDto.fromJson(cached.value),
            page: page,
            pageSize: pageSize,
          );
        } on Object {
          // Invalid records are cache misses; fetch a replacement from the API.
        }
      }
      _checkCancellation(cancelToken);
      if (!refresh && cachedPage != null && !cached!.isStale) {
        trace.finish(source: 'cache', items: cachedPage.items.length);
        return DreamData(value: cachedPage, isStale: false);
      }
      try {
        final dto = await request();
        _checkCancellation(cancelToken);
        await _cache.putJson(cacheKey, dto.toJson(), ttl: releasesTtl);
        final pageData = _releasePageFromDto(
          dto,
          page: page,
          pageSize: pageSize,
        );
        trace.finish(source: 'network', items: pageData.items.length);
        logReleaseSample(
          source: 'Repository releases network page=$page',
          releases: pageData.items,
          totalCount: pageData.totalCount,
          isStale: false,
        );
        return DreamData(value: pageData, isStale: false);
      } catch (error) {
        _checkCancellation(cancelToken);
        if (cachedPage == null) rethrow;
        final pageData = cachedPage;
        trace.finish(source: 'stale_cache', items: pageData.items.length);
        logReleaseSample(
          source: 'Repository releases cache page=$page',
          releases: pageData.items,
          totalCount: pageData.totalCount,
          isStale: true,
        );
        return DreamData(value: pageData, isStale: true);
      }
    } catch (_) {
      trace.finish(
        outcome: cancelToken?.isCancelled == true ? 'cancelled' : 'error',
      );
      rethrow;
    }
  }

  ReleasePage<DreamRelease> _releasePageFromDto(
    DreamReleaseListDto dto, {
    required int page,
    required int pageSize,
  }) {
    return ReleasePage<DreamRelease>(
      items: dto.releases
          .map((release) => release.toDomain())
          .toList(growable: false),
      totalCount: dto.count,
      page: page,
      pageSize: pageSize,
    );
  }

  Future<DreamData<DreamReleaseDetail>?> _detailFromCache(
    String cacheKey,
    DreamRelease release,
  ) async {
    final cached = await _cache.getJsonMap(cacheKey);
    if (cached == null) return null;
    return DreamData(
      value: _detailFromJson(cached.value, release),
      isStale: true,
    );
  }

  Future<DreamRelease?> _releaseFromDetailCache(int id) async {
    final cached = await _cache.getJsonMap('dreamcast:detail:$id');
    if (cached == null) return null;
    final raw = cached.value['release'];
    if (raw is! Map) return null;
    return _releaseFromJson(raw.cast<String, Object?>());
  }

  Future<DreamData<List<DreamEpisode>>?> _episodesFromCache(
    String cacheKey,
  ) async {
    final cached = await _cache.getJsonMap(cacheKey);
    if (cached == null) return null;
    final raw = cached.value['episodes'];
    if (raw is! List) return null;
    return DreamData(
      value: raw
          .whereType<Map>()
          .map((item) => _episodeFromJson(item.cast<String, Object?>()))
          .toList(growable: false),
      isStale: true,
      diagnostics: cached.value['diagnostics'] as String?,
    );
  }

  Future<DreamData<List<DreamStream>>?> _streamsFromCache(
    String cacheKey,
  ) async {
    final cached = await _cache.getJsonMap(cacheKey, maxAge: streamsTtl);
    if (cached == null) return null;
    final raw = cached.value['streams'];
    if (raw is! List) return null;
    return DreamData(
      value: raw
          .whereType<Map>()
          .map((item) => _streamFromJson(item.cast<String, Object?>()))
          .toList(growable: false),
      isStale: cached.isStale,
    );
  }

  Future<DreamData<ReleaseSchedule>?> _scheduleFromCache(
    String cacheKey,
  ) async {
    final cached = await _cache.getJsonMap(cacheKey, maxAge: scheduleTtl);
    if (cached == null) return null;
    final daysRaw = cached.value['days'];
    if (daysRaw is! List) return null;
    try {
      final days = daysRaw
          .whereType<Map>()
          .map((item) => _scheduleDayFromJson(item.cast<String, Object?>()))
          .toList(growable: false);
      if (days.every((day) => day.releases.isEmpty)) return null;
      return DreamData(
        value: ReleaseSchedule(days: days),
        isStale: cached.isStale,
      );
    } on TypeError {
      return null;
    }
  }

  Map<String, Object?> _detailToJson(DreamReleaseDetail detail) => {
    'release': _releaseToJson(detail.release),
    'title': detail.title,
    'description': detail.description,
    'thumbnailUrl': detail.thumbnailUrl,
    'playerPayload': detail.playerPayload,
    'playerScriptUrl': detail.playerScriptUrl,
    'fetchedAt': detail.fetchedAt.toIso8601String(),
  };

  DreamReleaseDetail _detailFromJson(
    Map<String, Object?> json,
    DreamRelease release,
  ) {
    final playerPayload = json['playerPayload'] as String?;
    final playerScriptUrl = json['playerScriptUrl'] as String?;
    if (playerPayload == null || playerScriptUrl == null) {
      throw const CacheException('Кэш страницы релиза повреждён.');
    }

    final rawRelease = json['release'];
    final cachedRelease = rawRelease is Map
        ? _releaseFromJson(rawRelease.cast<String, Object?>())
        : null;

    return DreamReleaseDetail(
      release: cachedRelease ?? release,
      title: json['title'] as String? ?? (cachedRelease ?? release).title,
      description: json['description'] as String?,
      thumbnailUrl: json['thumbnailUrl'] as String?,
      playerPayload: playerPayload,
      playerScriptUrl: playerScriptUrl,
      fetchedAt:
          DateTime.tryParse(json['fetchedAt'] as String? ?? '') ??
          DateTime.now(),
    );
  }

  DreamRelease _hydrateRelease(
    DreamRelease release, {
    required String title,
    required String? thumbnailUrl,
    required String? description,
  }) {
    final needsTitle = release.title.trim().isEmpty;
    final needsPoster = release.posterUrl == null || release.posterUrl!.isEmpty;
    final needsDescription =
        release.description == null || release.description!.trim().isEmpty;
    if (!needsTitle && !needsPoster && !needsDescription) return release;

    return DreamRelease(
      id: release.id,
      title: needsTitle ? title : release.title,
      originalTitle: release.originalTitle,
      url: release.url.trim().isEmpty
          ? '/home/release/${release.id}'
          : release.url,
      posterUrl: needsPoster ? thumbnailUrl : release.posterUrl,
      wallUrl: release.wallUrl,
      description: needsDescription ? description : release.description,
      status: release.status,
      type: release.type,
      year: release.year,
      season: release.season,
      genres: release.genres,
      studio: release.studio,
      durationMinutes: release.durationMinutes,
      totalEpisodes: release.totalEpisodes,
      currentEpisodes: release.currentEpisodes,
      rating: release.rating,
      raw: release.raw,
    );
  }

  Map<String, Object?> _episodeToJson(DreamEpisode episode) => {
    'id': episode.id,
    'releaseId': episode.releaseId,
    'ordinal': episode.ordinal,
    'title': episode.title,
    'file': episode.file,
    'label': episode.label,
    'thumbnailUrl': episode.thumbnailUrl,
    'embedUrl': episode.embedUrl,
    'vars': episode.vars,
  };

  DreamEpisode _episodeFromJson(Map<String, Object?> json) {
    return DreamEpisode(
      id: json['id'] as String,
      releaseId: (json['releaseId'] as num).toInt(),
      ordinal: (json['ordinal'] as num).toInt(),
      title: json['title'] as String,
      file: json['file'] as String,
      label: json['label'] as String?,
      thumbnailUrl: json['thumbnailUrl'] as String?,
      embedUrl: json['embedUrl'] as String?,
      vars:
          (json['vars'] as Map?)?.map(
            (key, value) => MapEntry('$key', '$value'),
          ) ??
          const {},
    );
  }

  Map<String, Object?> _streamToJson(DreamStream stream) => {
    'id': stream.id,
    'releaseId': stream.releaseId,
    'episodeId': stream.episodeId,
    'url': stream.url.toString(),
    'type': stream.type.name,
    'quality': stream.quality,
    'headers': stream.headers,
    'expiresAt': stream.expiresAt?.toIso8601String(),
  };

  DreamStream _streamFromJson(Map<String, Object?> json) {
    return DreamStream(
      id: json['id'] as String,
      releaseId: (json['releaseId'] as num).toInt(),
      episodeId: json['episodeId'] as String,
      url: Uri.parse(json['url'] as String),
      type: DreamStreamType.values.firstWhere(
        (type) => type.name == json['type'],
        orElse: () => DreamStreamType.unknown,
      ),
      quality: (json['quality'] as num).toInt(),
      headers:
          (json['headers'] as Map?)?.map(
            (key, value) => MapEntry('$key', '$value'),
          ) ??
          const {},
      expiresAt: DateTime.tryParse(json['expiresAt'] as String? ?? ''),
    );
  }

  Map<String, Object?> _scheduleToJson(ReleaseSchedule schedule) => {
    'days': schedule.days
        .map(
          (day) => {
            'title': day.title,
            'releases': day.releases.map(_releaseToJson).toList(),
          },
        )
        .toList(),
  };

  ReleaseScheduleDay _scheduleDayFromJson(Map<String, Object?> json) {
    final releasesRaw = json['releases'];
    return ReleaseScheduleDay(
      title: json['title'] as String? ?? '',
      releases: releasesRaw is List
          ? releasesRaw
                .whereType<Map>()
                .map((item) => _releaseFromJson(item.cast<String, Object?>()))
                .toList(growable: false)
          : const [],
    );
  }

  Map<String, Object?> _releaseToJson(DreamRelease release) => {
    'id': release.id,
    'title': release.title,
    'originalTitle': release.originalTitle,
    'url': release.url,
    'posterUrl': release.posterUrl,
    'wallUrl': release.wallUrl,
    'description': release.description,
    'status': release.status,
    'type': release.type,
    'year': release.year,
    'season': release.season,
    'genres': release.genres,
    'studio': release.studio,
    'durationMinutes': release.durationMinutes,
    'totalEpisodes': release.totalEpisodes,
    'currentEpisodes': release.currentEpisodes,
    'rating': release.rating,
    'raw': release.raw,
  };

  DreamRelease _releaseFromJson(Map<String, Object?> json) {
    return DreamRelease(
      id: (json['id'] as num).toInt(),
      title: json['title'] as String? ?? '',
      originalTitle: json['originalTitle'] as String? ?? '',
      url: json['url'] as String? ?? '',
      posterUrl: json['posterUrl'] as String?,
      wallUrl: json['wallUrl'] as String?,
      description: json['description'] as String?,
      status: json['status'] as String?,
      type: json['type'] as String?,
      year: (json['year'] as num?)?.toInt(),
      season: json['season'] as String?,
      genres: json['genres'] as String?,
      studio: json['studio'] as String?,
      durationMinutes: (json['durationMinutes'] as num?)?.toInt(),
      totalEpisodes: (json['totalEpisodes'] as num?)?.toInt(),
      currentEpisodes: (json['currentEpisodes'] as num?)?.toInt(),
      rating: json['rating'] as String?,
      raw: (json['raw'] as Map?)?.cast<String, Object?>() ?? const {},
    );
  }
}

String _snippet(String value, {int max = 500}) {
  final normalized = value.replaceAll(RegExp(r'\s+'), ' ').trim();
  if (normalized.length <= max) return normalized;
  return '${normalized.substring(0, max)}...';
}
