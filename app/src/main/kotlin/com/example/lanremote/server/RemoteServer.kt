package com.example.lanremote.server

import android.content.Context
import com.example.lanremote.Stats
import com.example.lanremote.control.ControlEventParser
import com.example.lanremote.control.ControlService
import com.example.lanremote.control.CoordinateMapper
import com.example.lanremote.video.CaptureService
import com.example.lanremote.video.VideoFrame
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel

class RemoteServer(private val context: Context) {
    private var engine: CIOApplicationEngine? = null

    fun start(port: Int = 8080) {
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets) {
                pingPeriodMillis = 15_000
            }
            routing {
                get("/") { call.respondText(indexHtml(), ContentType.Text.Html) }
                get("/client.js") { call.respondText(asset("web/client.js"), ContentType.Text.JavaScript) }
                get("/opus-decoder.min.js") {
                    call.respondText(asset("web/opus-decoder.min.js"), ContentType.Text.JavaScript)
                }
                webSocket("/video") { serveVideo() }
                webSocket("/mjpeg") { serveMjpeg() }
                webSocket("/audio") { serveAudio() }
                webSocket("/control") { serveControl() }
            }
        }.also { it.start(wait = false) }
    }

    private fun indexHtml(): String = asset("web/index.html")

    private fun asset(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    private suspend fun DefaultWebSocketServerSession.serveVideo() {
        val meta = CaptureService.meta ?: return
        val controller = CaptureService.controller ?: return
        send(Frame.Text(meta.toJson()))
        val queue = Channel<ByteArray>(capacity = 8)
        val requestKeyframe = controller.startH264 { key, nal ->
            queue.trySend(VideoFrame.encode(key, nal))
        } ?: return
        Stats.onVideoStart("H.264")
        // Force an immediate IDR (carrying SPS/PPS) so this late-joining client can decode at once.
        requestKeyframe()
        try {
            for (frame in queue) {
                send(Frame.Binary(true, frame))
                Stats.onVideoFrame(frame.size)
            }
        } finally {
            Stats.onVideoStop()
            controller.stopPipeline()
            queue.close()
        }
    }

    private suspend fun DefaultWebSocketServerSession.serveMjpeg() {
        val meta = CaptureService.meta ?: return
        val controller = CaptureService.controller ?: return
        send(Frame.Text(meta.toJson()))
        val queue = Channel<ByteArray>(capacity = 4)
        controller.startMjpeg { jpeg -> queue.trySend(jpeg) }
        Stats.onVideoStart("MJPEG")
        try {
            for (jpeg in queue) {
                send(Frame.Binary(true, jpeg))
                Stats.onVideoFrame(jpeg.size)
            }
        } finally {
            Stats.onVideoStop()
            controller.stopPipeline()
            queue.close()
        }
    }

    private suspend fun DefaultWebSocketServerSession.serveAudio() {
        val controller = CaptureService.controller ?: return
        val queue = Channel<ByteArray>(capacity = 32)
        val started = controller.startAudio { pkt -> queue.trySend(pkt) }
        if (!started) {
            // RECORD_AUDIO not granted or capture unavailable — tell the client so it can hint the user.
            send(Frame.Text("""{"error":"audio_unavailable"}"""))
            return
        }
        send(Frame.Text("""{"sampleRate":48000,"channels":2}"""))
        Stats.onAudioStart()
        try {
            for (pkt in queue) {
                send(Frame.Binary(true, pkt))
                Stats.onAudioBytes(pkt.size)
            }
        } finally {
            Stats.onAudioStop()
            controller.stopAudio()
            queue.close()
        }
    }

    private suspend fun DefaultWebSocketServerSession.serveControl() {
        val meta = CaptureService.meta ?: return
        val mapper = CoordinateMapper(meta.width, meta.height)
        for (frame in incoming) {
            if (frame is Frame.Text) {
                ControlEventParser.parse(frame.readText())?.let {
                    ControlService.dispatch(it, mapper)
                }
            }
        }
    }

    fun stop() {
        engine?.stop(500, 1000)
        engine = null
    }
}
