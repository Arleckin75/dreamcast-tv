import 'package:dream_cast/app/bootstrap/app_bootstrap.dart';
import 'package:dream_cast/app/bootstrap/dream_cast_app.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter/services.dart';
import 'package:dream_cast/core/logging/performance_trace.dart';

Future<void> main() async {
  final firstFrame = PerformanceTrace('startup.first_framework_frame');
  final bootstrap = await AppBootstrap.start();
  await SystemChrome.setPreferredOrientations([
    DeviceOrientation.landscapeLeft,
    DeviceOrientation.landscapeRight,
  ]);
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);

  WidgetsBinding.instance.addPostFrameCallback((_) => firstFrame.finish());
  runApp(DreamCastApp(bootstrap: bootstrap));
}
