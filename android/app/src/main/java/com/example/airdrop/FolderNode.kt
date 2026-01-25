package com.example.airdrop

interface FolderNode<T> {
    val name: String?
    val isDirectory: Boolean
    val length: Long
    val uri: T
    fun listFiles(): Array<FolderNode<T>>
}
