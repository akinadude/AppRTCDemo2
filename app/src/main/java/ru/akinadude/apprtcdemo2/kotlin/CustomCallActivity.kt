package ru.akinadude.apprtcdemo2.kotlin

import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.app.FragmentTransaction
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Toast
import org.webrtc.*
import org.webrtc.RendererCommon.ScalingType
import ru.akinadude.apprtcdemo2.*
import ru.akinadude.apprtcdemo2.AppRTCAudioManager.AudioDevice
import ru.akinadude.apprtcdemo2.AppRTCClient.RoomConnectionParameters
import ru.akinadude.apprtcdemo2.AppRTCClient.SignalingParameters
import ru.akinadude.apprtcdemo2.CallFragment
import ru.akinadude.apprtcdemo2.PeerConnectionClient.DataChannelParameters
import ru.akinadude.apprtcdemo2.PeerConnectionClient.PeerConnectionParameters
import java.io.IOException
import java.util.*

import ru.akinadude.apprtcdemo2.R

/**
 * Activity for peer connection call setup, call waiting
 * and call view.
 */
class CustomCallActivity : Activity(), AppRTCClient.SignalingEvents, PeerConnectionClient.PeerConnectionEvents,
    CallFragment.OnCallEvents {

    private val remoteProxyRenderer = ProxyVideoSink()
    private val localProxyVideoSink = ProxyVideoSink()
    private var peerConnectionClient: PeerConnectionClient? = null
    private var appRtcClient: AppRTCClient? = null
    private var signalingParameters: SignalingParameters? = null
    private var audioManager: AppRTCAudioManager? = null
    private var pipRenderer: SurfaceViewRenderer? = null
    private var fullscreenRenderer: SurfaceViewRenderer? = null
    private var videoFileRenderer: VideoFileRenderer? = null
    private val remoteSinks = ArrayList<VideoSink>()
    private var logToast: Toast? = null
    private var commandLineRun: Boolean = false
    private var activityRunning: Boolean = false
    private var roomConnectionParameters: RoomConnectionParameters? = null
    private var peerConnectionParameters: PeerConnectionParameters? = null
    private var connected: Boolean = false
    private var isError: Boolean = false
    private var callControlFragmentVisible = true
    private var callStartedTimeMs: Long = 0
    private var micEnabled = true
    private var screencaptureEnabled: Boolean = false
    // True if local view is in the fullscreen renderer.
    private var isSwappedFeeds: Boolean = false

    // Controls
    private var callFragment: CallFragment? = null
    private var hudFragment: HudFragment? = null
    private var cpuMonitor: CpuMonitor? = null

    private val displayMetrics: DisplayMetrics
        @TargetApi(17)
        get() {
            val displayMetrics = DisplayMetrics()
            val windowManager = application.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            return displayMetrics
        }

    // TODO(bugs.webrtc.org/8580): LayoutParams.FLAG_TURN_SCREEN_ON and
    // LayoutParams.FLAG_SHOW_WHEN_LOCKED are deprecated.
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread.setDefaultUncaughtExceptionHandler(UnhandledExceptionHandler(this))

        // Set window styles for fullscreen-window size. Needs to be done before
        // adding content.
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(
            LayoutParams.FLAG_FULLSCREEN or LayoutParams.FLAG_KEEP_SCREEN_ON
                    or LayoutParams.FLAG_SHOW_WHEN_LOCKED or LayoutParams.FLAG_TURN_SCREEN_ON
        )
        window.decorView.systemUiVisibility = systemUiVisibility
        setContentView(R.layout.activity_call)

        connected = false
        signalingParameters = null

        // Create UI controls.
        pipRenderer = findViewById(R.id.pip_video_view)
        fullscreenRenderer = findViewById(R.id.fullscreen_video_view)
        callFragment = CallFragment()
        hudFragment = HudFragment()

        // Show/hide call control fragment on view click.
        val listener = View.OnClickListener { toggleCallControlFragmentVisibility() }

        // Swap feeds on pip view click.
        pipRenderer!!.setOnClickListener { setSwappedFeeds(!isSwappedFeeds) }

        fullscreenRenderer!!.setOnClickListener(listener)
        remoteSinks.add(remoteProxyRenderer)

        val intent = intent
        val eglBase = EglBase.create()

        // Create video renderers.
        pipRenderer!!.init(eglBase.eglBaseContext, null)
        pipRenderer!!.setScalingType(ScalingType.SCALE_ASPECT_FIT)
        val saveRemoteVideoToFile = intent.getStringExtra(EXTRA_SAVE_REMOTE_VIDEO_TO_FILE)

        // When saveRemoteVideoToFile is set we save the video from the remote to a file.
        if (saveRemoteVideoToFile != null) {
            val videoOutWidth = intent.getIntExtra(EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_WIDTH, 0)
            val videoOutHeight = intent.getIntExtra(EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_HEIGHT, 0)
            try {
                videoFileRenderer = VideoFileRenderer(
                    saveRemoteVideoToFile,
                    videoOutWidth,
                    videoOutHeight,
                    eglBase.eglBaseContext
                )
                videoFileRenderer?.let { remoteSinks.add(it) }
            } catch (e: IOException) {
                throw RuntimeException("Failed to open video file for output: " + saveRemoteVideoToFile, e)
            }

        }
        fullscreenRenderer!!.init(eglBase.eglBaseContext, null)
        fullscreenRenderer!!.setScalingType(ScalingType.SCALE_ASPECT_FILL)

        pipRenderer!!.setZOrderMediaOverlay(true)
        pipRenderer!!.setEnableHardwareScaler(true /* enabled */)
        fullscreenRenderer!!.setEnableHardwareScaler(false /* enabled */)
        // Start with local feed in fullscreen and swap it to the pip when the call is connected.
        setSwappedFeeds(true /* isSwappedFeeds */)

        // Check for mandatory permissions.
        for (permission in MANDATORY_PERMISSIONS) {
            if (checkCallingOrSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                logAndToast("Permission $permission is not granted")
                setResult(Activity.RESULT_CANCELED)
                finish()
                return
            }
        }

        val roomUri = intent.data
        if (roomUri == null) {
            logAndToast(getString(R.string.missing_url))
            Log.e(TAG, "Didn't get any URL in intent!")
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        // Get Intent parameters.
        val roomId = intent.getStringExtra(EXTRA_ROOMID)
        Log.d(TAG, "Room ID: " + roomId!!)
        if (roomId == null || roomId!!.length == 0) {
            logAndToast(getString(R.string.missing_url))
            Log.e(TAG, "Incorrect room ID in intent!")
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        val loopback = intent.getBooleanExtra(EXTRA_LOOPBACK, false)
        val tracing = intent.getBooleanExtra(EXTRA_TRACING, false)

        var videoWidth = intent.getIntExtra(EXTRA_VIDEO_WIDTH, 0)
        var videoHeight = intent.getIntExtra(EXTRA_VIDEO_HEIGHT, 0)

        screencaptureEnabled = intent.getBooleanExtra(EXTRA_SCREENCAPTURE, false)
        // If capturing format is not specified for screencapture, use screen resolution.
        if (screencaptureEnabled && videoWidth == 0 && videoHeight == 0) {
            val displayMetrics = displayMetrics
            videoWidth = displayMetrics.widthPixels
            videoHeight = displayMetrics.heightPixels
        }
        var dataChannelParameters: DataChannelParameters? = null
        if (intent.getBooleanExtra(EXTRA_DATA_CHANNEL_ENABLED, false)) {
            dataChannelParameters = DataChannelParameters(
                intent.getBooleanExtra(EXTRA_ORDERED, true),
                intent.getIntExtra(EXTRA_MAX_RETRANSMITS_MS, -1),
                intent.getIntExtra(EXTRA_MAX_RETRANSMITS, -1), intent.getStringExtra(EXTRA_PROTOCOL),
                intent.getBooleanExtra(EXTRA_NEGOTIATED, false), intent.getIntExtra(EXTRA_ID, -1)
            )
        }
        peerConnectionParameters = PeerConnectionParameters(
            intent.getBooleanExtra(EXTRA_VIDEO_CALL, true), loopback,
            tracing, videoWidth, videoHeight, intent.getIntExtra(EXTRA_VIDEO_FPS, 0),
            intent.getIntExtra(EXTRA_VIDEO_BITRATE, 0), intent.getStringExtra(EXTRA_VIDEOCODEC),
            intent.getBooleanExtra(EXTRA_HWCODEC_ENABLED, true),
            intent.getBooleanExtra(EXTRA_FLEXFEC_ENABLED, false),
            intent.getIntExtra(EXTRA_AUDIO_BITRATE, 0), intent.getStringExtra(EXTRA_AUDIOCODEC),
            intent.getBooleanExtra(EXTRA_NOAUDIOPROCESSING_ENABLED, false),
            intent.getBooleanExtra(EXTRA_AECDUMP_ENABLED, false),
            intent.getBooleanExtra(EXTRA_SAVE_INPUT_AUDIO_TO_FILE_ENABLED, false),
            intent.getBooleanExtra(EXTRA_OPENSLES_ENABLED, false),
            intent.getBooleanExtra(EXTRA_DISABLE_BUILT_IN_AEC, false),
            intent.getBooleanExtra(EXTRA_DISABLE_BUILT_IN_AGC, false),
            intent.getBooleanExtra(EXTRA_DISABLE_BUILT_IN_NS, false),
            intent.getBooleanExtra(EXTRA_DISABLE_WEBRTC_AGC_AND_HPF, false),
            intent.getBooleanExtra(EXTRA_ENABLE_RTCEVENTLOG, false),
            intent.getBooleanExtra(EXTRA_USE_LEGACY_AUDIO_DEVICE, false), dataChannelParameters
        )
        commandLineRun = intent.getBooleanExtra(EXTRA_CMDLINE, false)
        val runTimeMs = intent.getIntExtra(EXTRA_RUNTIME, 0)

        Log.d(TAG, "VIDEO_FILE: '" + intent.getStringExtra(EXTRA_VIDEO_FILE_AS_CAMERA) + "'")

        // Create connection client. Use DirectRTCClient if room name is an IP otherwise use the
        // standard WebSocketRTCClient.
        if (loopback || !DirectRTCClient.IP_PATTERN.matcher(roomId).matches()) {
            appRtcClient = WebSocketRTCClient(this)
        } else {
            Log.i(TAG, "Using DirectRTCClient because room name looks like an IP.")
            appRtcClient = DirectRTCClient(this)
        }
        // Create connection parameters.
        val urlParameters = intent.getStringExtra(EXTRA_URLPARAMETERS)
        roomConnectionParameters = RoomConnectionParameters(roomUri.toString(), roomId, loopback, urlParameters)

        // Create CPU monitor
        if (CpuMonitor.isSupported()) {
            cpuMonitor = CpuMonitor(this)
            hudFragment!!.setCpuMonitor(cpuMonitor)
        }

        // Send intent arguments to fragments.
        callFragment!!.arguments = intent.extras
        hudFragment!!.arguments = intent.extras
        // Activate call and HUD fragments and start the call.
        val ft = fragmentManager.beginTransaction()
        ft.add(R.id.call_fragment_container, callFragment)
        ft.add(R.id.hud_fragment_container, hudFragment)
        ft.commit()

        // For command line execution run connection for <runTimeMs> and exit.
        if (commandLineRun && runTimeMs > 0) {
            (Handler()).postDelayed({ disconnect() }, runTimeMs.toLong())
        }

        // Create peer connection client.
        peerConnectionClient = PeerConnectionClient(
            applicationContext, eglBase, peerConnectionParameters!!, this@CustomCallActivity
        )
        val options = PeerConnectionFactory.Options()
        if (loopback) {
            options.networkIgnoreMask = 0
        }
        peerConnectionClient!!.createPeerConnectionFactory(options)

        if (screencaptureEnabled) {
            startScreenCapture()
        } else {
            startCall()
        }
    }

    @TargetApi(21)
    private fun startScreenCapture() {
        val mediaProjectionManager = application.getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager
        startActivityForResult(
            mediaProjectionManager!!.createScreenCaptureIntent(), CAPTURE_PERMISSION_REQUEST_CODE
        )
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        if (requestCode != CAPTURE_PERMISSION_REQUEST_CODE)
            return
        mediaProjectionPermissionResultCode = resultCode
        mediaProjectionPermissionResultData = data
        startCall()
    }

    private fun useCamera2(): Boolean {
        return Camera2Enumerator.isSupported(this) && intent.getBooleanExtra(EXTRA_CAMERA2, true)
    }

    private fun captureToTexture(): Boolean {
        return intent.getBooleanExtra(EXTRA_CAPTURETOTEXTURE_ENABLED, false)
    }

    private fun createCameraCapturer(enumerator: CameraEnumerator): VideoCapturer? {
        val deviceNames = enumerator.deviceNames

        // First, try to find front facing camera
        Logging.d(TAG, "Looking for front facing cameras.")
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating front facing camera capturer.")
                val videoCapturer = enumerator.createCapturer(deviceName, null)

                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }

        // Front facing camera not found, try something else
        Logging.d(TAG, "Looking for other cameras.")
        for (deviceName in deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                Logging.d(TAG, "Creating other camera capturer.")
                val videoCapturer = enumerator.createCapturer(deviceName, null)

                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }

        return null
    }

    @TargetApi(21)
    private fun createScreenCapturer(): VideoCapturer? {
        if (mediaProjectionPermissionResultCode != Activity.RESULT_OK) {
            reportError("User didn't give permission to capture the screen.")
            return null
        }
        return ScreenCapturerAndroid(
            mediaProjectionPermissionResultData, object : MediaProjection.Callback() {
                override fun onStop() {
                    reportError("User revoked permission to capture the screen.")
                }
            })
    }

    // Activity interfaces
    public override fun onStop() {
        super.onStop()
        activityRunning = false
        // Don't stop the video when using screencapture to allow user to show other apps to the remote
        // end.
        if (peerConnectionClient != null && !screencaptureEnabled) {
            peerConnectionClient!!.stopVideoSource()
        }
        if (cpuMonitor != null) {
            cpuMonitor!!.pause()
        }
    }

    public override fun onStart() {
        super.onStart()
        activityRunning = true
        // Video is not paused for screencapture. See onPause.
        if (peerConnectionClient != null && !screencaptureEnabled) {
            peerConnectionClient!!.startVideoSource()
        }
        if (cpuMonitor != null) {
            cpuMonitor!!.resume()
        }
    }

    override fun onDestroy() {
        Thread.setDefaultUncaughtExceptionHandler(null)
        disconnect()
        if (logToast != null) {
            logToast!!.cancel()
        }
        activityRunning = false
        super.onDestroy()
    }

    // LoopbackCallFragment.OnCallEvents interface implementation.
    override fun onCallHangUp() {
        disconnect()
    }

    override fun onCameraSwitch() {
        if (peerConnectionClient != null) {
            peerConnectionClient!!.switchCamera()
        }
    }

    override fun onVideoScalingSwitch(scalingType: ScalingType) {
        fullscreenRenderer!!.setScalingType(scalingType)
    }

    override fun onCaptureFormatChange(width: Int, height: Int, framerate: Int) {
        if (peerConnectionClient != null) {
            peerConnectionClient!!.changeCaptureFormat(width, height, framerate)
        }
    }

    override fun onToggleMic(): Boolean {
        if (peerConnectionClient != null) {
            micEnabled = !micEnabled
            peerConnectionClient!!.setAudioEnabled(micEnabled)
        }
        return micEnabled
    }

    // Helper functions.
    private fun toggleCallControlFragmentVisibility() {
        if (!connected || !callFragment!!.isAdded) {
            return
        }
        // Show/hide call control fragment
        callControlFragmentVisible = !callControlFragmentVisible
        val ft = fragmentManager.beginTransaction()
        if (callControlFragmentVisible) {
            ft.show(callFragment)
            ft.show(hudFragment)
        } else {
            ft.hide(callFragment)
            ft.hide(hudFragment)
        }
        ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
        ft.commit()
    }

    private fun startCall() {
        if (appRtcClient == null) {
            Log.e(TAG, "AppRTC client is not allocated for a call.")
            return
        }
        callStartedTimeMs = System.currentTimeMillis()

        // Start room connection.
        logAndToast(getString(R.string.connecting_to, roomConnectionParameters!!.roomUrl))
        appRtcClient!!.connectToRoom(roomConnectionParameters)

        // Create and audio manager that will take care of audio routing,
        // audio modes, audio device enumeration etc.
        audioManager = AppRTCAudioManager.create(applicationContext)
        // Store existing audio settings and change audio mode to
        // MODE_IN_COMMUNICATION for best possible VoIP performance.
        Log.d(TAG, "Starting the audio manager...")
        audioManager!!.start { audioDevice, availableAudioDevices ->
            // This method will be called each time the number of available audio
            // devices has changed.
            onAudioManagerDevicesChanged(audioDevice, availableAudioDevices)
        }
    }

    // Should be called from UI thread
    private fun callConnected() {
        val delta = System.currentTimeMillis() - callStartedTimeMs
        Log.i(TAG, "Call connected: delay=" + delta + "ms")
        if (peerConnectionClient == null || isError) {
            Log.w(TAG, "Call is connected in closed or error state")
            return
        }
        // Enable statistics callback.
        peerConnectionClient!!.enableStatsEvents(true, STAT_CALLBACK_PERIOD)
        setSwappedFeeds(false /* isSwappedFeeds */)
    }

    // This method is called when the audio manager reports audio device change,
    // e.g. from wired headset to speakerphone.
    private fun onAudioManagerDevicesChanged(
        device: AudioDevice, availableDevices: Set<AudioDevice>
    ) {
        Log.d(
            TAG, ("onAudioManagerDevicesChanged: " + availableDevices + ", "
                    + "selected: " + device)
        )
        // TODO(henrika): add callback handler.
    }

    // Disconnect from remote resources, dispose of local resources, and exit.
    private fun disconnect() {
        activityRunning = false
        remoteProxyRenderer.setTarget(null)
        localProxyVideoSink.setTarget(null)
        if (appRtcClient != null) {
            appRtcClient!!.disconnectFromRoom()
            appRtcClient = null
        }
        if (pipRenderer != null) {
            pipRenderer!!.release()
            pipRenderer = null
        }
        if (videoFileRenderer != null) {
            videoFileRenderer!!.release()
            videoFileRenderer = null
        }
        if (fullscreenRenderer != null) {
            fullscreenRenderer!!.release()
            fullscreenRenderer = null
        }
        if (peerConnectionClient != null) {
            peerConnectionClient!!.close()
            peerConnectionClient = null
        }
        if (audioManager != null) {
            audioManager!!.stop()
            audioManager = null
        }
        if (connected && !isError) {
            setResult(Activity.RESULT_OK)
        } else {
            setResult(Activity.RESULT_CANCELED)
        }
        finish()
    }

    private fun disconnectWithErrorMessage(errorMessage: String) {
        if (commandLineRun || !activityRunning) {
            Log.e(TAG, "Critical error: $errorMessage")
            disconnect()
        } else {
            AlertDialog.Builder(this)
                .setTitle(getText(R.string.channel_error_title))
                .setMessage(errorMessage)
                .setCancelable(false)
                .setNeutralButton(
                    R.string.ok,
                    DialogInterface.OnClickListener { dialog, id ->
                        dialog.cancel()
                        disconnect()
                    })
                .create()
                .show()
        }
    }

    // Log |msg| and Toast about it.
    private fun logAndToast(msg: String) {
        Log.d(TAG, msg)
        if (logToast != null) {
            logToast!!.cancel()
        }
        logToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT)
        logToast!!.show()
    }

    private fun reportError(description: String) {
        runOnUiThread {
            if (!isError) {
                isError = true
                disconnectWithErrorMessage(description)
            }
        }
    }

    private fun createVideoCapturer(): VideoCapturer? {
        val videoCapturer: VideoCapturer?
        val videoFileAsCamera = intent.getStringExtra(EXTRA_VIDEO_FILE_AS_CAMERA)
        if (videoFileAsCamera != null) {
            try {
                videoCapturer = FileVideoCapturer(videoFileAsCamera)
            } catch (e: IOException) {
                reportError("Failed to open video file for emulated camera")
                return null
            }

        } else if (screencaptureEnabled) {
            return createScreenCapturer()
        } else if (useCamera2()) {
            if (!captureToTexture()) {
                reportError(getString(R.string.camera2_texture_only_error))
                return null
            }

            Logging.d(TAG, "Creating capturer using camera2 API.")
            videoCapturer = createCameraCapturer(Camera2Enumerator(this))
        } else {
            Logging.d(TAG, "Creating capturer using camera1 API.")
            videoCapturer = createCameraCapturer(Camera1Enumerator(captureToTexture()))
        }
        if (videoCapturer == null) {
            reportError("Failed to open camera")
            return null
        }
        return videoCapturer
    }

    private fun setSwappedFeeds(isSwappedFeeds: Boolean) {
        Logging.d(TAG, "setSwappedFeeds: $isSwappedFeeds")
        this.isSwappedFeeds = isSwappedFeeds
        localProxyVideoSink.setTarget(if (isSwappedFeeds) fullscreenRenderer else pipRenderer)
        remoteProxyRenderer.setTarget(if (isSwappedFeeds) pipRenderer else fullscreenRenderer)
        fullscreenRenderer!!.setMirror(isSwappedFeeds)
        pipRenderer!!.setMirror(!isSwappedFeeds)
    }

    // -----Implementation of AppRTCClient.AppRTCSignalingEvents ---------------
    // All callbacks are invoked from websocket signaling looper thread and
    // are routed to UI thread.
    private fun onConnectedToRoomInternal(params: SignalingParameters) {
        val delta = System.currentTimeMillis() - callStartedTimeMs

        signalingParameters = params
        logAndToast("Creating peer connection, delay=" + delta + "ms")
        var videoCapturer: VideoCapturer? = null
        if (peerConnectionParameters!!.videoCallEnabled) {
            videoCapturer = createVideoCapturer()
        }
        peerConnectionClient!!.createPeerConnection(
            localProxyVideoSink, remoteSinks, videoCapturer, signalingParameters
        )

        if (signalingParameters!!.initiator) {
            logAndToast("Creating OFFER...")
            // Create offer. Offer SDP will be sent to answering client in
            // PeerConnectionEvents.onLocalDescription event.
            peerConnectionClient!!.createOffer()
        } else {
            if (params.offerSdp != null) {
                peerConnectionClient!!.setRemoteDescription(params.offerSdp)
                logAndToast("Creating ANSWER...")
                // Create answer. Answer SDP will be sent to offering client in
                // PeerConnectionEvents.onLocalDescription event.
                peerConnectionClient!!.createAnswer()
            }
            if (params.iceCandidates != null) {
                // Add remote ICE candidates from room.
                for (iceCandidate in params.iceCandidates) {
                    peerConnectionClient!!.addRemoteIceCandidate(iceCandidate)
                }
            }
        }
    }

    override fun onConnectedToRoom(params: SignalingParameters) {
        runOnUiThread { onConnectedToRoomInternal(params) }
    }

    override fun onRemoteDescription(sdp: SessionDescription) {
        val delta = System.currentTimeMillis() - callStartedTimeMs
        runOnUiThread(Runnable {
            if (peerConnectionClient == null) {
                Log.e(TAG, "Received remote SDP for non-initilized peer connection.")
                return@Runnable
            }
            logAndToast("Received remote " + sdp.type + ", delay=" + delta + "ms")
            peerConnectionClient!!.setRemoteDescription(sdp)
            if (!signalingParameters!!.initiator) {
                logAndToast("Creating ANSWER...")
                // Create answer. Answer SDP will be sent to offering client in
                // PeerConnectionEvents.onLocalDescription event.
                peerConnectionClient!!.createAnswer()
            }
        })
    }

    override fun onRemoteIceCandidate(candidate: IceCandidate) {
        runOnUiThread(Runnable {
            if (peerConnectionClient == null) {
                Log.e(TAG, "Received ICE candidate for a non-initialized peer connection.")
                return@Runnable
            }
            peerConnectionClient!!.addRemoteIceCandidate(candidate)
        })
    }

    override fun onRemoteIceCandidatesRemoved(candidates: Array<IceCandidate>) {
        runOnUiThread(Runnable {
            if (peerConnectionClient == null) {
                Log.e(TAG, "Received ICE candidate removals for a non-initialized peer connection.")
                return@Runnable
            }
            peerConnectionClient!!.removeRemoteIceCandidates(candidates)
        })
    }

    override fun onChannelClose() {
        runOnUiThread {
            logAndToast("Remote end hung up; dropping PeerConnection")
            disconnect()
        }
    }

    override fun onChannelError(description: String) {
        reportError(description)
    }

    // -----Implementation of PeerConnectionClient.PeerConnectionEvents.---------
    // Send local peer connection SDP and ICE candidates to remote party.
    // All callbacks are invoked from peer connection client looper thread and
    // are routed to UI thread.
    override fun onLocalDescription(sdp: SessionDescription) {
        val delta = System.currentTimeMillis() - callStartedTimeMs
        runOnUiThread {
            if (appRtcClient != null) {
                logAndToast("Sending " + sdp.type + ", delay=" + delta + "ms")
                if (signalingParameters!!.initiator) {
                    appRtcClient!!.sendOfferSdp(sdp)
                } else {
                    appRtcClient!!.sendAnswerSdp(sdp)
                }
            }
            if (peerConnectionParameters!!.videoMaxBitrate > 0) {
                Log.d(TAG, "Set video maximum bitrate: " + peerConnectionParameters!!.videoMaxBitrate)
                peerConnectionClient!!.setVideoMaxBitrate(peerConnectionParameters!!.videoMaxBitrate)
            }
        }
    }

    override fun onIceCandidate(candidate: IceCandidate) {
        runOnUiThread {
            if (appRtcClient != null) {
                appRtcClient!!.sendLocalIceCandidate(candidate)
            }
        }
    }

    override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {
        runOnUiThread {
            if (appRtcClient != null) {
                appRtcClient!!.sendLocalIceCandidateRemovals(candidates)
            }
        }
    }

    override fun onIceConnected() {
        val delta = System.currentTimeMillis() - callStartedTimeMs
        runOnUiThread { logAndToast("ICE connected, delay=" + delta + "ms") }
    }

    override fun onIceDisconnected() {
        runOnUiThread { logAndToast("ICE disconnected") }
    }

    override fun onConnected() {
        val delta = System.currentTimeMillis() - callStartedTimeMs
        runOnUiThread {
            logAndToast("DTLS connected, delay=" + delta + "ms")
            connected = true
            callConnected()
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            logAndToast("DTLS disconnected")
            connected = false
            disconnect()
        }
    }

    override fun onPeerConnectionClosed() {}

    override fun onPeerConnectionStatsReady(reports: Array<StatsReport>) {
        runOnUiThread {
            if (!isError && connected) {
                hudFragment!!.updateEncoderStatistics(reports)
            }
        }
    }

    override fun onPeerConnectionError(description: String) {
        reportError(description)
    }

    companion object {
        private val TAG = "CallRTCClient"

        val EXTRA_ROOMID = "org.appspot.apprtc.ROOMID"
        val EXTRA_URLPARAMETERS = "org.appspot.apprtc.URLPARAMETERS"
        val EXTRA_LOOPBACK = "org.appspot.apprtc.LOOPBACK"
        val EXTRA_VIDEO_CALL = "org.appspot.apprtc.VIDEO_CALL"
        val EXTRA_SCREENCAPTURE = "org.appspot.apprtc.SCREENCAPTURE"
        val EXTRA_CAMERA2 = "org.appspot.apprtc.CAMERA2"
        val EXTRA_VIDEO_WIDTH = "org.appspot.apprtc.VIDEO_WIDTH"
        val EXTRA_VIDEO_HEIGHT = "org.appspot.apprtc.VIDEO_HEIGHT"
        val EXTRA_VIDEO_FPS = "org.appspot.apprtc.VIDEO_FPS"
        val EXTRA_VIDEO_CAPTUREQUALITYSLIDER_ENABLED = "org.appsopt.apprtc.VIDEO_CAPTUREQUALITYSLIDER"
        val EXTRA_VIDEO_BITRATE = "org.appspot.apprtc.VIDEO_BITRATE"
        val EXTRA_VIDEOCODEC = "org.appspot.apprtc.VIDEOCODEC"
        val EXTRA_HWCODEC_ENABLED = "org.appspot.apprtc.HWCODEC"
        val EXTRA_CAPTURETOTEXTURE_ENABLED = "org.appspot.apprtc.CAPTURETOTEXTURE"
        val EXTRA_FLEXFEC_ENABLED = "org.appspot.apprtc.FLEXFEC"
        val EXTRA_AUDIO_BITRATE = "org.appspot.apprtc.AUDIO_BITRATE"
        val EXTRA_AUDIOCODEC = "org.appspot.apprtc.AUDIOCODEC"
        val EXTRA_NOAUDIOPROCESSING_ENABLED = "org.appspot.apprtc.NOAUDIOPROCESSING"
        val EXTRA_AECDUMP_ENABLED = "org.appspot.apprtc.AECDUMP"
        val EXTRA_SAVE_INPUT_AUDIO_TO_FILE_ENABLED = "org.appspot.apprtc.SAVE_INPUT_AUDIO_TO_FILE"
        val EXTRA_OPENSLES_ENABLED = "org.appspot.apprtc.OPENSLES"
        val EXTRA_DISABLE_BUILT_IN_AEC = "org.appspot.apprtc.DISABLE_BUILT_IN_AEC"
        val EXTRA_DISABLE_BUILT_IN_AGC = "org.appspot.apprtc.DISABLE_BUILT_IN_AGC"
        val EXTRA_DISABLE_BUILT_IN_NS = "org.appspot.apprtc.DISABLE_BUILT_IN_NS"
        val EXTRA_DISABLE_WEBRTC_AGC_AND_HPF = "org.appspot.apprtc.DISABLE_WEBRTC_GAIN_CONTROL"
        val EXTRA_DISPLAY_HUD = "org.appspot.apprtc.DISPLAY_HUD"
        val EXTRA_TRACING = "org.appspot.apprtc.TRACING"
        val EXTRA_CMDLINE = "org.appspot.apprtc.CMDLINE"
        val EXTRA_RUNTIME = "org.appspot.apprtc.RUNTIME"
        val EXTRA_VIDEO_FILE_AS_CAMERA = "org.appspot.apprtc.VIDEO_FILE_AS_CAMERA"
        val EXTRA_SAVE_REMOTE_VIDEO_TO_FILE = "org.appspot.apprtc.SAVE_REMOTE_VIDEO_TO_FILE"
        val EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_WIDTH = "org.appspot.apprtc.SAVE_REMOTE_VIDEO_TO_FILE_WIDTH"
        val EXTRA_SAVE_REMOTE_VIDEO_TO_FILE_HEIGHT = "org.appspot.apprtc.SAVE_REMOTE_VIDEO_TO_FILE_HEIGHT"
        val EXTRA_USE_VALUES_FROM_INTENT = "org.appspot.apprtc.USE_VALUES_FROM_INTENT"
        val EXTRA_DATA_CHANNEL_ENABLED = "org.appspot.apprtc.DATA_CHANNEL_ENABLED"
        val EXTRA_ORDERED = "org.appspot.apprtc.ORDERED"
        val EXTRA_MAX_RETRANSMITS_MS = "org.appspot.apprtc.MAX_RETRANSMITS_MS"
        val EXTRA_MAX_RETRANSMITS = "org.appspot.apprtc.MAX_RETRANSMITS"
        val EXTRA_PROTOCOL = "org.appspot.apprtc.PROTOCOL"
        val EXTRA_NEGOTIATED = "org.appspot.apprtc.NEGOTIATED"
        val EXTRA_ID = "org.appspot.apprtc.ID"
        val EXTRA_ENABLE_RTCEVENTLOG = "org.appspot.apprtc.ENABLE_RTCEVENTLOG"
        val EXTRA_USE_LEGACY_AUDIO_DEVICE = "org.appspot.apprtc.USE_LEGACY_AUDIO_DEVICE"

        private val CAPTURE_PERMISSION_REQUEST_CODE = 1

        // List of mandatory application permissions.
        private val MANDATORY_PERMISSIONS = arrayOf(
            "android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.RECORD_AUDIO",
            "android.permission.INTERNET"
        )

        // Peer connection statistics callback period in ms.
        private val STAT_CALLBACK_PERIOD = 1000
        private var mediaProjectionPermissionResultData: Intent? = null
        private var mediaProjectionPermissionResultCode: Int = 0

        private val systemUiVisibility: Int
            @TargetApi(19)
            get() {
                var flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    flags = flags or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                }
                return flags
            }
    }
}
