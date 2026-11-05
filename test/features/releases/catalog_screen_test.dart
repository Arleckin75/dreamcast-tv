import 'package:dream_cast/app/bootstrap/dream_cast_app.dart';
import 'package:dream_cast/features/releases/data/release_repository.dart';
import 'package:dream_cast/features/releases/domain/catalog_options.dart';
import 'package:dream_cast/features/releases/presentation/catalog_providers.dart';
import 'package:dream_cast/features/releases/presentation/catalog_screen.dart';
import 'package:dream_cast/features/releases/presentation/release_list_state.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _Catalog extends CatalogController {
  _Catalog(super.filter, this.requests);
  final List<CatalogFilter> requests;
  @override
  Future<ReleaseListState> build() async {
    requests.add(filter);
    return const ReleaseListState.empty();
  }
}

void main() {
  testWidgets('year and genres apply together and show all resets both', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues({});
    final preferences = await SharedPreferences.getInstance();
    final requests = <CatalogFilter>[];
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          sharedPreferencesProvider.overrideWithValue(preferences),
          catalogOptionsProvider.overrideWith(
            (ref) async => const DreamData(
              value: CatalogOptions(
                years: [2024, 2023],
                genres: [
                  CatalogGenre('romance', 'Романтика'),
                  CatalogGenre('magic', 'Магия'),
                ],
              ),
              isStale: false,
            ),
          ),
          for (final filter in <CatalogFilter>[
            (year: null, genres: ''),
            (year: 2024, genres: ''),
            (year: 2024, genres: 'magic,romance'),
          ])
            catalogReleasesProvider(
              filter,
            ).overrideWith(() => _Catalog(filter, requests)),
        ],
        child: const MaterialApp(home: CatalogScreen()),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Год: любой'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('2024'));
    await tester.pumpAndSettle();
    expect(requests.last.year, 2024);
    await tester.tap(find.text('Жанры: все'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Романтика'));
    await tester.tap(find.text('Магия'));
    await tester.tap(find.text('Применить'));
    await tester.pumpAndSettle();
    expect(requests.last, (year: 2024, genres: 'magic,romance'));
    await tester.tap(find.text('Показать все'));
    await tester.pumpAndSettle();
    expect(find.text('Год: любой'), findsOneWidget);
    expect(find.text('Жанры: все'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
