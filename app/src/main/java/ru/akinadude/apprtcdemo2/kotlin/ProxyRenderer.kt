package ru.akinadude.apprtcdemo2.kotlin

import org.webrtc.Logging
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

class ProxyVideoSink : VideoSink {
    private val TAG = ProxyVideoSink::class.java.simpleName
    private var target: VideoSink? = null

    @Synchronized
    override fun onFrame(frame: VideoFrame) {
        if (target == null) {
            Logging.d(TAG, "Dropping frame in proxy because target is null.")
            return
        }

        target!!.onFrame(frame)
    }

    @Synchronized
    fun setTarget(target: VideoSink?) {
        this.target = target
    }
}