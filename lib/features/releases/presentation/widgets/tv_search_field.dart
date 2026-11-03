import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart' show PlatformViewHitTestBehavior;
import 'package:flutter/services.dart';

/// Inline Android editor for Cosmos: Android owns IME input and remote events.
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
  bool _syncing = false;
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
        if (focused) unawaited(_channel?.invokeMethod<void>('focus'));
      },
      onKeyEvent: (_, event) {
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
            params.onPlatformViewCreated(id);
            if (mounted && widget.focusNode.hasFocus) {
              unawaited(channel.invokeMethod<void>('focus'));
            }
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
