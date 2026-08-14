package com.htmake.reader.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress

class ImageProxyTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `normalizes protocol relative URLs and rejects unsupported protocols`() {
        assertEquals("https://example.com/cover.jpg", ImageProxy.normalizeUrl("//example.com/cover.jpg"))
        assertNull(ImageProxy.normalizeUrl("file:///etc/passwd"))
        assertNull(ImageProxy.normalizeUrl("http://example.com/cover.jpg\r\nX-Test: value"))
    }

    @Test
    fun `detects private addresses`() {
        assertTrue(ImageProxy.isPrivateAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(ImageProxy.isPrivateAddress(InetAddress.getByName("10.0.0.1")))
        assertFalse(ImageProxy.isPrivateAddress(InetAddress.getByName("8.8.8.8")))
    }

    @Test
    fun `detects image signatures`() {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0, 0, 0, 0
        )
        assertEquals("png", ImageProxy.sniffExt(png))
        assertNull(ImageProxy.sniffExt("<html></html>".toByteArray()))
    }

    @Test
    fun `trims oldest cover files to configured size`() {
        val tempDir = temporaryFolder.root
        val oldest = File(tempDir, "old.jpg").apply {
            writeBytes(ByteArray(8))
            setLastModified(1)
        }
        val newest = File(tempDir, "new.png").apply {
            writeBytes(ByteArray(8))
            setLastModified(2)
        }
        File(tempDir, "other.txt").writeBytes(ByteArray(20))

        assertEquals(1, ImageProxy.trimCache(tempDir, 12))
        assertFalse(oldest.exists())
        assertTrue(newest.exists())
    }
}
