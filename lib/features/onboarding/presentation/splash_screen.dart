import 'package:dream_cast/app/widgets/app_loading_view.dart';
import 'package:dream_cast/core/settings/settings_providers.dart';
import 'package:dream_cast/features/profile/data/profile_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

/// Initializes the local TV profile on first launch, without setup screens.
/// Existing profiles, preferences, bookmarks, and watch progress are preserved.
class SplashScreen extends ConsumerStatefulWidget {
  const SplashScreen({super.key});

  @override
  ConsumerState<SplashScreen> createState() => _SplashScreenState();
}

class _SplashScreenState extends ConsumerState<SplashScreen> {
  String? _error;
  bool _running = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _initialize());
  }

  Future<void> _initialize() async {
    if (_running || !mounted) return;
    setState(() {
      _running = true;
      _error = null;
    });
    try {
      final profiles = ref.read(profileRepositoryProvider);
      final existing = await profiles.activeProfile();
      if (existing == null) {
        await ref.read(activeProfileProvider.notifier).save('TV');
      }
      // Keep the historical preference consistent with earlier installations.
      await ref.read(appSettingsProvider.notifier).completeOnboarding();
      if (mounted) context.go('/home');
    } catch (error) {
      if (mounted) {
        setState(() {
          _error = 'Не удалось подготовить локальный профиль: $error';
        });
      }
    } finally {
      _running = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: _error == null
          ? const AppLoadingView(message: 'Подготовка приложения…')
          : Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(_error!, textAlign: TextAlign.center),
                    const SizedBox(height: 16),
                    FilledButton(
                      autofocus: true,
                      onPressed: _initialize,
                      child: const Text('Повторить'),
                    ),
                  ],
                ),
              ),
            ),
    );
  }
}
