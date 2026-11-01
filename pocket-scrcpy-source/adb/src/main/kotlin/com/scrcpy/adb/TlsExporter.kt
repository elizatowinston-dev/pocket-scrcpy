package com.scrcpy.adb

interface TlsExporter {
    fun exportKeyingMaterial(label: String, context: ByteArray?, length: Int): ByteArray
}
