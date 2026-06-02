import 'dart:async';
import 'dart:io';
import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:window_manager/window_manager.dart';

import 'src/app.dart';
import 'src/logging/app_logger.dart';
import 'src/state/app_state_controller.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  try {
    await AppLogger.instance.initialize();
  } catch (_) {
    // The app should still start if the diagnostics log cannot be opened.
  }
  FlutterError.onError = (details) {
    FlutterError.presentError(details);
    unawaited(
      AppLogger.instance.error(
        'Flutter error',
        details.exception,
        details.stack,
      ),
    );
  };
  PlatformDispatcher.instance.onError = (error, stackTrace) {
    unawaited(AppLogger.instance.error('Platform error', error, stackTrace));
    return false;
  };
  if (Platform.isWindows) {
    await windowManager.ensureInitialized();
    await AppStateController.instance.initialize();
  }
  runApp(const EZVeneraApp());
  if (Platform.isWindows) {
    windowManager.waitUntilReadyToShow().then((_) async {
      await windowManager.setTitleBarStyle(TitleBarStyle.hidden);
      await windowManager.setMinimumSize(const Size(720, 560));
      final windowState = AppStateController.instance.getSection(
        'window.bounds',
      );
      final width = (windowState['width'] as num?)?.toDouble();
      final height = (windowState['height'] as num?)?.toDouble();
      if (width != null && height != null && width >= 720 && height >= 560) {
        await windowManager.setSize(Size(width, height));
      }
      await windowManager.show();
      await windowManager.focus();
    });
  }
}
