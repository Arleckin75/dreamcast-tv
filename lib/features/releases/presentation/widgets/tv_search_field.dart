import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart' show PlatformViewHitTestBehavior;
import 'package:flutter/services.dart';

/// Inline Android editor for old ATV: Android owns IME input and remote events.
class TvSearchField extends StatefulWidget {
  const TvSearchField({
    required this.controller,
    required this.focusNode,
    required this.onSubmitted,
    required this.onResults,
    super.key,
  });
  final TextEditingController controller;
  final FocusNode focusNode;
  final ValueChanged<String> onSubmitted;
  final VoidCallback onResults;
  @override
  State<TvSearchField> createState() => _TvSearchFieldState();
}

class _TvSearchFieldState extends State<TvSearchField> {
  MethodChannel? _channel;
  bool _nativeReady = false;
  bool _syncing = false;
  void _focusEditor() {
    if (_nativeReady) unawaited(_channel?.invokeMethod<void>('focus'));
  }

  Map<String, int> get _colors {
    final colors = Theme.of(context).colorScheme;
    return {
      'accent': colors.primary.toARGB32(),
      'foreground': colors.onSurface.toARGB32(),
      'hintColor': colors.onSurfaceVariant.toARGB32(),
    };
  }

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_sendText);
  }

  void _sendText() {
    if (!_syncing) {
      unawaited(_channel?.invokeMethod<void>('text', widget.controller.text));
    }
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    unawaited(_channel?.invokeMethod<void>('colors', _colors));
  }

  @override
  Widget build(BuildContext context) => SizedBox(
    height: 56,
    child: Focus(
      focusNode: widget.focusNode,
      onFocusChange: (focused) {
        if (focused) _focusEditor();
      },
      onKeyEvent: (_, event) {
        // The platform view may not own Android focus yet. Activate it from
        // Flutter instead of forwarding OK to an unfocused native editor.
        if (event.logicalKey == LogicalKeyboardKey.select ||
            event.logicalKey == LogicalKeyboardKey.enter ||
            event.logicalKey == LogicalKeyboardKey.numpadEnter) {
          if (event is KeyDownEvent) {
            _focusEditor();
          }
          return KeyEventResult.handled;
        }
        const keys = [
          LogicalKeyboardKey.arrowUp,
          LogicalKeyboardKey.arrowDown,
          LogicalKeyboardKey.arrowLeft,
          LogicalKeyboardKey.arrowRight,
          LogicalKeyboardKey.select,
          LogicalKeyboardKey.enter,
          LogicalKeyboardKey.numpadEnter,
        ];
        return keys.contains(event.logicalKey)
            ? KeyEventResult.skipRemainingHandlers
            : KeyEventResult.ignored;
      },
      child: PlatformViewLink(
        viewType: 'dream_cast/tv_search_field',
        surfaceFactory: (_, controller) => AndroidViewSurface(
          controller: controller as AndroidViewController,
          hitTestBehavior: PlatformViewHitTestBehavior.opaque,
          gestureRecognizers: const {},
        ),
        onCreatePlatformView: (params) {
          final channel = MethodChannel(
            'dream_cast/tv_search_field/${params.id}',
          );
          _channel = channel;
          channel.setMethodCallHandler((call) async {
            if (!mounted) return;
            switch (call.method) {
              case 'changed':
                final text = call.arguments as String;
                _syncing = true;
                widget.controller.value = TextEditingValue(
                  text: text,
                  selection: TextSelection.collapsed(offset: text.length),
                );
                _syncing = false;
              case 'submitted':
                widget.onSubmitted(call.arguments as String);
              case 'results':
                widget.onResults();
            }
          });
          final controller = PlatformViewsService.initExpensiveAndroidView(
            id: params.id,
            viewType: params.viewType,
            layoutDirection: Directionality.of(context),
            creationParams: {
              'text': widget.controller.text,
              'hint': 'Название релиза',
              ..._colors,
            },
            creationParamsCodec: const StandardMessageCodec(),
            onFocus: () => params.onFocusChanged(true),
          );
          controller.addOnPlatformViewCreatedListener((id) {
            _nativeReady = true;
            params.onPlatformViewCreated(id);
            // Creation precedes the frame that installs AndroidViewSurface.
            // Focusing during creation lets Flutter's placeholder take it back.
            WidgetsBinding.instance.addPostFrameCallback((_) {
              if (mounted && widget.focusNode.hasFocus) {
                _focusEditor();
              }
            });
          });
          return controller;
        },
      ),
    ),
  );
  @override
  void dispose() {
    widget.controller.removeListener(_sendText);
    _channel?.setMethodCallHandler(null);
    super.dispose();
  }
}
