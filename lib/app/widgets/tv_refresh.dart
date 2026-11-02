import 'package:flutter/material.dart';

/// Refresh is an explicit remote action rather than a touch gesture.
class TvRefresh extends StatefulWidget {
  const TvRefresh({required this.onRefresh, required this.child, super.key});
  final Future<void> Function() onRefresh;
  final Widget child;
  @override
  State<TvRefresh> createState() => _TvRefreshState();
}

class _TvRefreshState extends State<TvRefresh> {
  bool _refreshing = false;
  Future<void> _refresh() async {
    if (_refreshing) return;
    setState(() => _refreshing = true);
    try {
      await widget.onRefresh();
    } finally {
      if (mounted) setState(() => _refreshing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Align(
          alignment: Alignment.centerRight,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: TextButton.icon(
              onPressed: _refreshing ? null : _refresh,
              icon: const Icon(Icons.refresh),
              label: Text(_refreshing ? 'Обновление…' : 'Обновить'),
            ),
          ),
        ),
        Expanded(child: widget.child),
      ],
    );
  }
}
