package io.github.HerobrineFR.herobrinemodpackinstaller

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import com.formdev.flatlaf.themes.FlatMacDarkLaf
import com.formdev.flatlaf.themes.FlatMacLightLaf
import com.google.gson.JsonObject
import io.github.oshai.KotlinLogging
import io.github.HerobrineFR.herobrinemodpackinstaller.getVersionCheck
import java.awt.BorderLayout
import java.util.*
import javax.swing.*
import kotlin.concurrent.thread
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

val I18N = ResourceBundle.getBundle("i18n/lang", Locale.getDefault())!!

fun main() {
    logger.info("Herobrine.fr-Optimized Installer $VERSION")

    if (isDarkMode()) {
        if (operatingSystem == OperatingSystem.MACOS) {
            FlatMacDarkLaf.setup()
        } else {
            FlatDarkLaf.setup()
        }
    } else {
        if (operatingSystem == OperatingSystem.MACOS) {
            FlatMacLightLaf.setup()
        } else {
            FlatLightLaf.setup()
        }
    }

    val modpack = Modpack("herobrine.fr-modpack")
    var selectedPack = modpack

    var version_check = getVersionCheck()

    // verify that the version is posterior to the lower authorized version
    if (VERSION == "<<VERSION>>" || !(versionIsAnterior(version_check["lower_authorized_version"].asString, VERSION))) {
        val url = version_check["last_version"].asJsonObject["url"].asString
        val message = "<html>This version ($VERSION) of the installer is outdated (version posterior of ${version_check["lower_authorized_version"].asString} needed), please download the latest version at <a href=\"$url\">$url</a></html> (if the link is not clickable, you can also find it on our discord server)"
        JOptionPane.showMessageDialog(null, message, "Outdated version", JOptionPane.INFORMATION_MESSAGE)
        exitProcess(0)
    }

    SwingUtilities.invokeLater { JFrame(selectedPack.windowTitle).apply root@ {
        iconImage = selectedPack.image

        val iconLabel = JLabel(ImageIcon(selectedPack.image))

        val packVersion = JComboBox<String>()

        lateinit var setupMinecraftVersions: () -> Unit

        val minecraftVersion = JComboBox<String>().apply {
            addItemListener {
                val gameVersion = selectedItem as? String ?: return@addItemListener
                packVersion.removeAllItems()
                selectedPack.versions[gameVersion]
                    ?.keys
                    ?.forEach(packVersion::addItem)
                selectedPack.versions[gameVersion]
                    ?.entries
                    ?.first { it.value.data["featured"].asBoolean }
                    ?.let { packVersion.selectedItem = it.key }
            }
        }

        setupMinecraftVersions = {
            val mcVersion = minecraftVersion.selectedItem
            minecraftVersion.removeAllItems()
            selectedPack.versions
                .keys
                .asSequence()
                .forEach(minecraftVersion::addItem)
            if (mcVersion != null) {
                minecraftVersion.selectedItem = mcVersion
            }
        }
        setupMinecraftVersions()

        // val includeFeatures = JCheckBox(I18N.getString("include.non.performance.features")).apply {
        //     isSelected = true
        //     addActionListener {
        //         selectedPack = if (isSelected) additive else adrenaline
        //         title = selectedPack.windowTitle
        //         iconImage = selectedPack.image
        //         iconLabel.icon = ImageIcon(selectedPack.image)

        //         setupMinecraftVersions()
        //     }
        // }

        val installProgress = JProgressBar().apply {
            isStringPainted = true
        }

        lateinit var enableOptions: (Boolean) -> Unit

        val install = JButton(I18N.getString("install")).apply {
            addActionListener {
                enableOptions(false)
                val selectedMcVersion = minecraftVersion.selectedItem
                val selectedPackVersion = packVersion.selectedItem
                val destinationPath = Path(PackInstaller.DOT_MINECRAFT.toString() ).resolve(selectedPack.id)
                if (!destinationPath.isDirectory()) {
                    if (destinationPath.exists()) {
                        JOptionPane.showMessageDialog(
                            this@root,
                            I18N.getString("installation.dir.not.directory"),
                            title, JOptionPane.INFORMATION_MESSAGE
                        )
                    } else {
                        destinationPath.createDirectories()
                    }
                }
                thread(isDaemon = true, name = "InstallThread") {
                    val error = try {
                        selectedPack.versions[selectedMcVersion]
                            ?.get(selectedPackVersion)
                            ?.install(destinationPath, JProgressBarProgressHandler(installProgress))
                            ?: throw IllegalStateException(
                                "Couldn't find pack version $selectedPackVersion for $selectedMcVersion"
                            )
                        null
                    } catch (t: Throwable) {
                        logger.error("Failed to install pack", t)
                        t
                    }
                    SwingUtilities.invokeLater {
                        enableOptions(true)
                        if (error == null) {
                            JOptionPane.showMessageDialog(
                                this@root,
                                I18N.getString("installation.success"),
                                title, JOptionPane.INFORMATION_MESSAGE
                            )
                        } else {
                            JOptionPane.showMessageDialog(
                                this@root,
                                "${I18N.getString("installation.failed")}\n${error.localizedMessage}",
                                title, JOptionPane.INFORMATION_MESSAGE
                            )
                        }
                    }
                }
            }
        }

        enableOptions = {
            // includeFeatures.isEnabled = it
            minecraftVersion.isEnabled = it
            packVersion.isEnabled = it
            install.isEnabled = it
        }

        contentPane = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            add(JPanel().apply {
                layout = BorderLayout()
                border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
                add(iconLabel, BorderLayout.PAGE_START)
            })
            add(Box.createVerticalStrut(15))
            // add(includeFeatures.withLabel())
            add(Box.createVerticalStrut(15))
            add(minecraftVersion.withLabel(I18N.getString("minecraft.version")))
            add(Box.createVerticalStrut(15))
            add(packVersion.withLabel(I18N.getString("pack.version")))
            add(Box.createVerticalStrut(15))
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
                border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
                add(JPanel().apply {
                    layout = BorderLayout()
                    add(install)
                })
                add(Box.createVerticalStrut(10))
                add(installProgress)
            })
        }

        isResizable = false

        pack()
        defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        setLocationRelativeTo(null)
        isVisible = true
    } }
}
