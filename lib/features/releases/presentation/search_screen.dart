import 'package:dream_cast/app/widgets/app_empty_state.dart';
import 'package:dream_cast/app/widgets/app_error_view.dart';
import 'package:dream_cast/features/releases/domain/release.dart';
import 'package:dream_cast/features/releases/presentation/release_list_providers.dart';
import 'package:dream_cast/features/releases/presentation/widgets/release_card.dart';
import 'package:dream_cast/features/releases/presentation/widgets/stale_cache_banner.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

class SearchScreen extends ConsumerStatefulWidget {
  const SearchScreen({super.key});

  @override
  ConsumerState<SearchScreen> createState() => _SearchScreenState();
}

class _SearchScreenState extends ConsumerState<SearchScreen> {
  final _controller = TextEditingController();
  String _lastQuery = '';
  late final FocusNode _focusNode;
  late final FocusNode _firstResultFocusNode;

  void _openKeyboard() {
    if (!mounted) return;
    _focusNode.requestFocus();
    // Focus may already be on the field after Android has closed its IME.
    // Request the IME explicitly after EditableText attaches its input client.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted && _focusNode.hasFocus) {
        SystemChannels.textInput.invokeMethod<void>('TextInput.show');
      }
    });
    WidgetsBinding.instance.ensureVisualUpdate();
  }

  KeyEventResult _handleSearchKey(FocusNode node, KeyEvent event) {
    if (event is! KeyDownEvent) return KeyEventResult.ignored;
    final key = event.logicalKey;
    if (key == LogicalKeyboardKey.select ||
        key == LogicalKeyboardKey.enter ||
        key == LogicalKeyboardKey.numpadEnter) {
      _openKeyboard();
      return KeyEventResult.handled;
    }
    if (key != LogicalKeyboardKey.arrowDown ||
        View.of(context).viewInsets.bottom > 0) {
      return KeyEventResult.ignored;
    }
    if (!_firstResultFocusNode.canRequestFocus ||
        _firstResultFocusNode.context == null) {
      return KeyEventResult.ignored;
    }
    // Remove the Android TV IME before transferring D-pad focus to results.
    SystemChannels.textInput.invokeMethod<void>('TextInput.hide');
    _firstResultFocusNode.requestFocus();
    return KeyEventResult.handled;
  }

  KeyEventResult _handleFirstResultKey(FocusNode node, KeyEvent event) {
    if (event is KeyDownEvent &&
        event.logicalKey == LogicalKeyboardKey.arrowUp) {
      _openKeyboard();
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  }

  @override
  void initState() {
    super.initState();
    _focusNode = FocusNode(
      debugLabel: 'search-input',
      onKeyEvent: _handleSearchKey,
    );
    _firstResultFocusNode = FocusNode(
      debugLabel: 'first-search-result',
      onKeyEvent: _handleFirstResultKey,
    );
    _controller.addListener(() {
      // Moving the caret or reopening the IME must not restart the search.
      if (_controller.text == _lastQuery) return;
      _lastQuery = _controller.text;
      ref.read(searchReleasesProvider.notifier).setQuery(_controller.text);
    });
    WidgetsBinding.instance.addPostFrameCallback((_) => _openKeyboard());
  }

  @override
  void dispose() {
    _controller.dispose();
    _focusNode.dispose();
    _firstResultFocusNode.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(searchReleasesProvider);

    return Scaffold(
      appBar: AppBar(
        automaticallyImplyLeading: false,
        titleSpacing: 0,
        title: TextField(
          controller: _controller,
          focusNode: _focusNode,
          textInputAction: TextInputAction.search,
          decoration: const InputDecoration(
            hintText: 'Название релиза',
            border: InputBorder.none,
            filled: false,
          ),
          onSubmitted: (value) =>
              ref.read(searchReleasesProvider.notifier).submit(value),
        ),
        actions: [
          IconButton(
            tooltip: 'Открыть клавиатуру',
            onPressed: _openKeyboard,
            icon: const Icon(Icons.keyboard_outlined),
          ),
          if (_controller.text.isNotEmpty)
            IconButton(
              tooltip: 'Очистить',
              onPressed: _controller.clear,
              icon: const Icon(Icons.close),
            ),
        ],
      ),
      body: state.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, stackTrace) => AppErrorView(error: error),
        data: (data) => _SearchBody(
          data: data,
          onOpenRelease: _openRelease,
          firstResultFocusNode: _firstResultFocusNode,
          onHistoryTap: (query) {
            _controller.text = query;
            _controller.selection = TextSelection.collapsed(
              offset: query.length,
            );
            ref.read(searchReleasesProvider.notifier).submit(query);
          },
          onHistoryRemove: ref
              .read(searchReleasesProvider.notifier)
              .removeHistoryItem,
        ),
      ),
    );
  }

  void _openRelease(DreamRelease release) {
    context.push('/release/${release.id}', extra: release);
  }
}

class _SearchBody extends StatelessWidget {
  const _SearchBody({
    required this.data,
    required this.onOpenRelease,
    required this.firstResultFocusNode,
    required this.onHistoryTap,
    required this.onHistoryRemove,
  });

  final SearchState data;
  final ValueChanged<DreamRelease> onOpenRelease;
  final FocusNode firstResultFocusNode;
  final ValueChanged<String> onHistoryTap;
  final ValueChanged<String> onHistoryRemove;

  @override
  Widget build(BuildContext context) {
    if (data.query.trim().length < 2) {
      if (data.history.isEmpty) {
        return const AppEmptyState(
          icon: Icons.search,
          title: 'Введите название',
          message: 'Поиск начнётся после двух символов.',
        );
      }
      return ListView(
        padding: const EdgeInsets.only(top: 8),
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 10, 16, 8),
            child: Text(
              'Недавние запросы',
              style: Theme.of(
                context,
              ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700),
            ),
          ),
          for (final query in data.history)
            ListTile(
              leading: const Icon(Icons.history),
              title: Text(query),
              onTap: () => onHistoryTap(query),
              trailing: IconButton(
                tooltip: 'Удалить из истории',
                onPressed: () => onHistoryRemove(query),
                icon: const Icon(Icons.close),
              ),
            ),
        ],
      );
    }

    if (data.error != null) {
      return AppErrorView(error: data.error!);
    }

    if (data.isSearching && data.items.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }

    if (data.items.isEmpty) {
      return const AppEmptyState(
        icon: Icons.manage_search,
        title: 'Ничего не найдено',
        message: 'Попробуйте другое название или проверьте раскладку.',
      );
    }

    final width = MediaQuery.sizeOf(context).width;
    final crossAxisCount = switch (width) {
      < 420 => 3,
      < 700 => 4,
      < 1000 => 5,
      _ => 6,
    };

    return Column(
      children: [
        if (data.isStale) const StaleCacheBanner(),
        if (data.isSearching) const LinearProgressIndicator(minHeight: 2),
        Expanded(
          child: GridView.builder(
            padding: const EdgeInsets.fromLTRB(10, 8, 10, 24),
            itemCount: data.items.length,
            gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: crossAxisCount,
              mainAxisSpacing: 8,
              crossAxisSpacing: 6,
              childAspectRatio: 0.49,
            ),
            itemBuilder: (context, index) {
              final release = data.items[index];
              return ReleaseCard(
                release: release,
                focusNode: index == 0 ? firstResultFocusNode : null,
                onTap: () => onOpenRelease(release),
              );
            },
          ),
        ),
      ],
    );
  }
}
