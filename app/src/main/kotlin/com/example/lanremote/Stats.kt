package com.example.lanremote

/**
 * Live session counters, updated by the capture pipelines and server, read by the UI.
 * All fields are @Volatile so the UI thread can poll them without locking.
 */
object Stats {
    @Volatile var sharing = false
    @Volatile var viewerConnected = false
    @Volatile var videoMode = "—"          // "H.264", "MJPEG", or "—"
    @Volatile var width = 0
    @Volatile var height = 0
    @Volatile var videoFrames = 0L
    @Volatile var videoBytes = 0L
    @Volatile var audioActive = false
    @Volatile var audioBytes = 0L

    fun onShareStart(width: Int, height: Int) {
        this.width = width
        this.height = height
        sharing = true
    }

    fun onVideoStart(mode: String) {
        videoMode = mode
        viewerConnected = true
        videoFrames = 0
        videoBytes = 0
    }

    fun onVideoFrame(bytes: Int) {
        videoFrames++
        videoBytes += bytes
    }

    fun onVideoStop() {
        viewerConnected = false
        videoMode = "—"
    }

    fun onAudioStart() { audioActive = true; audioBytes = 0 }
    fun onAudioBytes(bytes: Int) { audioBytes += bytes }
    fun onAudioStop() { audioActive = false }

    fun reset() {
        sharing = false
        viewerConnected = false
        videoMode = "—"
        width = 0; height = 0
        videoFrames = 0; videoBytes = 0
        audioActive = false; audioBytes = 0
    }
}
