package com.github.takahirom.roborazzi

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import nl.adaptivity.xmlutil.serialization.DefaultXmlSerializationPolicy
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlConfig.Companion.IGNORING_UNKNOWN_CHILD_HANDLER
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.executeAsync
import okio.ByteString.Companion.readByteString
import okio.FileSystem
import okio.Path.Companion.toPath
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.junit.Ignore
import org.junit.Test
import org.w3c.dom.Node
import org.w3c.dom.NodeList


class GenerateQualifiersTest {
  val devicesTarGz =
    ("https://android.googlesource.com" +
      "/platform/tools/base/+archive/refs/heads/mirror-goog-studio-main/sdklib/" +
      "src/main/resources/com/android/sdklib/devices.tar.gz").toHttpUrl()

  val client = OkHttpClient()

  val xml = XML {
    policy = DefaultXmlSerializationPolicy(
      pedantic = false,
      unknownChildHandler = IGNORING_UNKNOWN_CHILD_HANDLER
    )
    repairNamespaces = true
  }

  @Test
  @Ignore
  fun generate() {
    val deviceTypes = runBlocking { readAllDevices() }.groupBy { it.tagId }

    FileSystem.SYSTEM.write("../roborazzi/src/main/java/com/github/takahirom/roborazzi/RobolectricDeviceQualifiers.kt".toPath()) {

      writeUtf8(
        """
          package com.github.takahirom.roborazzi

          object RobolectricDeviceQualifiers {
            // Generated by [GenerateQualifiersTest.kt]
            // Data from: AOSP $devicesTarGz
 
        """.trimIndent()
      )
      deviceTypes.forEach { (tagId, devices) ->
        writeUtf8("\n\t// Type: ${tagId ?: "default"}\n")
        devices.forEach { device ->
          // find device name
          val name = device.name
            .replace(" ", "")
            .replace("(", "")
            .replace(")", "")
            .replace("'", "")
            .replace("-", "")
            .replace(".", "")
            .replace("\"", "")

          val shouldSkipDevice = shouldSkip(name, device)

          if (shouldSkipDevice) {
            println("skip device:$name")
            return@forEach
          }

          val screen = device.hardware.screen
          val screenSize = screen.screenSize

          val qualifier = listOf(
            "w${screen.widthDp}dp",
            "h${screen.heightDp}dp",
            screenSize,
            device.screenRatio,
            device.shape,
            device.type,
            screen.pixelDensity,
            "keyshidden",
            device.hardware.nav
          ).joinToString("-")

          writeUtf8("\tconst val $name = \"$qualifier\"\n")
        }
      }

      writeUtf8("\n}")
    }
  }

  private fun shouldSkip(name: String, device: Device): Boolean {
    if (name[0] in '0'..'9') return true
    val skippingDevicePrefixes =
      listOf(
        "GalaxyNexus",
        "Pixel2",
        "Pixel3",
        "NexusS",
        "Nexus10",
        "Nexus4",
        "Nexus5",
        "Nexus6"
      )
    if (skippingDevicePrefixes.any { name.startsWith(it) }) {
      return true
    }
    val skippingDevices = listOf("Nexus72012", "Pixel")
    if (skippingDevices.any { name == it }) {
      return true
    }
    return false
  }

  private suspend fun readAllDevices(): List<Device> {
    val response = client.newCall(Request(devicesTarGz)).executeAsync()

    val archive = TarArchiveInputStream(GzipCompressorInputStream(response.body.byteStream()))
    val devices = buildList {
      var entry: TarArchiveEntry? = archive.nextTarEntry
      while (entry != null) {
        val content = archive.readByteString(entry.realSize.toInt()).utf8()

        val withSingleNamespace = content.replace(
          "http://schemas.android.com/sdk/devices/\\d+".toRegex(),
          "http://schemas.android.com/sdk/devices/1"
        )

        val devices: Devices = xml.decodeFromString(withSingleNamespace)
        addAll(devices.devices)

        entry = archive.nextTarEntry
      }
    }
    return devices
  }

  private fun NodeList.toList(): List<Node> {
    val list = mutableListOf<Node>()
    for (i in 0 until length) {
      list.add(item(i))
    }
    return list
  }
}