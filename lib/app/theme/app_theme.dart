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

    final buttonStyle = ButtonStyle(
      minimumSize: const WidgetStatePropertyAll(Size(80, 52)),
      elevation: const WidgetStatePropertyAll(0),
      backgroundColor: const WidgetStatePropertyAll(Colors.transparent),
      surfaceTintColor: const WidgetStatePropertyAll(Colors.transparent),
      shadowColor: const WidgetStatePropertyAll(Colors.transparent),
      foregroundColor: WidgetStateProperty.resolveWith(
        (states) => states.contains(WidgetState.disabled)
            ? scheme.onSurface.withValues(alpha: 0.38)
            : scheme.primary,
      ),
      overlayColor: WidgetStateProperty.resolveWith(
        (states) => states.contains(WidgetState.disabled)
            ? Colors.transparent
            : scheme.primary.withValues(
                alpha: states.contains(WidgetState.pressed) ? 0.12 : 0.08,
              ),
      ),
      shape: const WidgetStatePropertyAll(StadiumBorder()),
      side: WidgetStateProperty.resolveWith(
        (states) => BorderSide(
          color:
              states.contains(WidgetState.focused) &&
                  !states.contains(WidgetState.disabled)
              ? scheme.primary
              : Colors.transparent,
          width: 3,
        ),
      ),
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
      textButtonTheme: TextButtonThemeData(style: buttonStyle),
      filledButtonTheme: FilledButtonThemeData(style: buttonStyle),
      outlinedButtonTheme: OutlinedButtonThemeData(style: buttonStyle),
      elevatedButtonTheme: ElevatedButtonThemeData(style: buttonStyle),
      iconButtonTheme: IconButtonThemeData(
        style: buttonStyle.copyWith(
          minimumSize: const WidgetStatePropertyAll(Size(52, 52)),
          shape: const WidgetStatePropertyAll(CircleBorder()),
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
      segmentedButtonTheme: SegmentedButtonThemeData(style: buttonStyle),
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
