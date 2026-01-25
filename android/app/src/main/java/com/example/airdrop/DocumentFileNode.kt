package com.example.airdrop

import android.net.Uri
import androidx.documentfile.provider.DocumentFile

class DocumentFileNode(private val documentFile: DocumentFile) : FolderNode<Uri> {
    override val name: String?
        get() = documentFile.name
    override val isDirectory: Boolean
        get() = documentFile.isDirectory
    override val length: Long
        get() = documentFile.length()
    override val uri: Uri
        get() = documentFile.uri

    override fun listFiles(): Array<FolderNode<Uri>> {
        return documentFile.listFiles().map { DocumentFileNode(it) }.toTypedArray()
    }
}
