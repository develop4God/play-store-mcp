package io.devexpert.playstore

import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.Random
import javax.imageio.ImageIO

/** Writes small real image files into a private temp directory that [close] deletes. */
internal class TestImages : AutoCloseable {
    private val dir: File = Files.createTempDirectory("store-images").toFile()

    fun png(name: String, width: Int, height: Int, alpha: Boolean = false): File =
        write(name, "png", BufferedImage(width, height, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB))

    fun jpeg(name: String, width: Int, height: Int): File = write(name, "jpg", BufferedImage(width, height, BufferedImage.TYPE_INT_RGB))

    /** A PNG of random noise, which does not compress, so its size is close to the raw pixel size. */
    fun noisyPng(name: String, width: Int, height: Int): File {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val random = Random(1)
        for (x in 0 until width) for (y in 0 until height) image.setRGB(x, y, random.nextInt())
        return write(name, "png", image)
    }

    fun text(name: String, content: String = "not an image"): File = File(dir, name).also { it.writeText(content) }

    fun file(name: String) = File(dir, name)

    private fun write(name: String, format: String, image: BufferedImage): File =
        File(dir, name).also { check(ImageIO.write(image, format, it)) { "no writer for $format" } }

    override fun close() {
        dir.deleteRecursively()
    }
}
