import 'package:dio/dio.dart';
import 'package:dream_cast/features/releases/data/dream_cast_api.dart';
import 'package:dream_cast/features/releases/domain/catalog_options.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('site options preserve genre identifiers and decode labels', () {
    final options = CatalogOptions.fromHtml('''
      <select id="dateissue"><option value="2020">2020</option>
        <option value="2024">2024</option><option value="2020">2020</option></select>
      <select id="genres"><option value="romance">Романтика &amp; драма</option>
        <option value="magic">Магия</option><option value="">Все</option></select>
    ''');
    expect(options.years, [2024, 2020]);
    expect(options.genres.map((g) => g.id), ['magic', 'romance']);
    expect(options.genres.last.name, 'Романтика & драма');
  });

  test(
    'missing site filters are reported instead of silently empty options',
    () {
      expect(
        () => CatalogOptions.fromHtml('<html>Unavailable</html>'),
        throwsFormatException,
      );
    },
  );

  test(
    'catalog sends site array fields and resets to unfiltered request',
    () async {
      final dio = Dio(BaseOptions(baseUrl: 'https://example.com'));
      addTearDown(dio.close);
      final requests = <Map<String, dynamic>>[];
      dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            requests.add(Map<String, dynamic>.from(options.data as Map));
            handler.resolve(
              Response(
                requestOptions: options,
                data: {'releases': [], 'count': 0},
              ),
            );
          },
        ),
      );
      final api = DreamCastApi(dio);
      await api.getReleases(
        year: 2024,
        genreIds: ['romance', 'magic'],
        page: 2,
      );
      await api.getReleases();
      expect(requests.first['dateissue[]'], [2024]);
      expect(requests.first['genres[]'], ['romance', 'magic']);
      expect(requests.first['pageNumber'], 2);
      expect(requests.last.containsKey('dateissue[]'), isFalse);
      expect(requests.last.containsKey('genres[]'), isFalse);
    },
  );
}
