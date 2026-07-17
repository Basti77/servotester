package de.pflueger.servotester.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a downloaded APK to the system package installer. Requires the
 * REQUEST_INSTALL_PACKAGES permission (declared in the manifest); on Android O+
 * the user must also have granted "install unknown apps" for this app — if not,
 * we bounce them to that settings page instead of failing silently.
 */
class ApkInstaller(private val context: Context) {

    /** @return null on success (installer launched), or a user-facing message. */
    fun install(apk: ByteArray): String? {
        // Android O+: without the per-app "unknown sources" grant the installer
        // just refuses — send the user to enable it first.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return "Bitte „Unbekannte Apps installieren“ für ServoTester erlauben und den Download erneut starten."
        }

        val file = File(context.cacheDir, "servotester-update.apk")
        return try {
            file.writeBytes(apk)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            null
        } catch (e: Exception) {
            "Installer ließ sich nicht starten: ${e.message}"
        }
    }
}
