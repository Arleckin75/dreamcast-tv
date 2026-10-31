import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// Impeller is configured before Flutter Engine creation, so a full process
/// restart is required. A cancelled dialog never modifies the stored setting.
class ImpellerSettingsTile extends StatefulWidget {
  const ImpellerSettingsTile({super.key});

  @override
  State<ImpellerSettingsTile> createState() => _ImpellerSettingsTileState();
}

class _ImpellerSettingsTileState extends State<ImpellerSettingsTile> {
  static const _channel = MethodChannel('dream_cast/renderer_settings');
  bool? _enabled;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final value = await _channel.invokeMethod<bool>('getImpellerEnabled');
      if (mounted) setState(() => _enabled = value ?? false);
    } on PlatformException {
      if (mounted) setState(() => _enabled = null);
    } on MissingPluginException {
      if (mounted) setState(() => _enabled = null);
    }
  }

  Future<void> _requestChange(bool newValue) async {
    if (_saving || _enabled == newValue) return;
    final restart = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Перезапустить Dream Cast?'),
        content: Text(newValue
            ? 'Для включения Impeller необходимо перезапустить приложение.'
            : 'Для отключения Impeller необходимо перезапустить приложение.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('Отмена'),
          ),
          FilledButton(
            autofocus: true,
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('Перезапустить приложение'),
          ),
        ],
      ),
    );
    if (!mounted || restart != true) return;

    setState(() => _saving = true);
    try {
      await _channel.invokeMethod<void>('setImpellerEnabled', {'enabled': newValue});
      if (!mounted) return;
      setState(() => _enabled = newValue);
      await _channel.invokeMethod<void>('restartForRendererChange');
    } on PlatformException catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text('Не удалось применить настройку: ${error.message ?? error.code}'),
        ));
      }
    } on MissingPluginException {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Перезапуск недоступен на этом устройстве')),
        );
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_enabled == null) {
      return const ListTile(
        leading: Icon(Icons.memory_outlined),
        title: Text('Impeller (графический движок)'),
        subtitle: Text('Настройка доступна в Android-приложении'),
      );
    }
    return SwitchListTile(
      secondary: const Icon(Icons.memory_outlined),
      title: const Text('Impeller (графический движок)'),
      subtitle: Text(_enabled! ? 'Включён' : 'Выключен (Skia)'),
      value: _enabled!,
      onChanged: _saving ? null : _requestChange,
    );
  }
}
