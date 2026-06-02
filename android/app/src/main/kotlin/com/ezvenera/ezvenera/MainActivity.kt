package com.ezvenera.ezvenera

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.KeyEvent
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

/**
 * Flutter host activity for EZVenera.
 *
 * Besides the standard Flutter wiring, this activity owns the native side of
 * the volume-key page turning feature exposed at the `ezvenera/volume`
 * event channel. The handshake mirrors venera's original implementation:
 *
 *   - Dart subscribes -> we flip `listening = true`.
 *   - onKeyDown(VOLUME_UP) sends `1`, onKeyDown(VOLUME_DOWN) sends `2`.
 *   - Dart cancels -> `listening = false` and the keys fall through to
 *     the system so the media volume still works outside the reader.
 */
class MainActivity : FlutterFragmentActivity() {

    private var volumeEventSink: EventChannel.EventSink? = null
    private var pendingDirectoryResult: MethodChannel.Result? = null
    private var pendingStorageAccessResult: MethodChannel.Result? = null
    private var listening = false

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, VOLUME_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    volumeEventSink = events
                    listening = true
                }

                override fun onCancel(arguments: Any?) {
                    volumeEventSink = null
                    listening = false
                }
            })

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, DIRECTORY_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "ensureStorageAccess" -> ensureStorageAccess(result)
                    "pickDirectory" -> pickDirectory(result)
                    "openDirectory" -> {
                        val path = call.arguments as? String
                        result.success(path != null && openDirectory(path))
                    }
                    else -> result.notImplemented()
                }
            }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == DIRECTORY_REQUEST_CODE) {
            val result = pendingDirectoryResult
            pendingDirectoryResult = null
            if (result == null) {
                super.onActivityResult(requestCode, resultCode, data)
                return
            }

            if (resultCode != Activity.RESULT_OK) {
                result.success(null)
                return
            }

            val uri = data?.data
            if (uri == null) {
                result.success(null)
                return
            }

            val flags = data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: SecurityException) {
                // Some file managers return a tree URI without persistable grants.
            }

            val path = treeUriToPath(uri)
            if (path == null) {
                result.error(
                    "unsupported_directory",
                    "Only primary shared-storage folders can be used as a download directory.",
                    uri.toString(),
                )
            } else {
                result.success(path)
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            val result = pendingStorageAccessResult
            pendingStorageAccessResult = null
            result?.success(hasStorageAccess())
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (listening) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    volumeEventSink?.success(1)
                    return true
                }

                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    volumeEventSink?.success(2)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun pickDirectory(result: MethodChannel.Result) {
        if (pendingDirectoryResult != null) {
            result.error("busy", "A directory picker is already open.", null)
            return
        }
        pendingDirectoryResult = result
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, DIRECTORY_REQUEST_CODE)
        } catch (error: ActivityNotFoundException) {
            pendingDirectoryResult = null
            result.error("picker_unavailable", error.message, null)
        }
    }

    private fun ensureStorageAccess(result: MethodChannel.Result) {
        if (hasStorageAccess()) {
            result.success(true)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    },
                )
            } catch (_: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            result.success(false)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (pendingStorageAccessResult != null) {
                result.error("busy", "A storage permission request is already open.", null)
                return
            }
            pendingStorageAccessResult = result
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                STORAGE_PERMISSION_REQUEST_CODE,
            )
            return
        }
        result.success(true)
    }

    private fun hasStorageAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun openDirectory(path: String): Boolean {
        val documentUri = pathToPrimaryDocumentUri(path)
        if (documentUri == null) {
            return false
        }
        val candidates = buildList {
            add(Intent(Intent.ACTION_VIEW).apply {
                data = documentUri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            })
            add(Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("content://com.android.externalstorage.documents/root/primary")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        for (intent in candidates) {
            try {
                startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }

    private fun pathToPrimaryDocumentUri(path: String): Uri? {
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath
        if (path != root && !path.startsWith("$root/")) {
            return null
        }
        val relative = path.removePrefix(root).trimStart('/')
        val documentId = if (relative.isEmpty()) "primary:" else "primary:$relative"
        return DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            documentId,
        )
    }

    private fun treeUriToPath(uri: Uri): String? {
        if (!DocumentsContract.isTreeUri(uri)) {
            return null
        }
        val treeDocumentId = DocumentsContract.getTreeDocumentId(uri)
        val parts = treeDocumentId.split(":", limit = 2)
        if (parts.isEmpty() || parts[0] != "primary") {
            return null
        }
        val relative = parts.getOrNull(1).orEmpty()
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath
        return if (relative.isEmpty()) root else "$root/$relative"
    }

    companion object {
        private const val VOLUME_CHANNEL = "ezvenera/volume"
        private const val DIRECTORY_CHANNEL = "ezvenera/directory"
        private const val DIRECTORY_REQUEST_CODE = 14021
        private const val STORAGE_PERMISSION_REQUEST_CODE = 14022
    }
}
