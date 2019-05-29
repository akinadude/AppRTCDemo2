package ru.akinadude.apprtcdemo2.kotlin

import android.annotation.TargetApi
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.support.v4.app.Fragment
import android.util.Log
import android.view.*
import android.widget.Toast
import kotlinx.android.synthetic.main.fragment_call2.*
import org.webrtc.*
import ru.akinadude.apprtcdemo2.*
import java.util.*

import ru.akinadude.apprtcdemo2.R

class CallFragment : Fragment(), CallEvents, PeerConnectionClient.PeerConnectionEvents {

    /*
    ConnectActivity starts CallActivity in connectToRoom method
    and passes many parameters into intent.

    CallActivity contains two org.webrtc.SurfaceViewRenderer in xml for rendering user's video and remote peer video.
    This activity does peer connection call setup or call waiting
    and starts CallFragment which contains views for call management.

    CallActivity.OnCreate
    - startCall
    -- appRtcClient.connectToRoom
    --- WebSocketRTCClient.connectToRoomInternal (or on another implementation)
    ---- RoomParametersFetcher(connectionUrl, null, callbacks).makeRequest()
    ----- roomHttpResponseParse (in case of succeed request)
    ------ формируем SignalingParameters, затем зовем WebSocketRTCClient.onSignalingParametersReady
    ------- WebSocketRTCClient.signalingParametersReady
    -------- events.onConnectedToRoom(signalingParameters) -> CallActivity.onConnectedToRoom
    --------- CallActivity.onConnectedToRoomInternal
               Создаем PeerConnection, передаем ему SignalingParameters

                Если мы инициатор, то peerConnectionClient.createOffer(),
                иначе {
                    сеттим remote sdp peerConnectionClient.setRemoteDescription(params.offerSdp);
                    и создаем ответ peerConnectionClient.createAnswer();

                    Добавляем IceCandidates если они есть в сингалинг-параметрах
                    peerConnectionClient.addRemoteIceCandidate(iceCandidate);
                }
    -------- wsClient.connect(...) and wsClient.register(...)

    =====================================
    Отсылка оффера и получение ответа.
    =====================================
    peerConnectionClient.createOffer
    - peerConnection.createOffer(sdpObserver, sdpMediaConstraints)
        В первом параметре имплементация интерфейса SDPObserver с колбэками.
        Если успешно создался offer зовем SDPObserver.onCreateSuccess.
        Внутри зовем peerConnection.setLocalDescription(sdpObserver, sdp)

        Если description засетился успешно, то SDPObserver.onSetSuccess
        В нем зовем CallActivity.onLocalDescription
        В нем зовем WebSocketRTCClient.sendOfferSdp
        В нем зовем WebSocketRTCClient.sendPostMessage

    Далее в методе-handler'е WebSocketRTCClient.onWebSocketMessage зовется метод events.onRemoteDescription(sdp)
    для инициитора. Эта цепочка позовется, когда в вебсокет прилетит сообщение с типом sdp answer.
    Реализация метода в CallActivity.onRemoteDescription
    В нем зовем peerConnectionClient.setRemoteDescription(sdp)
    В нем зовем peerConnection.setRemoteDescription(sdpObserver, sdpRemote)
    Если успешно засеттился, то для инициатора зовется SdpObserver.onSetSuccess, в нем drainCandidates.

    =====================================
    Обмен IceCandidates'ами.
    =====================================
    //todo пока непонятно где стартует механизм, который тригерит отсылку IceCandidatе'ов
    PCObserver.onIceCandidate
    - CallActivity.onIceCandidate
    -- WebSocketRTCClient.sendLocalIceCandidate
        Если мы инициатор, то шлем Ice Candidate POST запросом sendPostMessage
        Иначе, зовем wsClient.send(json.toString())

    SignalingParameters — dto для сигналинга
    SignalingEvents — интерфейс, который имплементит CallActivity
    RoomConnectionParameters - ...

    Questions
    1. What is the difference between IceCandidate and PeerConnection.IceServer?
    2. Where
        onIceCandidate(final IceCandidate candidate)
        onIceConnected()
        onAddStream
            are called?
    3. How queuedRemoteCandidates are populated with IceCandidates?

    todo: what should one do to initiate a call?
    Нужно подключиться к комнате по вебсокету: WebSocketRTCClient метод connectToRoom
    В вебсокет будут прилетать события сигналинга.

    todo: завести инициализацию звонка в этом фрагменте

    */

    companion object {

        fun newInstance(): CallFragment {
            return CallFragment()
        }
    }

    private val CAPTURE_PERMISSION_REQUEST_CODE = 1

    // List of mandatory application permissions.
    private val MANDATORY_PERMISSIONS = arrayOf(
        "android.permission.MODIFY_AUDIO_SETTINGS",
        "android.permission.RECORD_AUDIO",
        "android.permission.INTERNET"
    )

