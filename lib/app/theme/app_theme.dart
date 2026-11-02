import 'package:dream_cast/core/settings/app_settings.dart';
import 'package:flutter/material.dart';

final class AppThemeBundle {
  const AppThemeBundle({required this.light, required this.dark});

  final ThemeData light;
  final ThemeData dark;
}

final class AppTheme {
  const AppTheme._();

  static AppThemeBundle build({
    required AppSettings settings,
    required ColorScheme? lightDynamic,
    required ColorScheme? darkDynamic,
  }) {
    final useSystemAccent =
        settings.useDynamicColor && settings.accent == AppAccent.system;
    final lightScheme = useSystemAccent && lightDynamic != null
        ? lightDynamic
        : ColorScheme.fromSeed(
            seedColor: settings.accent.seedColor,
            brightness: Brightness.light,
          );
    final darkScheme = useSystemAccent && darkDynamic != null
        ? darkDynamic
        : ColorScheme.fromSeed(
            seedColor: settings.accent.seedColor,
            brightness: Brightness.dark,
          );

    return AppThemeBundle(light: _theme(lightScheme), dark: _theme(darkScheme));
  }

  static ThemeData _theme(ColorScheme scheme) {
    final typography = Typography.material2021();
    final textTheme = typography.englishLike
        .merge(typography.black)
        .apply(
          fontSizeFactor: 1.1,
          bodyColor: scheme.onSurface,
          displayColor: scheme.onSurface,
        );

    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      textTheme: textTheme,
      visualDensity: VisualDensity.standard,
      focusColor: scheme.primary.withValues(alpha: 0.30),
      splashFactory: InkSparkle.splashFactory,
      scaffoldBackgroundColor: scheme.surface,
      appBarTheme: AppBarTheme(
        centerTitle: false,
        elevation: 0,
        scrolledUnderElevation: 2,
        backgroundColor: scheme.surface,
        foregroundColor: scheme.onSurface,
        titleTextStyle: textTheme.titleLarge?.copyWith(
          color: scheme.onSurface,
          fontWeight: FontWeight.w600,
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: ButtonStyle(
          minimumSize: const WidgetStatePropertyAll(Size(80, 52)),
          side: WidgetStateProperty.resolveWith(
            (states) => BorderSide(
              color: states.contains(WidgetState.focused)
                  ? scheme.primary
                  : Colors.transparent,
              width: 3,
            ),
          ),
        ),
      ),
      iconButtonTheme: IconButtonThemeData(
        style: ButtonStyle(
          minimumSize: const WidgetStatePropertyAll(Size(52, 52)),
          side: WidgetStateProperty.resolveWith(
            (states) => BorderSide(
              color: states.contains(WidgetState.focused)
                  ? scheme.primary
                  : Colors.transparent,
              width: 3,
            ),
          ),
        ),
      ),
      cardTheme: CardThemeData(
        elevation: 0,
        color: scheme.surfaceContainerLow,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        margin: EdgeInsets.zero,
      ),
      listTileTheme: const ListTileThemeData(
        contentPadding: EdgeInsets.symmetric(horizontal: 16, vertical: 2),
        minLeadingWidth: 28,
      ),
      filledButtonTheme: FilledButtonThemeData(
        style:
            FilledButton.styleFrom(
              minimumSize: const Size(80, 52),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(14),
              ),
            ).copyWith(
              side: WidgetStateProperty.resolveWith(
                (states) => BorderSide(
                  color: states.contains(WidgetState.focused)
                      ? scheme.onPrimary
                      : Colors.transparent,
                  width: 3,
                ),
              ),
            ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style:
            OutlinedButton.styleFrom(
              minimumSize: const Size(80, 52),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(14),
              ),
            ).copyWith(
              side: WidgetStateProperty.resolveWith(
                (states) => BorderSide(
                  color: states.contains(WidgetState.focused)
                      ? scheme.primary
                      : scheme.outline,
                  width: states.contains(WidgetState.focused) ? 3 : 1,
                ),
              ),
            ),
      ),
      segmentedButtonTheme: SegmentedButtonThemeData(
        style: ButtonStyle(
          visualDensity: VisualDensity.standard,
          shape: WidgetStatePropertyAll(
            RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          ),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: scheme.surfaceContainerHighest.withValues(alpha: 0.55),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: BorderSide.none,
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: BorderSide(color: scheme.primary, width: 3),
        ),
      ),
    );
  }
}
