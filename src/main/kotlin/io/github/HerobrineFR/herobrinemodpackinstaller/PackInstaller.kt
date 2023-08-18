package io.github.HerobrineFR.herobrinemodpackinstaller

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.oshai.KotlinLogging
import io.github.z4kn4fein.semver.toVersion
import java.io.IOException
import java.net.URI
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.awt.BorderLayout
import kotlin.io.path.*
import javax.swing.*

private val logger = KotlinLogging.logger {}

class PackInstaller(
    private val packVersion: PackVersion, private val destination: Path, private  val progressHandler: ProgressHandler
) : AutoCloseable {
    companion object {
        val DOT_MINECRAFT = Path(
            when (operatingSystem) {
                OperatingSystem.WINDOWS -> "${System.getenv("APPDATA")}\\.minecraft"
                OperatingSystem.MACOS -> "${System.getProperty("user.home")}/Library/Application Support/minecraft"
                else -> "${System.getProperty("user.home")}/.minecraft"
            }
        )
        val VERSIONS = DOT_MINECRAFT / "versions"
        val LAUNCHER_PROFILES = DOT_MINECRAFT / "launcher_profiles.json"
        var INSTALLER_VERSION = "1"
    }

    private val jimfs = Jimfs.newFileSystem(Configuration.unix())!!
    private lateinit var zfs: FileSystem

    private lateinit var packIndex: JsonObject

    private val modsDir = DOT_MINECRAFT / packVersion.launcherFolderPath

    @OptIn(ExperimentalPathApi::class)
    private  fun writeVersionDir(clientJson: JsonObject) {
        progressHandler.newTask(I18N.getString("creating.version.folder"))
        val versionDir = VERSIONS / packVersion.launcherVersionId
        versionDir.deleteRecursively()
        versionDir.createDirectories()

        progressHandler.newTask(I18N.getString("writing.client.json"))
        versionDir.resolve("${packVersion.launcherVersionId}.json").writer().use(clientJson::writeTo)

        progressHandler.newTask(I18N.getString("writing.placeholder.client.jar"))
        versionDir.resolve("${packVersion.launcherVersionId}.jar").createFile()
    }

    private  fun updateLauncherProfiles() {
        progressHandler.newTask(I18N.getString("reading.launcher.profiles.json"))
        val launcherProfiles = LAUNCHER_PROFILES.reader().use(JsonParser::parseReader).asJsonObject

        progressHandler.newTask(I18N.getString("patching.launcher.profiles.json"))
        val profile = launcherProfiles["profiles"]
            .asJsonObject[packVersion.launcherProfileId]
            ?.asJsonObject
            ?: JsonObject()
        if ("created" !in profile) {
            profile["created"] = isoTime()
        }
        if (destination != DOT_MINECRAFT) {
            profile["gameDir"] = destination.toString()
        }
        if ("icon" !in profile) {
            packVersion.modpack.launcherIcon?.let { profile["icon"] = it }
        }
        profile["lastUsed"] = isoTime()
        if ("name" !in profile) {
            profile["name"] = I18N.getString("profile.name", packVersion.modpack.name, packVersion.gameVersion)
        }
        profile["lastVersionId"] = packVersion.launcherVersionId
        if ("type" !in profile) {
            profile["type"] = "custom"
        }
        launcherProfiles["profiles"].asJsonObject.add(packVersion.launcherProfileId, profile)

        progressHandler.newTask(I18N.getString("writing.launcher.profiles.json"))
        LAUNCHER_PROFILES.writer().use(launcherProfiles::writeTo)
    }

    private fun downloadPack() {
        progressHandler.newTaskSet(3)

        progressHandler.newTask(I18N.getString("downloading.pack"))
        val files = packVersion.data["files"].asJsonArray
        val file = files.asSequence()
            .map { it.asJsonObject }
            .firstOrNull { it["primary"].asBoolean }
            ?: files[0].asJsonObject
        val jfsPath = jimfs.getPath(file["filename"].asString)
        download(file, file["url"].asString, jfsPath)

        progressHandler.newTask(I18N.getString("opening.pack"))
        zfs = FileSystems.newFileSystem(URI("jar:${jfsPath.toUri()}!/"), mapOf<String, String>())

        progressHandler.newTask(I18N.getString("reading.index"))
        packIndex = zfs.getPath("modrinth.index.json").reader().use(JsonParser::parseReader).asJsonObject
        if (packIndex["dependencies"].asJsonObject["minecraft"].asString != packVersion.gameVersion) {
            throw IllegalStateException("Game version mismatch!")
        }
    }

    private fun installLoader() {
        val gameVersion = packVersion.gameVersion

        progressHandler.newTaskSet(8)

        val loaderVersion = packIndex["dependencies"].asJsonObject[packVersion.loader.dependencyName].asString
        logger.info("Using ${packVersion.loader.dependencyName} $loaderVersion")

        progressHandler.newTask(I18N.getString("downloading.client.json"))
        val clientJson = requestJson(
            "${packVersion.loader.apiRoot}/versions/loader/$gameVersion/$loaderVersion/profile/json"
        ).asJsonObject

        progressHandler.newTask(I18N.getString("patching.client.json"))
        clientJson["id"] = packVersion.launcherVersionId
        packVersion.loader.addMods(loaderVersion.toVersion()).let { (prefix, suffix) ->
            clientJson["arguments"]
                .asJsonObject
                .asMap()
                .getOrPut("jvm", ::JsonArray)
                .asJsonArray
                .add("-D$prefix=$modsDir$suffix")
        }
        if (packVersion.packVersion.toVersion() >= "1.15.9".toVersion()) {
            // HACK HACK HACK World Host has a bug where Java 17 is required
            clientJson["javaVersion"] = JsonObject().apply {
                this["component"] = "java-runtime-gamma"
                this["majorVersion"] = 17
            }
        }

        writeVersionDir(clientJson)
        updateLauncherProfiles()
    }

    @OptIn(ExperimentalPathApi::class)
    private fun installPack() {
        progressHandler.prepareNewTaskSet(I18N.getString("downloading.mods"))

        val files = packIndex["files"].asJsonArray
        modsDir.deleteRecursively()

        progressHandler.newTaskSet(files.size())

        var modrinth_api_base = "api.modrinth.com/v2/project/"

        val optionalFilesMap = mutableMapOf<String, String>()
        val optionalFilesMapPathKey = mutableMapOf<String, String>()
        files.asSequence().map(JsonElement::getAsJsonObject).forEach { file ->
            if (file.has("env") && file["env"].asJsonObject.has("client")) {
                val client = file["env"].asJsonObject["client"].asString
                if (client != "optional") {
                    return@forEach
                }
                val projectID = file["downloads"].asJsonArray[0].asString.split("/")[4]
                val project = requestJson("https://$modrinth_api_base$projectID").asJsonObject
                optionalFilesMapPathKey[file["path"].asString] = project["title"].asString
                optionalFilesMap[project["title"].asString] = file["path"].asString
            } else {
                return@forEach
            }
        }

        val optionalFilesToApply = optionalFilesMapPathKey.toMutableMap()

        JDialog().apply {
            isModal = true
            isResizable = false
            title = I18N.getString("optionals.window.title")
            defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE

            // List of checkboxes from optionalFiles
            val optionalFilesPanel = JPanel()
            optionalFilesPanel.layout = BoxLayout(optionalFilesPanel, BoxLayout.PAGE_AXIS)
            optionalFilesPanel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            for (file in optionalFilesMap.keys) {
                val checkBox = JCheckBox(file)
                checkBox.isSelected = false
                optionalFilesPanel.add(checkBox)
            }
            add(optionalFilesPanel)
            // Resume installation button
            val resumeButton = JButton(I18N.getString("resume.install"))
            resumeButton.addActionListener {
                // Remove unselected files from optionalFiles
                for (i in 0 until optionalFilesPanel.componentCount) {
                    val checkBox = optionalFilesPanel.getComponent(i) as JCheckBox
                    if (!checkBox.isSelected) {
                        optionalFilesToApply.remove(optionalFilesMap[checkBox.text])
                    }
                }
                // Close dialog
                isVisible = false
                dispose()
            }
            val iconLabel = JLabel(ImageIcon(packVersion.modpack.image))
            contentPane = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
                border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
                add(JPanel().apply {
                    layout = BorderLayout()
                    border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
                    add(iconLabel, BorderLayout.PAGE_START)
                })
                add(Box.createVerticalStrut(15))
                add(optionalFilesPanel)
                add(Box.createVerticalStrut(15))
                add(JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
                    border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
                    add(JPanel().apply {
                        layout = BorderLayout()
                        add(resumeButton)
                    })
                })
                add(Box.createVerticalStrut(10))
            }
            pack()
            setLocationRelativeTo(null)
            isVisible = true
        }

        files.asSequence().map(JsonElement::getAsJsonObject).forEach { file ->
            val path = file["path"].asString
            if (file.has("env") && file["env"].asJsonObject.has("client") && file["env"].asJsonObject["client"].asString == "optional") {
                if (!optionalFilesToApply.containsKey(path)) {
                    return@forEach
                }
            }
            progressHandler.newTask(I18N.getString("downloading.file", path))
            val (destRoot, dest) = if (path.startsWith("mods/")) {
                Pair(modsDir, modsDir / path.substring(5))
            } else {
                Pair(destination, destination / path)
            }
            if (!dest.startsWith(destRoot)) {
                throw IllegalArgumentException("Path doesn't start with mods dir?")
            }
            dest.parent.createDirectories()
            val downloadUrl = file["downloads"].asJsonArray.first().asString
            download(file, downloadUrl, dest)
        }

        progressHandler.prepareNewTaskSet(I18N.getString("extracting.overrides"))

        val overridesDir = zfs.getPath("/overrides")
        val overrides = overridesDir.walk().toList()

        progressHandler.newTaskSet(overrides.size)

        for (override in overrides) {
            var relative = override.relativeTo(overridesDir).toString()
            if (relative.startsWith("mods/")) {
                relative = (modsDir / relative.substring(5)).toString()
            }
            var overwrite = true
            progressHandler.newTask(I18N.getString("extracting.override", relative))
            val dest = destination / relative
            try {
                dest.parent.createDirectories()
            } catch (_: IOException) {
            }
            override.copyTo(dest, overwrite)    
        }
    }

    fun install() {
        downloadPack()
        installLoader()
        installPack()
        progressHandler.done()
    }

    override fun close() {
        if (this::zfs.isInitialized) {
            zfs.close()
        }
        jimfs.close()
    }
}
