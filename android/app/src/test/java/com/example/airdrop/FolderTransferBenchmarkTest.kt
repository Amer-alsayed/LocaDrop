package com.example.airdrop

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class FolderTransferBenchmarkTest {

    // Helper class for simulation
    class FakeFolderNode(
        override val name: String,
        override val isDirectory: Boolean,
        val children: List<FakeFolderNode> = emptyList(),
        override val length: Long = 0,
        private val tracker: AccessTracker
    ) : FolderNode<String> {

        override val uri: String = "fake://$name"

        override fun listFiles(): Array<FolderNode<String>> {
            tracker.listFilesCallCount++
            return children.toTypedArray() as Array<FolderNode<String>>
        }
    }

    class AccessTracker {
        var listFilesCallCount = 0
    }

    // Legacy logic simulation
    private fun calculateSizeLegacy(folder: FolderNode<String>): Long {
        var size = 0L
        val files = folder.listFiles()
        for (file in files) {
            if (file.isDirectory) {
                size += calculateSizeLegacy(file)
            } else {
                size += file.length
            }
        }
        return size
    }

    private fun sendFolderLegacy(folder: FolderNode<String>, parentPath: String, action: (FolderNode<String>) -> Unit) {
        val files = folder.listFiles()
        for (file in files) {
            if (file.isDirectory) {
                sendFolderLegacy(file, "$parentPath/${file.name}", action)
            } else {
                action(file)
            }
        }
    }

    @Test
    fun benchmarkFolderTraversal() {
        // Setup a deep structure
        val trackerLegacy = AccessTracker()
        val rootLegacy = createDeepStructure(trackerLegacy)

        // MEASURE LEGACY
        val startLegacy = System.nanoTime()
        val totalSize = calculateSizeLegacy(rootLegacy)

        var sentFilesLegacy = 0
        sendFolderLegacy(rootLegacy, rootLegacy.name ?: "Root") {
            sentFilesLegacy++
        }
        val endLegacy = System.nanoTime()

        val legacyCalls = trackerLegacy.listFilesCallCount

        println("Legacy Approach:")
        println("  Total Size: $totalSize")
        println("  Sent Files: $sentFilesLegacy")
        println("  listFiles calls: $legacyCalls")
        println("  Time: ${(endLegacy - startLegacy) / 1000} us")

        // MEASURE OPTIMIZED
        val trackerOpt = AccessTracker()
        val rootOpt = createDeepStructure(trackerOpt) // Same structure

        val startOpt = System.nanoTime()
        val result = FolderScanner.scan(rootOpt, rootOpt.name ?: "Root")

        // Simulating sending by iterating the list
        var sentFilesOpt = 0
        result.files.forEach {
             sentFilesOpt++
        }
        val endOpt = System.nanoTime()

        val optCalls = trackerOpt.listFilesCallCount

        println("Optimized Approach:")
        println("  Total Size: ${result.totalSize}")
        println("  Sent Files: $sentFilesOpt")
        println("  listFiles calls: $optCalls")
        println("  Time: ${(endOpt - startOpt) / 1000} us")

        assertEquals(totalSize, result.totalSize)
        assertEquals(sentFilesLegacy, sentFilesOpt)

        assertTrue("Optimized calls ($optCalls) should be less than Legacy calls ($legacyCalls)", optCalls < legacyCalls)
        assertEquals(legacyCalls / 2, optCalls)
    }

    private fun createDeepStructure(tracker: AccessTracker): FakeFolderNode {
        // 5 levels deep, 3 folders each, 5 files each
        return createDir("Root", 3, tracker)
    }

    private fun createDir(name: String, depth: Int, tracker: AccessTracker): FakeFolderNode {
        val children = mutableListOf<FakeFolderNode>()

        // Files
        for (i in 1..5) {
            children.add(FakeFolderNode("File_$i.txt", false, emptyList(), 1000, tracker))
        }

        if (depth > 0) {
             for (i in 1..3) {
                 children.add(createDir("Dir_$i", depth - 1, tracker))
             }
        }

        return FakeFolderNode(name, true, children, 0, tracker)
    }
}
