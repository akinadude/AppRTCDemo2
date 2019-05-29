package ru.akinadude.apprtcdemo2.kotlin

import org.webrtc.RendererCommon

interface CallEvents {
    fun onCallHangUp()

    fun onCameraSwitch()

    //todo won't be used for a while
    fun onVideoScalingSwitch(scalingType: RendererCommon.ScalingType)

    //todo won't be used for a while
    fun onCaptureFormatChange(width: Int, height: Int, framerate: Int)

    fun onToggleMic(): Boolean
}