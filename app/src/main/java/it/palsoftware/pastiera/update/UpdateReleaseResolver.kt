package it.palsoftware.pastiera.update

internal data class ReleaseAsset(
    val name: String,
    val browserDownloadUrl: String?
)

internal data class GitHubRelease(
    val tagName: String,
    val prerelease: Boolean,
    val draft: Boolean,
    val htmlUrl: String?,
    val assets: List<ReleaseAsset>
)

internal data class ReleaseInfo(
    val tagName: String,
    val downloadUrl: String?,
    val releasePageUrl: String?
)

internal fun findLatestRelease(releases: List<GitHubRelease>, releaseChannel: String): ReleaseInfo? {
    val normalizedChannel = releaseChannel.lowercase()

    for (release in releases) {
        if (release.draft) continue

        val matchesChannel = when (normalizedChannel) {
            "nightly" -> release.prerelease && release.tagName.startsWith("nightly/")
            else -> !release.prerelease
        }
        if (!matchesChannel) continue

        return ReleaseInfo(
            tagName = release.tagName,
            downloadUrl = findApkDownloadUrl(release.assets),
            releasePageUrl = release.htmlUrl?.takeIf(String::isNotBlank)
        )
    }

    return null
}

internal fun findApkDownloadUrl(assets: List<ReleaseAsset>): String? =
    assets.firstNotNullOfOrNull { asset ->
        val isApk = asset.name.lowercase().endsWith(".apk")
        if (isApk) asset.browserDownloadUrl?.takeIf(String::isNotBlank) else null
    }

internal fun normalizeReleaseVersion(version: String): String =
    version.removePrefix("nightly/").removePrefix("v").removePrefix("V")

internal fun isReleaseVersionNewer(latestVersion: String, currentVersion: String): Boolean {
    val latest = parseVersionParts(latestVersion)
    val current = parseVersionParts(currentVersion)
    // Builds running ahead of the newest published release must not be offered
    // a "new" update, so only unparseable versions fall back to inequality.
    if (latest == null || current == null) return latestVersion != currentVersion

    val coreComparison = compareNumberLists(latest.core, current.core)
    if (coreComparison != 0) return coreComparison > 0
    return compareSuffixes(latest.suffix, current.suffix) > 0
}

private data class VersionParts(val core: List<Int>, val suffix: List<String>)

private fun parseVersionParts(version: String): VersionParts? {
    val core = version.substringBefore('-')
    val suffix = version.substringAfter('-', missingDelimiterValue = "")
    val numbers = core.split('.').map { it.toIntOrNull() ?: return null }
    return VersionParts(numbers, if (suffix.isEmpty()) emptyList() else suffix.split('.'))
}

private fun compareNumberLists(left: List<Int>, right: List<Int>): Int {
    for (index in 0 until maxOf(left.size, right.size)) {
        val diff = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
        if (diff != 0) return diff
    }
    return 0
}

private fun compareSuffixes(left: List<String>, right: List<String>): Int {
    if (left.isEmpty() && right.isEmpty()) return 0
    // A release without a suffix is newer than a pre-release with the same core version.
    if (left.isEmpty()) return 1
    if (right.isEmpty()) return -1
    for (index in 0 until maxOf(left.size, right.size)) {
        val leftSegment = left.getOrNull(index) ?: return -1
        val rightSegment = right.getOrNull(index) ?: return 1
        val leftNumber = leftSegment.toIntOrNull()
        val rightNumber = rightSegment.toIntOrNull()
        val diff = if (leftNumber != null && rightNumber != null) {
            leftNumber.compareTo(rightNumber)
        } else {
            leftSegment.compareTo(rightSegment)
        }
        if (diff != 0) return diff
    }
    return 0
}
