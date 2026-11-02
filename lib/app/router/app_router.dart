import 'package:animations/animations.dart';
import 'package:dream_cast/app/router/app_routes.dart';
import 'package:dream_cast/app/widgets/app_error_view.dart';
import 'package:dream_cast/features/releases/presentation/release_list_providers.dart';
import 'package:dream_cast/app/widgets/adaptive_app_shell.dart';
import 'package:dream_cast/features/home/presentation/home_screen.dart';
import 'package:dream_cast/features/library/presentation/library_screen.dart';
import 'package:dream_cast/features/notifications/presentation/notification_history_screen.dart';
import 'package:dream_cast/features/onboarding/presentation/splash_screen.dart';
import 'package:dream_cast/features/player/domain/playback_request.dart';
import 'package:dream_cast/features/player/presentation/player_placeholder_screen.dart';
import 'package:dream_cast/features/player/presentation/video_player_screen.dart';
import 'package:dream_cast/features/profile/presentation/profile_screen.dart';
import 'package:dream_cast/features/releases/domain/release.dart';
import 'package:dream_cast/features/releases/presentation/episode_list_screen.dart';
import 'package:dream_cast/features/releases/presentation/release_detail_screen.dart';
import 'package:dream_cast/features/releases/presentation/search_screen.dart';
import 'package:dream_cast/features/schedule/presentation/schedule_screen.dart';
import 'package:dream_cast/features/settings/presentation/about_app_screen.dart';
import 'package:dream_cast/features/settings/presentation/cache_settings_screen.dart';
import 'package:dream_cast/features/settings/presentation/settings_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

final _rootNavigatorKey = GlobalKey<NavigatorState>();
final _homeNavigatorKey = GlobalKey<NavigatorState>();
final _libraryNavigatorKey = GlobalKey<NavigatorState>();
final _scheduleNavigatorKey = GlobalKey<NavigatorState>();
final _settingsNavigatorKey = GlobalKey<NavigatorState>();

