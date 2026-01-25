package com.example.airdrop

data class ScannedFile<T>(
    val uri: T,
    val name: String,
    val size: Long,
    val relativePath: String
)

data class ScanResult<T>(
    val files: List<ScannedFile<T>>,
    val totalSize: Long
)

object FolderScanner {
    fun <T> scan(folder: FolderNode<T>, rootName: String): ScanResult<T> {
        val files = mutableListOf<ScannedFile<T>>()
        val totalSize = scanRecursive(folder, rootName, files)
        return ScanResult(files, totalSize)
    }

    private fun <T> scanRecursive(
        folder: FolderNode<T>,
        parentPath: String,
        files: MutableList<ScannedFile<T>>
    ): Long {
        var size = 0L
        val children = folder.listFiles()
        for (child in children) {
            if (child.isDirectory) {
                val childName = child.name ?: "Unknown"
                size += scanRecursive(child, "$parentPath/$childName", files)
            } else {
                val childSize = child.length
                size += childSize
                val name = child.name ?: "Unknown"
                val relativePath = "$parentPath/$name"
                files.add(ScannedFile(child.uri, name, childSize, relativePath))
            }
        }
        return size
    }
}
