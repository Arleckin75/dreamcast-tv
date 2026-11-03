import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// A centered dialog with remote focus contained in the dialog.
Future<T?> showTvDialog<T>({
  required BuildContext context,
  required WidgetBuilder builder,
}) {
  return showDialog<T>(
    context: context,
    requestFocus: true,
    traversalEdgeBehavior: TraversalEdgeBehavior.closedLoop,
    builder: (context) => _TvDialog(builder: builder),
  );
}

class _TvDialog extends StatefulWidget {
  const _TvDialog({required this.builder});
  final WidgetBuilder builder;
  @override
  State<_TvDialog> createState() => _TvDialogState();
}

class _TvDialogState extends State<_TvDialog> {
  final _scope = FocusScopeNode(debugLabel: 'tv-dialog');
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && _scope.focusedChild == null) _scope.nextFocus();
    });
  }

  @override
  void dispose() {
    _scope.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final size = MediaQuery.sizeOf(context);
    return Dialog(
      insetPadding: const EdgeInsets.symmetric(horizontal: 32, vertical: 24),
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxWidth: math.min(840, math.max(0, size.width - 64)),
          maxHeight: math.max(0, size.height - 48),
        ),
        child: FocusScope(
          node: _scope,
          autofocus: true,
          child: FocusTraversalGroup(
            policy: ReadingOrderTraversalPolicy(),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 16),
              child: widget.builder(context),
            ),
          ),
        ),
      ),
    );
  }
}

/// TV devices need not have a browser or a handler for social-network links.
Future<void> showTvLink(BuildContext context, String address) {
  return showTvDialog<void>(
    context: context,
    builder: (context) => SingleChildScrollView(
      padding: const EdgeInsets.symmetric(horizontal: 24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Ссылка', style: Theme.of(context).textTheme.headlineSmall),
          const SizedBox(height: 16),
          const Text('Откройте этот адрес на телефоне или компьютере:'),
          const SizedBox(height: 16),
          Text(address, textDirection: TextDirection.ltr),
          const SizedBox(height: 24),
          FilledButton(
            autofocus: true,
            onPressed: () => Navigator.pop(context),
            child: const Text('Закрыть'),
          ),
        ],
      ),
    ),
  );
}

/// Long read-only text remains scrollable with the remote.
class TvScrollableText extends StatefulWidget {
  const TvScrollableText(this.text, {super.key});
  final String text;
  @override
  State<TvScrollableText> createState() => _TvScrollableTextState();
}

class _TvScrollableTextState extends State<TvScrollableText> {
  final _controller = ScrollController();
  bool _focused = false;
  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Focus(
      autofocus: true,
      onFocusChange: (value) {
        if (mounted) setState(() => _focused = value);
      },
      onKeyEvent: (node, event) {
        if (!_controller.hasClients ||
            (event is! KeyDownEvent && event is! KeyRepeatEvent)) {
          return KeyEventResult.ignored;
        }
        final direction = event.logicalKey == LogicalKeyboardKey.arrowDown
            ? 1
            : event.logicalKey == LogicalKeyboardKey.arrowUp
            ? -1
            : 0;
        final position = _controller.position;
        if (direction == 0 ||
            (direction > 0 && position.extentAfter == 0) ||
            (direction < 0 && position.extentBefore == 0)) {
          return KeyEventResult.ignored;
        }
        _controller.animateTo(
          (position.pixels + direction * 160).clamp(
            0,
            position.maxScrollExtent,
          ),
          duration: const Duration(milliseconds: 100),
          curve: Curves.easeOut,
        );
        return KeyEventResult.handled;
      },
      child: DecoratedBox(
        decoration: BoxDecoration(
          border: Border.all(
            color: _focused
                ? Theme.of(context).colorScheme.primary
                : Colors.transparent,
            width: 3,
          ),
        ),
        child: Scrollbar(
          controller: _controller,
          thumbVisibility: true,
          child: SingleChildScrollView(
            controller: _controller,
            padding: const EdgeInsets.all(12),
            child: Text(widget.text),
          ),
        ),
      ),
    );
  }
}