    // Peer connection statistics callback period in ms.
    private val STAT_CALLBACK_PERIOD = 1000

    private val TAG = CallFragment::class.java.simpleName
    private val callEventsImplementation: CallEvents = this
    private var logToast: Toast? = null
    private var scalingType: RendererCommon.ScalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL
    private var videoCallEnabled = true
    private var rootEglBase: EglBase? = null
    private var peerConnectionClient: PeerConnectionClient? = null

    private val remoteProxyRenderer = ProxyVideoSink()
    private val localProxyVideoSink = ProxyVideoSink()
    private val remoteSinks = ArrayList<VideoSink>()
    private var peerConnectionParameters: PeerConnectionClient.PeerConnectionParameters? = null
    private var appRtcClient: AppRTCClient? = null
    private var signalingParameters: AppRTCClient.SignalingParameters? = null
    private var audioManager: AppRTCAudioManager? = null
    private var videoFileRenderer: VideoFileRenderer? = null
    private var roomConnectionParameters: AppRTCClient.RoomConnectionParameters? = null

    private var connected: Boolean = false
    private var isError: Boolean = false
    private var callControlFragmentVisible = true
    private var callStartedTimeMs: Long = 0
    private var micEnabled = true
    // True if local view is in the fullscreen renderer.
    private var isSwappedFeeds: Boolean = false
    private var screencaptureEnabled = false

    // -------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread.setDefaultUncaughtExceptionHandler(UnhandledExceptionHandler(activity))

        // Set window styles for fullscreen-window size. Needs to be done before
        // adding content.
        activity?.requestWindowFeature(Window.FEATURE_NO_TITLE)
        activity?.window?.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        activity?.window?.decorView?.systemUiVisibility = getSystemUiVisibility()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_call2, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        connected = false

        // Swap feeds on pip view click.
        pip_video_view.setOnClickListener { setSwappedFeeds(!isSwappedFeeds) }

        //turn on/off control buttons
        //fullscreen_video_view.setOnClickListener(listener)
        remoteSinks.add(remoteProxyRenderer)

        //val intent = getIntent()
        val eglBase = EglBase.create()

        // Create video renderers.
        pip_video_view.init(eglBase.eglBaseContext, null)
        pip_video_view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)

        fullscreen_video_view.init(eglBase.eglBaseContext, null)
        fullscreen_video_view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)

        pip_video_view.setZOrderMediaOverlay(true)
        pip_video_view.setEnableHardwareScaler(true /* enabled */)
        fullscreen_video_view.setEnableHardwareScaler(false /* enabled */)
        // Start with local feed in fullscreen and swap it to the pip when the call is connected.
        setSwappedFeeds(true)

        // Check for mandatory permissions.
        for (permission in MANDATORY_PERMISSIONS) {
            if (activity?.checkCallingOrSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                logAndToast("Permission $permission is not granted")
                activity?.setResult(Activity.RESULT_CANCELED)
                activity?.finish()
                return
            }
        }

        //todo нужно установить параметры, которые прокидываются в CallActivity в intent'е.
        //параметры соберем в MainFragment'e, передадим их сюда в аргументах
        // либо создать подготовительный фрагмент, в котором установить параметры.
    }

    // --- private methods ---
    @TargetApi(19)
    private fun getSystemUiVisibility(): Int {
        var flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            flags = flags or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        return flags
    }

    private fun setSwappedFeeds(isSwappedFeeds: Boolean) {
        Logging.d(TAG, "setSwappedFeeds: $isSwappedFeeds")
        this.isSwappedFeeds = isSwappedFeeds
        localProxyVideoSink.setTarget(if (isSwappedFeeds) fullscreen_video_view else pip_video_view)
        remoteProxyRenderer.setTarget(if (isSwappedFeeds) pip_video_view else fullscreen_video_view)
        fullscreen_video_view!!.setMirror(isSwappedFeeds)
        pip_video_view.setMirror(!isSwappedFeeds)
    }

    // Log |msg| and Toast about it.
    private fun logAndToast(msg: String) {
        Log.d(TAG, msg)
        if (logToast != null) {
            logToast?.cancel()
        }
        logToast = Toast.makeText(activity, msg, Toast.LENGTH_SHORT)
        logToast?.show()
    }

    // --- CallEvents ---
    override fun onCallHangUp() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onCameraSwitch() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onVideoScalingSwitch(scalingType: RendererCommon.ScalingType) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onCaptureFormatChange(width: Int, height: Int, framerate: Int) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onToggleMic(): Boolean {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    // --- PeerConnectionEvents ---
    override fun onLocalDescription(sdp: SessionDescription?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onIceCandidate(candidate: IceCandidate?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onIceConnected() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onIceDisconnected() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onConnected() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onDisconnected() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onPeerConnectionClosed() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onPeerConnectionStatsReady(reports: Array<out StatsReport>?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun onPeerConnectionError(description: String?) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}