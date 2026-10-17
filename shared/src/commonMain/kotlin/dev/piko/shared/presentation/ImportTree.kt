package dev.piko.shared.presentation

/** A complete source tree built from resolved paths, independent of visible filtering or selection. */
data class ImportTreeNode(val key: String, val name: String, val depth: Int, val indices: Set<Int>, val fileIndex: Int? = null) {
    val isFolder: Boolean get() = fileIndex == null
}

data class ImportTreeSource(val key: String, val paths: List<String>)
data class ImportSourceNode(val sourceKey: String, val node: ImportTreeNode) {
    // Include the key length so arbitrary source keys and paths cannot alias each other.
    val key: String get() = "${sourceKey.length}:$sourceKey:${node.key}"
}

/** Concatenate original trees without inventing one wrapper folder per link or merging equal paths. */
fun importSourceForest(sources: List<ImportTreeSource>): List<ImportSourceNode> =
    sources.flatMap { source -> importTree(source.paths).map { ImportSourceNode(source.key, it) } }

fun visibleImportSources(nodes: List<ImportSourceNode>, expanded: Set<String>, query: String): List<ImportSourceNode> =
    nodes.groupBy { it.sourceKey }.values.flatMap { source ->
        val localExpanded = source.filter { it.key in expanded }.map { it.node.key }.toSet()
        val visible = visibleImportTree(source.map { it.node }, localExpanded, query).map { it.key }.toSet()
        source.filter { it.node.key in visible }
    }

fun importTree(paths: List<String>): List<ImportTreeNode> {
    val directories = linkedMapOf<String, MutableSet<Int>>()
    val names = linkedMapOf<String, String>()
    paths.forEachIndexed { index, path ->
        val parts = path.split('/').filter { it.isNotEmpty() }
        parts.dropLast(1).indices.forEach { depth ->
            val key = parts.take(depth + 1).joinToString("/")
            directories.getOrPut(key) { linkedSetOf() } += index
            names[key] = parts[depth]
        }
    }
    val children = linkedMapOf<String, MutableList<ImportTreeNode>>()
    directories.forEach { (path, indices) ->
        children.getOrPut(path.substringBeforeLast('/', "")) { mutableListOf() } +=
            ImportTreeNode("d:$path", names.getValue(path), path.count { it == '/' }, indices.toSet())
    }
    paths.forEachIndexed { index, path ->
        val normalized = path.split('/').filter { it.isNotEmpty() }.joinToString("/")
        children.getOrPut(normalized.substringBeforeLast('/', "")) { mutableListOf() } +=
            ImportTreeNode("f:$index", normalized.substringAfterLast('/'), normalized.count { it == '/' }, setOf(index), index)
    }
    return buildList {
        fun visit(parent: String) {
            children[parent].orEmpty().sortedWith(compareBy<ImportTreeNode> { !it.isFolder }.thenBy { it.name }).forEach { node ->
                add(node)
                if (node.isFolder) visit(node.key.removePrefix("d:"))
            }
        }
        visit("")
    }
}

/** Search examines every source path, keeping ancestors so a nested match retains its context. */
fun visibleImportTree(nodes: List<ImportTreeNode>, expanded: Set<String>, query: String): List<ImportTreeNode> {
    val matches = if (query.isBlank()) null else nodes.filter { it.name.contains(query, ignoreCase = true) }.flatMap { it.indices }.toSet()
    var hiddenDepth: Int? = null
    return nodes.filter { node ->
        if (matches != null) node.indices.any { it in matches }
        else {
            if (hiddenDepth != null && node.depth <= hiddenDepth!!) hiddenDepth = null
            val visible = hiddenDepth == null
            if (visible && node.isFolder && node.key !in expanded) hiddenDepth = node.depth
            visible
        }
    }
}
