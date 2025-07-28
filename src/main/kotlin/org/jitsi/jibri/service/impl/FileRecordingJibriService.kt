/*
 * Copyright @ 2018 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.jitsi.jibri.service.impl

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jitsi.jibri.capture.ffmpeg.FfmpegCapturer
import org.jitsi.jibri.config.Config
import org.jitsi.jibri.config.XmppCredentials
import org.jitsi.jibri.error.JibriError
import org.jitsi.jibri.selenium.CallParams
import org.jitsi.jibri.selenium.JibriSelenium
import org.jitsi.jibri.selenium.RECORDING_URL_OPTIONS
import org.jitsi.jibri.service.ErrorSettingPresenceFields
import org.jitsi.jibri.service.JibriService
import org.jitsi.jibri.service.JibriServiceFinalizer
import org.jitsi.jibri.sink.Sink
import org.jitsi.jibri.sink.impl.FileSink
import org.jitsi.jibri.status.ComponentState
import org.jitsi.jibri.status.ErrorScope
import org.jitsi.jibri.util.ProcessFactory
import org.jitsi.jibri.util.createIfDoesNotExist
import org.jitsi.jibri.util.whenever
import org.jitsi.metaconfig.config
import org.jitsi.xmpp.extensions.jibri.JibriIq
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.UUID

/**
 * Parameters needed for starting a [FileRecordingJibriService]
 */
data class FileRecordingParams(
    /**
     * Which call we'll join
     */
    val callParams: CallParams,
    /**
     * The ID of this session
     */
    val sessionId: String,
    /**
     * The login information needed to appear invisible in
     * the call
     */
    val callLoginParams: XmppCredentials,
    /**
     * A map of arbitrary key, value metadata that will be written
     * to the metadata file.
     */
    val additionalMetadata: Map<Any, Any>? = null
)

/**
 * Set of metadata we'll put alongside the recording file(s)
 */
data class RecordingMetadata(
    @JsonProperty("meeting_url")
    val meetingUrl: String,
    val participants: List<Map<String, Any>>,
    @JsonIgnore val additionalMetadata: Map<Any, Any>? = null
) {
    /**
     * We tell the JSON serializer to ignore the additionalMetadata map (above)
     * and use this to expose each of its individual fields, that way they
     * are serialized at the top level (rather than being nested within a
     * 'additionalMetadata' JSON object)
     */
    @JsonAnyGetter
    fun get(): Map<Any, Any>? = additionalMetadata
}

/**
 * [FileRecordingJibriService] is the [JibriService] responsible for joining
 * a web call, capturing its audio and video, and writing that audio and video
 * to a file to be replayed later.
 */
