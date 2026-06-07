import 'dart:io';

import 'package:file_selector/file_selector.dart';
import 'package:flutter/services.dart';
import 'package:url_launcher/url_launcher.dart';

class PlatformDirectory {
  const PlatformDirectory._();

  static const MethodChannel _channel = MethodChannel('ezvenera/directory');

  static bool get canPickDirectory => !Platform.isIOS;

  static bool get canOpenDirectory => !Platform.isIOS;

  static Future<String?> pickDirectory() async {
    if (!canPickDirectory) {
      return null;
    }
    if (Platform.isAndroid) {
      final canUseExternalStorage =
          await _channel.invokeMethod<bool>('ensureStorageAccess') ?? false;
      if (!canUseExternalStorage) {
        return null;
      }
      final path = await _channel.invokeMethod<String>('pickDirectory');
      if (path == null || path.trim().isEmpty) {
        return null;
      }
      return path;
    }
    return getDirectoryPath();
  }

  static Future<bool> openDirectory(String path) async {
    if (!canOpenDirectory) {
      return false;
    }
    if (Platform.isWindows) {
      await Process.start('explorer.exe', [path]);
      return true;
    }
    if (Platform.isAndroid) {
      return await _channel.invokeMethod<bool>('openDirectory', path) ?? false;
    }
    return launchUrl(Uri.directory(path), mode: LaunchMode.externalApplication);
  }
}
