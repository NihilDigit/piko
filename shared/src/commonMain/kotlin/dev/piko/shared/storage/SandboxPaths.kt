package dev.piko.shared.storage

/** Lexical POSIX path handling for app-owned iOS directories, not a symlink/security boundary. */
object SandboxPaths {
    fun relativeDirectory(root: String, requested: String): String {
        val base = absoluteSegments(root)
        require(base.isNotEmpty()) { "An app-owned directory is required" }
        val target = if (requested.isBlank()) base else absoluteSegments(requested)
        require(target.size >= base.size && target.take(base.size) == base) {
            "Download folders must be inside the app-managed download directory; external folders need document-picker bookmarks"
        }
        return target.drop(base.size).joinToString("/")
    }

    fun resolveDirectory(root: String, relative: String): String {
        val base = absoluteSegments(root)
        require(base.isNotEmpty()) { "An app-owned directory is required" }
        require(!relative.startsWith('/') && '\u0000' !in relative) { "Invalid stored relative directory" }
        val parts = if (relative.isEmpty()) emptyList() else relative.split('/')
        require(parts.none { it.isEmpty() || it == "." || it == ".." }) { "Invalid stored relative directory" }
        return "/" + (base + parts).joinToString("/")
    }

    private fun absoluteSegments(path: String): List<String> {
        require(path.startsWith('/') && '\u0000' !in path) { "An absolute POSIX path is required" }
        val result = mutableListOf<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> {
                    require(result.isNotEmpty()) { "Path traverses above the filesystem root" }
                    result.removeAt(result.lastIndex)
                }
                else -> result.add(part)
            }
        }
        return result
    }
}
