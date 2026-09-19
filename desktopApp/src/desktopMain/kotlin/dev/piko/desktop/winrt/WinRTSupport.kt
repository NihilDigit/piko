package dev.piko.desktop.winrt

import androidx.compose.ui.graphics.Color
import io.github.composefluent.winrt.runtime.RuntimeScope
import windows.data.xml.dom.XmlDocument
import windows.system.display.DisplayRequest
import windows.ui.notifications.ToastNotification
import windows.ui.notifications.ToastNotificationManager
import windows.ui.viewmanagement.UIColorType
import windows.ui.viewmanagement.UISettings
import java.io.File

object WinRTSupport {
    val isWindows: Boolean = System.getProperty("os.name").contains("Windows", ignoreCase = true)

    fun getSystemAccentColor(): Color? {
        if (!isWindows) return null
        return runCatching {
            RuntimeScope.initializeSingleThreaded().use {
                val settings = UISettings()
                val color = settings.getColorValue(UIColorType.Accent)
                Color(
                    red = (color.r.toInt() and 0xFF) / 255f,
                    green = (color.g.toInt() and 0xFF) / 255f,
                    blue = (color.b.toInt() and 0xFF) / 255f,
                    alpha = 1f,
                )
            }
        }.getOrNull()
    }

    fun isSystemInDarkMode(): Boolean {
        if (!isWindows) return false
        return runCatching {
            RuntimeScope.initializeSingleThreaded().use {
                val settings = UISettings()
                val bg = settings.getColorValue(UIColorType.Background)
                val r = bg.r.toInt() and 0xFF
                val g = bg.g.toInt() and 0xFF
                val b = bg.b.toInt() and 0xFF
                val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
                luminance < 0.5
            }
        }.getOrDefault(false)
    }

    fun showNotification(title: String, message: String) {
        if (!isWindows) return
        runCatching {
            RuntimeScope.initializeSingleThreaded().use {
                val xml = XmlDocument()
                val escapedTitle = escapeXml(title)
                val escapedMessage = escapeXml(message)
                xml.loadXml(
                    """
                    <toast>
                        <visual>
                            <binding template="ToastGeneric">
                                <text>$escapedTitle</text>
                                <text>$escapedMessage</text>
                            </binding>
                        </visual>
                    </toast>
                    """.trimIndent(),
                )
                val toast = ToastNotification(xml)
                ToastNotificationManager.Metadata.createToastNotifier("Piko").show(toast)
            }
        }
    }

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    fun createDisplayRequest(): AutoCloseable? {
        if (!isWindows) return null
        return runCatching {
            RuntimeScope.initializeSingleThreaded().use {
                val request = DisplayRequest()
                request.requestActive()
                AutoCloseable {
                    runCatching {
                        RuntimeScope.initializeSingleThreaded().use {
                            request.requestRelease()
                        }
                    }
                }
            }
        }.getOrNull()
    }

    fun openFolder(folder: File) {
        if (!folder.exists()) folder.mkdirs()
        runCatching {
            if (isWindows) {
                RuntimeScope.initializeSingleThreaded().use {
                    val uri = windows.foundation.Uri("file:///" + folder.absolutePath.replace('\\', '/'))
                    windows.system.Launcher.Metadata.launchUriAsync(uri)
                }
            } else {
                java.awt.Desktop.getDesktop().open(folder)
            }
        }.onFailure {
            runCatching { java.awt.Desktop.getDesktop().open(folder) }
        }
    }

    fun openFile(file: File) {
        if (!file.exists()) return
        runCatching {
            if (isWindows) {
                RuntimeScope.initializeSingleThreaded().use {
                    val uri = windows.foundation.Uri("file:///" + file.absolutePath.replace('\\', '/'))
                    windows.system.Launcher.Metadata.launchUriAsync(uri)
                }
            } else {
                java.awt.Desktop.getDesktop().open(file)
            }
        }.onFailure {
            runCatching { java.awt.Desktop.getDesktop().open(file) }
        }
    }
}
