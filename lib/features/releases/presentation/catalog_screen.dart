import 'package:dream_cast/app/widgets/app_error_view.dart';
import 'package:dream_cast/app/widgets/tv_dialog.dart';
import 'package:dream_cast/core/settings/app_ui_preferences.dart';
import 'package:dream_cast/features/releases/domain/catalog_options.dart';
import 'package:dream_cast/features/releases/presentation/catalog_providers.dart';
import 'package:dream_cast/features/releases/presentation/widgets/release_card.dart';
import 'package:dream_cast/features/releases/presentation/widgets/release_skeletons.dart';
import 'package:dream_cast/features/releases/presentation/widgets/stale_cache_banner.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

class CatalogScreen extends ConsumerStatefulWidget {
  const CatalogScreen({super.key});
  @override
  ConsumerState<CatalogScreen> createState() => _CatalogScreenState();
}

class _CatalogScreenState extends ConsumerState<CatalogScreen> {
  int? _year;
  Set<String> _genres = {};
  final _scroll = ScrollController();
  CatalogFilter get _filter {
    final ids = _genres.toList()..sort();
    return (year: _year, genres: ids.join(','));
  }

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_onScroll);
  }

  void _onScroll() {
    final data = ref.read(catalogReleasesProvider(_filter)).asData?.value;
    if (_scroll.position.extentAfter < 700 && data?.loadMoreError == null) {
      ref.read(catalogReleasesProvider(_filter).notifier).loadMore();
    }
  }

  void _change(VoidCallback change) {
    if (_scroll.hasClients) _scroll.jumpTo(0);
    setState(change);
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final optionsState = ref.watch(catalogOptionsProvider);
    final options = optionsState.asData?.value;
    final provider = catalogReleasesProvider(_filter);
    final releases = ref.watch(provider);
    final width = MediaQuery.sizeOf(context).width;
    final columns = width >= 900
        ? (width / 210).floor().clamp(4, 8)
        : ref.watch(appUiPreferencesProvider).homeGridColumns;
    final names = options?.value.genres
        .where((g) => _genres.contains(g.id))
        .map((g) => g.name)
        .join(', ');
    return Scaffold(
      appBar: AppBar(title: const Text('Каталог')),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(16),
            child: Wrap(
              spacing: 12,
              runSpacing: 12,
              children: [
                OutlinedButton.icon(
                  autofocus: true,
                  onPressed: options == null
                      ? null
                      : () => _pickYear(options.value),
                  icon: const Icon(Icons.calendar_month_outlined),
                  label: Text('Год: ${_year ?? 'любой'}'),
                ),
                OutlinedButton.icon(
                  onPressed: options == null
                      ? null
                      : () => _pickGenres(options.value),
                  icon: const Icon(Icons.category_outlined),
                  label: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 400),
                    child: Text(
                      'Жанры: ${_genres.isEmpty ? 'все' : names ?? _genres.length.toString()}',
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                ),
                FilledButton(
                  onPressed: () => _change(() {
                    _year = null;
                    _genres = {};
                  }),
                  child: const Text('Показать все'),
                ),
              ],
            ),
          ),
          if (optionsState.isLoading) const LinearProgressIndicator(),
          if (optionsState.hasError)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Wrap(
                crossAxisAlignment: WrapCrossAlignment.center,
                spacing: 12,
                children: [
                  const Text('Не удалось загрузить фильтры каталога'),
                  TextButton(
                    onPressed: () => ref.invalidate(catalogOptionsProvider),
                    child: const Text('Повторить'),
                  ),
                ],
              ),
            ),
          if (options?.isStale == true) const StaleCacheBanner(),
          Expanded(
            child: releases.when(
              loading: () => const ReleaseGridSkeleton(),
              error: (error, stack) => AppErrorView(
                error: error,
                onRetry: () => ref.invalidate(provider),
              ),
              data: (data) => CustomScrollView(
                controller: _scroll,
                slivers: [
                  if (data.isStale)
                    const SliverToBoxAdapter(child: StaleCacheBanner()),
                  SliverToBoxAdapter(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Text(
                        data.items.isEmpty
                            ? 'По выбранным фильтрам ничего не найдено'
                            : 'Найдено: ${data.totalCount}',
                      ),
                    ),
                  ),
                  SliverPadding(
                    padding: const EdgeInsets.all(16),
                    sliver: SliverGrid.builder(
                      itemCount: data.items.length,
                      gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                        crossAxisCount: columns,
                        mainAxisSpacing: 16,
                        crossAxisSpacing: 12,
                        childAspectRatio: width >= 900 ? 0.60 : 0.49,
                      ),
                      itemBuilder: (context, index) {
                        final release = data.items[index];
                        return ReleaseCard(
                          key: ValueKey(release.id),
                          release: release,
                          onTap: () => context.push(
                            '/release/${release.id}',
                            extra: release,
                          ),
                        );
                      },
                    ),
                  ),
                  SliverToBoxAdapter(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: data.isLoadingMore
                          ? const Center(child: CircularProgressIndicator())
                          : data.hasMore
                          ? Center(
                              child: OutlinedButton(
                                onPressed: () =>
                                    ref.read(provider.notifier).loadMore(),
                                child: Text(
                                  data.loadMoreError == null
                                      ? 'Загрузить ещё'
                                      : 'Повторить загрузку',
                                ),
                              ),
                            )
                          : const SizedBox.shrink(),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _pickYear(CatalogOptions options) async {
    final result = await showTvDialog<int>(
      context: context,
      builder: (dialogContext) => SizedBox(
        width: 420,
        child: ListView(
          shrinkWrap: true,
          children: [
            Padding(
              padding: const EdgeInsets.all(16),
              child: Text(
                'Год выпуска',
                style: Theme.of(context).textTheme.titleLarge,
              ),
            ),
            ListTile(
              autofocus: _year == null,
              selected: _year == null,
              title: const Text('Любой год'),
              onTap: () => Navigator.pop(dialogContext, 0),
            ),
            for (final year in options.years)
              ListTile(
                autofocus: _year == year,
                selected: _year == year,
                title: Text('$year'),
                onTap: () => Navigator.pop(dialogContext, year),
              ),
          ],
        ),
      ),
    );
    if (!mounted || result == null) return;
    _change(() => _year = result == 0 ? null : result);
  }

  Future<void> _pickGenres(CatalogOptions options) async {
    final selected = {..._genres};
    final result = await showTvDialog<Set<String>>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, update) => SizedBox(
          width: 560,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Padding(
                padding: const EdgeInsets.all(16),
                child: Text(
                  'Жанры',
                  style: Theme.of(context).textTheme.titleLarge,
                ),
              ),
              Flexible(
                child: ListView(
                  shrinkWrap: true,
                  children: [
                    for (final genre in options.genres)
                      CheckboxListTile(
                        title: Text(genre.name),
                        value: selected.contains(genre.id),
                        onChanged: (value) => update(() {
                          if (value == true) {
                            selected.add(genre.id);
                          } else {
                            selected.remove(genre.id);
                          }
                        }),
                      ),
                  ],
                ),
              ),
              Padding(
                padding: const EdgeInsets.all(16),
                child: Wrap(
                  spacing: 16,
                  children: [
                    TextButton(
                      onPressed: () => update(selected.clear),
                      child: const Text('Сбросить'),
                    ),
                    FilledButton(
                      autofocus: true,
                      onPressed: () => Navigator.pop(dialogContext, selected),
                      child: const Text('Применить'),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
    if (!mounted || result == null) return;
    _change(() => _genres = result);
  }
}
