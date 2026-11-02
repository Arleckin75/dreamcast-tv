import 'package:dream_cast/app/bootstrap/app_bootstrap.dart';
import 'package:dream_cast/app/bootstrap/dream_cast_app.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter/services.dart';

Future<void> main() async {
  final bootstrap = await AppBootstrap.start();
  await SystemChrome.setPreferredOrientations([
    DeviceOrientation.landscapeLeft,
    DeviceOrientation.landscapeRight,
  ]);
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);

  runApp(DreamCastApp(bootstrap: bootstrap));
}
