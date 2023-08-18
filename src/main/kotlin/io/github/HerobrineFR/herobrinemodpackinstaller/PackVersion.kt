package io.github.HerobrineFR.herobrinemodpackinstaller

import com.google.gson.JsonObject
import java.nio.file.Path

class PackVersion(val modpack: Modpack, val data: JsonObject) {
    val packVersion: String
    val gameVersion: String
    val loader: Loader

    init {
        packVersion = data["version_number"].asString
        gameVersion = data["game_versions"].asJsonArray[0].asString
        loader = "FABRIC".let(Loader::valueOf)
    }

    val launcherFolderPath = "${modpack.id}/$packVersion-$gameVersion"
    val launcherVersionId = "${modpack.id}-$packVersion-$gameVersion"
    val launcherProfileId = "${modpack.id}-$gameVersion"

    fun install(destination: Path, progressHandler: ProgressHandler) =
        PackInstaller(this, destination, progressHandler)
            .use(PackInstaller::install)
}
