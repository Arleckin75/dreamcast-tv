import 'dart:async';

import 'package:flutter/material.dart';

enum EpisodeCompletionAction { next, exit }

/// The same completion flow is used after ExoPlayer and the native VLC Activity.
class EpisodeCompletionDialog extends StatefulWidget {
  const EpisodeCompletionDialog({this.nextTitle, super.key});

  final String? nextTitle;

  @override
  State<EpisodeCompletionDialog> createState() =>
      _EpisodeCompletionDialogState();
}

class _EpisodeCompletionDialogState extends State<EpisodeCompletionDialog>
    with WidgetsBindingObserver {
  final _nextFocus = FocusNode();
  Timer? _timer;
  int _remaining = 10;
  bool _cancelled = false;
  bool _finished = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    if (WidgetsBinding.instance.lifecycleState == null ||
        WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed) {
      _startCountdown();
    }
  }

  void _startCountdown() {
    if (widget.nextTitle != null &&
        _timer == null &&
        !_cancelled &&
        !_finished) {
      _timer = Timer.periodic(const Duration(seconds: 1), (timer) {
        if (!mounted || _finished || _cancelled) return;
        final remaining = (10 - timer.tick).clamp(0, 10);
        if (remaining == 0) {
          _finish(EpisodeCompletionAction.next);
        } else {
          setState(() => _remaining = remaining);
        }
      });
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _startCountdown();
    } else if (_timer != null && widget.nextTitle != null) {
      _cancel(requestFocus: false);
    }
  }

  void _cancel({bool requestFocus = true}) {
    if (!mounted || _finished || _cancelled) return;
    _timer?.cancel();
    setState(() => _cancelled = true);
    if (requestFocus) _nextFocus.requestFocus();
  }

  void _finish(EpisodeCompletionAction action) {
    if (!mounted || _finished) return;
    _finished = true;
    _timer?.cancel();
    Navigator.of(context).pop(action);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _timer?.cancel();
    _nextFocus.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final hasNext = widget.nextTitle != null;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (didPop) return;
        if (hasNext && !_cancelled) {
          _cancel();
        } else {
          _finish(EpisodeCompletionAction.exit);
        }
      },
      child: AlertDialog(
        title: const Text('Серия закончилась'),
        content: Text(
          !hasNext
              ? 'Это последняя доступная серия.'
              : _cancelled
              ? 'Автопереход отменён.\n${widget.nextTitle}'
              : '${widget.nextTitle}\nПереход через $_remaining с',
        ),
        actions: [
          if (hasNext)
            TextButton(
              autofocus: true,
              onPressed: _cancelled ? null : _cancel,
              child: Text(_cancelled ? 'Переход отменён' : 'Отменить переход'),
            ),
          TextButton(
            autofocus: !hasNext,
            onPressed: () => _finish(EpisodeCompletionAction.exit),
            child: const Text('К тайтлу'),
          ),
          if (hasNext)
            FilledButton(
              focusNode: _nextFocus,
              onPressed: () => _finish(EpisodeCompletionAction.next),
              child: const Text('Следующая серия'),
            ),
        ],
      ),
    );
  }
}