final appRouterProvider = Provider<GoRouter>((ref) {
  return GoRouter(
    navigatorKey: _rootNavigatorKey,
    initialLocation: '/',
    routes: [
      GoRoute(
        path: '/',
        pageBuilder: (context, state) =>
            _fadeThroughPage(key: state.pageKey, child: const SplashScreen()),
      ),
      StatefulShellRoute.indexedStack(
        builder: (context, state, navigationShell) {
          return AdaptiveAppShell(navigationShell: navigationShell);
        },
        branches: [
          StatefulShellBranch(
            navigatorKey: _homeNavigatorKey,
            routes: [
              GoRoute(
                path: '/home',
                pageBuilder: (context, state) => _fadeThroughPage(
                  key: state.pageKey,
                  child: const HomeScreen(),
                ),
              ),
            ],
          ),
          StatefulShellBranch(
            navigatorKey: _libraryNavigatorKey,
            routes: [
              GoRoute(
                path: '/library',
                pageBuilder: (context, state) => _fadeThroughPage(
                  key: state.pageKey,
                  child: const LibraryScreen(),
                ),
                routes: [
                  GoRoute(
                    path: 'status/:status',
                    parentNavigatorKey: _rootNavigatorKey,
                    pageBuilder: (context, state) {
                      final status = libraryStatusFromName(
                        state.pathParameters['status'],
                      );
                      return _sharedAxisPage(
                        key: state.pageKey,
                        child: status == null
                            ? const _MissingRouteDataScreen()
                            : LibraryStatusScreen(status: status),
                      );
                    },
                  ),
                ],
              ),
            ],
          ),
          StatefulShellBranch(
            navigatorKey: _scheduleNavigatorKey,
            routes: [
              GoRoute(
                path: '/schedule',
                pageBuilder: (context, state) => _fadeThroughPage(
                  key: state.pageKey,
                  child: const ScheduleScreen(),
                ),
              ),
            ],
          ),
          StatefulShellBranch(
            navigatorKey: _settingsNavigatorKey,
            routes: [
              GoRoute(
                path: '/settings',
                pageBuilder: (context, state) => _fadeThroughPage(
                  key: state.pageKey,
                  child: const SettingsScreen(),
                ),
                routes: [
                  GoRoute(
                    path: 'profile',
                    parentNavigatorKey: _rootNavigatorKey,
                    pageBuilder: (context, state) => _sharedAxisPage(
                      key: state.pageKey,
                      child: const ProfileScreen(),
                    ),
                  ),
                  GoRoute(
                    path: 'about',
                    parentNavigatorKey: _rootNavigatorKey,
                    pageBuilder: (context, state) => _sharedAxisPage(
                      key: state.pageKey,
                      child: const AboutAppScreen(),
                    ),
                  ),
                  GoRoute(
                    path: 'cache',
                    parentNavigatorKey: _rootNavigatorKey,
                    pageBuilder: (context, state) => _sharedAxisPage(
                      key: state.pageKey,
                      child: const CacheSettingsScreen(),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ],
      ),
      GoRoute(
        path: '/search',
        parentNavigatorKey: _rootNavigatorKey,
        pageBuilder: (context, state) =>
            _sharedAxisPage(key: state.pageKey, child: const SearchScreen()),
      ),
      GoRoute(
        path: '/notifications',
        parentNavigatorKey: _rootNavigatorKey,
        pageBuilder: (context, state) => _sharedAxisPage(
          key: state.pageKey,
          child: const NotificationHistoryScreen(),
        ),
      ),
      GoRoute(
        path: '/release/:id',
        parentNavigatorKey: _rootNavigatorKey,
        pageBuilder: (context, state) {
          final id = int.tryParse(state.pathParameters['id'] ?? '');
          return _sharedAxisPage(
            key: state.pageKey,
            child: id != null && id > 0
                ? _ReleaseRouteScreen(
                    id: id,
                    seed: releaseExtra(state.extra, id),
                  )
                : const _MissingRouteDataScreen(),
          );
        },
      ),
      GoRoute(
        path: '/release/:id/episodes',
        parentNavigatorKey: _rootNavigatorKey,
        pageBuilder: (context, state) {
          final id = int.tryParse(state.pathParameters['id'] ?? '');
          return _sharedAxisPage(
            key: state.pageKey,
            child: id != null && id > 0
                ? _ReleaseRouteScreen(id: id, episodes: true)
                : const _MissingRouteDataScreen(),
          );
        },
      ),
      GoRoute(
        path: '/watch/:releaseId/:episodeId',
        parentNavigatorKey: _rootNavigatorKey,
        pageBuilder: (context, state) {
          final id = int.tryParse(state.pathParameters['releaseId'] ?? '');
          final episodeId = state.pathParameters['episodeId'];
          return _sharedAxisPage(
            key: state.pageKey,
            child: id != null && id > 0 && episodeId != null
                ? _WatchRouteScreen(
                    releaseId: id,
                    episodeId: episodeId,
                    request: state.extra is PlaybackRequest
                        ? state.extra as PlaybackRequest
                        : null,
                  )
                : const _MissingRouteDataScreen(),
          );
        },
      ),
      GoRoute(
        path: '/episodes',
        redirect: (context, state) => state.extra is DreamReleaseDetail
            ? releaseEpisodesLocation(
                (state.extra as DreamReleaseDetail).release.id,
              )
            : '/home',
        parentNavigatorKey: _rootNavigatorKey,
        pageBuilder: (context, state) {
          final detail = state.extra;
          return _sharedAxisPage(
            key: state.pageKey,
            child: detail is DreamReleaseDetail
                ? EpisodeListScreen(detail: detail)
                : const _MissingRouteDataScreen(),
          );
        },
      ),
      GoRoute(
        path: '/watch',
        redirect: (context, state) {
          final request = state.extra;
          return request is PlaybackRequest
              ? watchLocation(request.release.id, request.episode.id)
              : '/home';
        },
        parentNavigatorKey: _rootNavigatorKey,
        pageBuilder: (context, state) {
          final request = state.extra;
          return _sharedAxisPage(
            key: state.pageKey,
            child: request is PlaybackRequest
                ? VideoPlayerScreen(request: request)
                : const _MissingRouteDataScreen(),
          );
        },
      ),
      GoRoute(
        path: '/player',
        parentNavigatorKey: _rootNavigatorKey,
        pageBuilder: (context, state) => _sharedAxisPage(
          key: state.pageKey,
          child: const PlayerPlaceholderScreen(),
        ),
      ),
    ],
  );
});

class _ReleaseRouteScreen extends ConsumerWidget {
  const _ReleaseRouteScreen({
    required this.id,
    this.seed,
    this.episodes = false,
  });
  final int id;
  final DreamRelease? seed;
  final bool episodes;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (episodes) {
      final provider = releaseDetailByIdProvider((id: id, seed: seed));
      return ref
          .watch(provider)
          .when(
            data: (data) => EpisodeListScreen(detail: data.value),
            loading: () => const Scaffold(
              body: Center(child: CircularProgressIndicator()),
            ),
            error: (error, stack) => Scaffold(
              body: AppErrorView(
                error: error,
                onRetry: () => ref.invalidate(provider),
              ),
            ),
          );
    }
    final provider = releaseByIdProvider((id: id, seed: seed));
    return ref
        .watch(provider)
        .when(
          data: (release) => ReleaseDetailScreen(release: release),
          loading: () =>
              const Scaffold(body: Center(child: CircularProgressIndicator())),
          error: (error, stack) => Scaffold(
            body: AppErrorView(
              error: error,
              onRetry: () => ref.invalidate(provider),
            ),
          ),
        );
  }
}

class _WatchRouteScreen extends ConsumerWidget {
  const _WatchRouteScreen({
    required this.releaseId,
    required this.episodeId,
    this.request,
  });
  final int releaseId;
  final String episodeId;
  final PlaybackRequest? request;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final seed = request;
    if (seed != null &&
        seed.release.id == releaseId &&
        seed.episode.id == episodeId) {
      return VideoPlayerScreen(request: seed);
    }
    final provider = watchPlaybackRequestProvider((
      releaseId: releaseId,
      episodeId: episodeId,
    ));
    return ref
        .watch(provider)
        .when(
          data: (request) => VideoPlayerScreen(request: request),
          loading: () =>
              const Scaffold(body: Center(child: CircularProgressIndicator())),
          error: (error, stack) => Scaffold(
            body: AppErrorView(
              error: error,
              onRetry: () => ref.invalidate(provider),
            ),
          ),
        );
  }
}

class _MissingRouteDataScreen extends StatelessWidget {
  const _MissingRouteDataScreen();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Не удалось открыть')),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text(
            'Экран был открыт без данных релиза. Вернитесь назад и выберите релиз снова.',
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.bodyLarge,
          ),
        ),
      ),
    );
  }
}

Page<void> _fadeThroughPage({required LocalKey key, required Widget child}) {
  return CustomTransitionPage<void>(
    key: key,
    child: child,
    transitionDuration: const Duration(milliseconds: 240),
    reverseTransitionDuration: const Duration(milliseconds: 200),
    transitionsBuilder: (context, animation, secondaryAnimation, child) {
      return FadeThroughTransition(
        animation: animation,
        secondaryAnimation: secondaryAnimation,
        child: child,
      );
    },
  );
}

Page<void> _sharedAxisPage({required LocalKey key, required Widget child}) {
  return CustomTransitionPage<void>(
    key: key,
    child: child,
    transitionDuration: const Duration(milliseconds: 300),
    reverseTransitionDuration: const Duration(milliseconds: 240),
    transitionsBuilder: (context, animation, secondaryAnimation, child) {
      return SharedAxisTransition(
        animation: animation,
        secondaryAnimation: secondaryAnimation,
        transitionType: SharedAxisTransitionType.horizontal,
        child: child,
      );
    },
  );
}