class FileRecordingJibriService(
    private val fileRecordingParams: FileRecordingParams,
    jibriSelenium: JibriSelenium? = null,
    capturer: FfmpegCapturer? = null,
    processFactory: ProcessFactory = ProcessFactory(),
    fileSystem: FileSystem = FileSystems.getDefault(),
    private var jibriServiceFinalizer: JibriServiceFinalizer? = null
) : StatefulJibriService("File recording") {
    init {
        logger.addContext("session_id", fileRecordingParams.sessionId)
    }

    private val capturer = capturer ?: FfmpegCapturer(logger)
    private val jibriSelenium = jibriSelenium ?: JibriSelenium(logger)

    /**
     * The [Sink] this class will use to model the file on the filesystem
     */
    private var sink: FileSink
    private val recordingsDirectory: String by config {
        "JibriConfig::recordingDirectory" { Config.legacyConfigSource.recordingDirectory!! }
        "jibri.recording.recordings-directory".from(Config.configSource)
    }
    private val finalizeScriptPath: String by config {
        "JibriConfig::finalizeRecordingScriptPath" {
            Config.legacyConfigSource.finalizeRecordingScriptPath!!
        }
        "jibri.recording.finalize-script".from(Config.configSource)
    }

    /**
     * The directory in which we'll store recordings for this particular session.  This is a directory that will
     * be nested within [recordingsDirectory].
     */
    private val sessionRecordingDirectory =
        fileSystem.getPath(recordingsDirectory).resolve(fileRecordingParams.sessionId)

    // HTTP client for API calls
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    init {
        logger.info("Writing recording to $sessionRecordingDirectory, finalize script path $finalizeScriptPath")
        sink = FileSink(
            sessionRecordingDirectory,
            fileRecordingParams.callParams.callUrlInfo.callName
        )

        registerSubComponent(JibriSelenium.COMPONENT_ID, this.jibriSelenium)
        registerSubComponent(FfmpegCapturer.COMPONENT_ID, this.capturer)

        jibriServiceFinalizer = JibriServiceFinalizeCommandRunner(
            processFactory,
            listOf(
                finalizeScriptPath,
                sessionRecordingDirectory.toString()
            )
        )
    }

    override fun start() {
        if (!createIfDoesNotExist(sessionRecordingDirectory, logger)) {
            publishStatus(ComponentState.Error(ErrorCreatingRecordingsDirectory))
            return
        }
        if (!Files.isWritable(sessionRecordingDirectory)) {
            logger.error("Unable to write to $recordingsDirectory")
            publishStatus(ComponentState.Error(RecordingsDirectoryNotWritable))
            return
        }
        jibriSelenium.joinCall(
            fileRecordingParams.callParams.callUrlInfo.copy(urlParams = RECORDING_URL_OPTIONS),
            fileRecordingParams.callLoginParams
        )

        whenever(jibriSelenium).transitionsTo(ComponentState.Running) {
            logger.info("Selenium joined the call, starting the capturer")
            try {
                jibriSelenium.addToPresence("session_id", fileRecordingParams.sessionId)
                jibriSelenium.addToPresence("mode", JibriIq.RecordingMode.FILE.toString())
                jibriSelenium.sendPresence()
                capturer.start(sink)
            } catch (t: Throwable) {
                logger.error("Error while setting fields in presence", t)
                publishStatus(ComponentState.Error(ErrorSettingPresenceFields))
            }
        }
    }

    /**
     * Upload the recorded file to the Cloud Clinic API
     */
    private fun uploadRecordingToApi(recordingFile: File) {
        try {
            logger.info("Starting file upload to Cloud Clinic API...")
            logger.info("File path: ${recordingFile.absolutePath}")
            logger.info("File size: ${recordingFile.length()} bytes")

            val boundary = "----WebKitFormBoundary" + UUID.randomUUID().toString().replace("-", "")

            // Read file content
            val fileContent = recordingFile.readBytes()
            logger.info("File content read successfully, ${fileContent.size} bytes")

            // Build multipart form data properly
            val lineEnd = "\r\n"
            val twoHyphens = "--"

            val multipartBodyBuilder = StringBuilder()
            multipartBodyBuilder.append(twoHyphens + boundary + lineEnd)
            multipartBodyBuilder.append("Content-Disposition: form-data; name=\"file\"; filename=\"${recordingFile.name}\"" + lineEnd)
            multipartBodyBuilder.append("Content-Type: application/octet-stream" + lineEnd)
            multipartBodyBuilder.append(lineEnd)

            val headerBytes = multipartBodyBuilder.toString().toByteArray(Charsets.UTF_8)
            val footerBytes = (lineEnd + twoHyphens + boundary + twoHyphens + lineEnd).toByteArray(Charsets.UTF_8)

            val multipartBody = headerBytes + fileContent + footerBytes

            logger.info("Multipart body prepared, total size: ${multipartBody.size} bytes")

            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://cloudclinicapi.azurewebsites.net/api/MeetingFile/test"))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                .timeout(Duration.ofMinutes(10))
                .build()

            logger.info("HTTP request prepared, sending...")

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            logger.info("Response received - Status: ${response.statusCode()}")
            logger.info("Response headers: ${response.headers().map()}")
            logger.info("Response body: ${response.body()}")

            if (response.statusCode() == 200 || response.statusCode() == 201) {
                logger.info("✅ Successfully uploaded recording file to Cloud Clinic API")
            } else {
                logger.error("❌ Failed to upload recording file. Status: ${response.statusCode()}, Response: ${response.body()}")
            }

        } catch (t: Throwable) {
            logger.error("💥 Exception during API upload", t)
            // Print full stack trace for debugging
            t.printStackTrace()
        }
    }

    override fun stop() {
        logger.info("🔄 FileRecordingJibriService.stop() method called")
        logger.info("Stopping capturer")
        capturer.stop()
        logger.info("Quitting selenium")

        // It's possible that the service was stopped before we even wrote anything, so check if we actually wrote
        // any data to disk.  If not, we'll skip writing the metadata and running the finalize script and instead
        // just delete the directory
        val recordedMedia = Files.exists(sink.file)
        logger.info("🔍 Recorded media exists: $recordedMedia, sink file path: ${sink.file}")
        logger.info("🔍 Sink file absolute path: ${sink.file.toAbsolutePath()}")

        if (!recordedMedia) {
            logger.info("No media was recorded, deleting directory and skipping metadata file & finalize")
            try {
                Files.delete(sessionRecordingDirectory)
            } catch (t: Throwable) {
                logger.error("Problem deleting session recording directory", t)
            }
            jibriSelenium.leaveCallAndQuitBrowser()
            return
        }

        val participants = try {
            jibriSelenium.getParticipants()
        } catch (t: Throwable) {
            logger.error(
                "An error occurred while trying to get the participants list, proceeding with " +
                    "an empty participants list",
                t
            )
            listOf<Map<String, Any>>()
        }
        logger.info("Participants in this recording: $participants")
        if (Files.isWritable(sessionRecordingDirectory)) {
            val metadataFile = sessionRecordingDirectory.resolve("metadata.json")
            val metadata = RecordingMetadata(
                fileRecordingParams.callParams.callUrlInfo.callUrl,
                participants,
                fileRecordingParams.additionalMetadata
            )
            try {
                Files.newBufferedWriter(metadataFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                    .use {
                        jacksonObjectMapper().writeValue(it, metadata)
                    }
                logger.info("✅ Metadata file written successfully")
            } catch (t: Throwable) {
                logger.error("Error writing metadata", t)
                publishStatus(ComponentState.Error(CouldntWriteMeetingMetadata))
            }
        } else {
            logger.error("Unable to write metadata file to recording directory $recordingsDirectory")
        }

        // Upload the recorded file to Cloud Clinic API
        logger.info("🚀 Starting API upload process...")
        try {
            val recordingFile = sink.file.toFile()
            logger.info("📁 Recording file path: ${recordingFile.absolutePath}")
            logger.info("📁 Recording file exists: ${recordingFile.exists()}")

            if (recordingFile.exists()) {
                logger.info("📊 Recording file size: ${recordingFile.length()} bytes")
                logger.info("📝 Recording file name: ${recordingFile.name}")
                logger.info("🔍 Recording file is readable: ${recordingFile.canRead()}")

                // Also check if there are other files in the directory
                val sessionDir = File(sessionRecordingDirectory.toString())
                logger.info("📂 Files in session directory:")
                sessionDir.listFiles()?.forEach { file ->
                    logger.info("   - ${file.name} (${file.length()} bytes)")
                }
            }

            if (recordingFile.exists() && recordingFile.length() > 0) {
                logger.info("✅ Proceeding with API upload...")
                uploadRecordingToApi(recordingFile)
            } else {
                logger.warn("⚠️ Recording file does not exist or is empty, skipping API upload")

                // Alternative: try to find any media file in the directory
                val sessionDir = File(sessionRecordingDirectory.toString())
                val mediaFiles = sessionDir.listFiles { file ->
                    file.name.endsWith(".mp4") || file.name.endsWith(".flac") ||
                    file.name.endsWith(".webm") || file.name.endsWith(".mkv")
                }

                if (mediaFiles != null && mediaFiles.isNotEmpty()) {
                    logger.info("🔄 Found alternative media files, trying to upload the first one:")
                    val alternativeFile = mediaFiles.first()
                    logger.info("📁 Alternative file: ${alternativeFile.absolutePath} (${alternativeFile.length()} bytes)")
                    uploadRecordingToApi(alternativeFile)
                } else {
                    logger.warn("❌ No media files found in session directory")
                }
            }
        } catch (t: Throwable) {
            logger.error("💥 Error during API upload process", t)
            t.printStackTrace()
        }
        logger.info("🏁 API upload process completed")

        jibriSelenium.leaveCallAndQuitBrowser()
        logger.info("Finalizing the recording")
        jibriServiceFinalizer?.doFinalize()
        logger.info("🔚 FileRecordingJibriService.stop() method completed")
    }
}

object ErrorCreatingRecordingsDirectory : JibriError(ErrorScope.SYSTEM, "Could not creat recordings director")
object RecordingsDirectoryNotWritable : JibriError(ErrorScope.SYSTEM, "Recordings directory is not writable")
object CouldntWriteMeetingMetadata : JibriError(ErrorScope.SYSTEM, "Could not write meeting metadata")
