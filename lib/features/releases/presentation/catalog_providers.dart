import 'package:dio/dio.dart';
import 'package:dream_cast/features/releases/data/release_providers.dart';
import 'package:dream_cast/features/releases/data/release_repository.dart';
import 'package:dream_cast/features/releases/domain/catalog_options.dart';
import 'package:dream_cast/features/releases/presentation/release_list_state.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

final catalogOptionsProvider =
    FutureProvider.autoDispose<DreamData<CatalogOptions>>((ref) {
      final token = CancelToken();
      ref.onDispose(token.cancel);
      return ref
          .watch(releaseRepositoryProvider)
          .catalogOptions(cancelToken: token);
    });

typedef CatalogFilter = ({int? year, String genres});

final catalogReleasesProvider = AsyncNotifierProvider.autoDispose
    .family<CatalogController, ReleaseListState, CatalogFilter>(
      CatalogController.new,
    );

class CatalogController extends AsyncNotifier<ReleaseListState> {
  CatalogController(this.filter);
  final CatalogFilter filter;
  final _token = CancelToken();

  @override
  Future<ReleaseListState> build() {
    ref.onDispose(_token.cancel);
    return _page(1);
  }

  Future<ReleaseListState> _page(int page) async {
    final data = await ref
        .read(releaseRepositoryProvider)
        .catalogCached(
          year: filter.year,
          genreIds: filter.genres.isEmpty ? const [] : filter.genres.split(','),
          page: page,
          cancelToken: _token,
        );
    return ReleaseListState(
      items: data.value.items,
      totalCount: data.value.totalCount,
      page: page,
      pageSize: data.value.pageSize,
      isStale: data.isStale,
    );
  }

  Future<void> loadMore() async {
    final current = state.asData?.value;
    if (current == null || current.isLoadingMore || !current.hasMore) return;
    state = AsyncData(
      current.copyWith(isLoadingMore: true, clearLoadMoreError: true),
    );
    try {
      final next = await _page(current.page + 1);
      if (!ref.mounted) return;
      final seen = current.items.map((item) => item.id).toSet();
      final added = next.items.where((item) => seen.add(item.id)).toList();
      state = AsyncData(
        next.copyWith(
          items: [...current.items, ...added],
          totalCount: added.isEmpty ? current.items.length : next.totalCount,
          isStale: current.isStale || next.isStale,
        ),
      );
    } catch (error) {
      if (ref.mounted) {
        state = AsyncData(current.copyWith(loadMoreError: error));
      }
    }
  }
}
