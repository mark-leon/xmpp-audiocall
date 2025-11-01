package com.example.xmppaudiocall

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer

class MainActivity : AppCompatActivity() {

    // Login views
    private lateinit var llLogin: LinearLayout
    private lateinit var etServer: EditText
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnConnect: Button

    // Connected views
    private lateinit var llConnected: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvMyJid: TextView
    private lateinit var etRecipientJid: EditText
    private lateinit var cbVideoCall: CheckBox
    private lateinit var btnCall: Button
    private lateinit var llCallControls: LinearLayout
    private lateinit var tvCallStatus: TextView
    private lateinit var btnEndCall: Button
    private lateinit var btnDisconnect: Button

    // Video views
    private lateinit var llVideoViews: LinearLayout
    private lateinit var localVideoView: SurfaceViewRenderer
    private lateinit var remoteVideoView: SurfaceViewRenderer

    private var xmppClient: XMPPClient? = null
    private var webRTCClient: WebRTCClient? = null

    private var myJid: String = ""
    private var remoteJid: String? = null
    private var isCallActive = false
    private var isVideoCall = false
    private val iceCandidateQueue = mutableListOf<String>()

    private val PERMISSION_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        requestPermissions()
    }

    private fun initViews() {
        // Login views
        llLogin = findViewById(R.id.llLogin)
        etServer = findViewById(R.id.etServer)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        btnConnect = findViewById(R.id.btnConnect)

        // Connected views
        llConnected = findViewById(R.id.llConnected)
        tvStatus = findViewById(R.id.tvStatus)
        tvMyJid = findViewById(R.id.tvMyJid)
        etRecipientJid = findViewById(R.id.etRecipientJid)
        cbVideoCall = findViewById(R.id.cbVideoCall)
        btnCall = findViewById(R.id.btnCall)
        llCallControls = findViewById(R.id.llCallControls)
        tvCallStatus = findViewById(R.id.tvCallStatus)
        btnEndCall = findViewById(R.id.btnEndCall)
        btnDisconnect = findViewById(R.id.btnDisconnect)

        // Video views
        llVideoViews = findViewById(R.id.llVideoViews)
        localVideoView = findViewById(R.id.localVideoView)
        remoteVideoView = findViewById(R.id.remoteVideoView)

        btnConnect.setOnClickListener { connectToXMPP() }
        btnCall.setOnClickListener { initiateCall() }
        btnEndCall.setOnClickListener { endCall() }
        btnDisconnect.setOnClickListener { disconnectFromXMPP() }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.CAMERA
        )

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (!grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permissions required for audio/video call", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun connectToXMPP() {
        val server = etServer.text.toString().trim()
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString().trim()

        if (server.isEmpty() || username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            return
        }

        btnConnect.isEnabled = false
        btnConnect.text = "Connecting..."

        initializeXMPPClient()
        xmppClient?.connect(server, username, password)
    }

    private fun initializeXMPPClient() {
        xmppClient = XMPPClient(object : XMPPClient.XMPPListener {
            override fun onConnected(jid: String) {
                runOnUiThread {
                    myJid = jid
                    tvMyJid.text = "Your JID: $jid"
                    llLogin.visibility = View.GONE
                    llConnected.visibility = View.VISIBLE
                    Toast.makeText(this@MainActivity, "Connected to XMPP", Toast.LENGTH_SHORT).show()

                    initializeWebRTC()
                }
            }

            override fun onConnectionFailed(error: String) {
                runOnUiThread {
                    btnConnect.isEnabled = true
                    btnConnect.text = "Connect to XMPP"
                    Toast.makeText(this@MainActivity, "Connection failed: $error", Toast.LENGTH_LONG).show()
                }
            }

            override fun onMessageReceived(from: String, message: String) {
                handleSignalingMessage(from, message)
            }

            override fun onDisconnected() {
                runOnUiThread {
                    llLogin.visibility = View.VISIBLE
                    llConnected.visibility = View.GONE
                    btnConnect.isEnabled = true
                    btnConnect.text = "Connect to XMPP"
                    Toast.makeText(this@MainActivity, "Disconnected from XMPP", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun initializeWebRTC() {
        webRTCClient = WebRTCClient(applicationContext, object : WebRTCClient.WebRTCListener {
            override fun onIceCandidate(candidate: IceCandidate) {
                remoteJid?.let { jid ->
                    val candidateString = "${candidate.sdpMid}|${candidate.sdpMLineIndex}|${candidate.sdp}"
                    val json = JSONObject().apply {
                        put("type", "ice-candidate")
                        put("candidate", candidateString)
                    }
                    xmppClient?.sendMessage(jid, json.toString())
                }
            }

            override fun onConnectionStateChange(state: PeerConnection.PeerConnectionState) {
                runOnUiThread {
                    when (state) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            tvCallStatus.text = if (isVideoCall) "Video Call Connected" else "Audio Call Connected"
                            Toast.makeText(this@MainActivity, "Call connected!", Toast.LENGTH_SHORT).show()
                        }
                        PeerConnection.PeerConnectionState.CONNECTING -> {
                            tvCallStatus.text = "Connecting..."
                        }
                        PeerConnection.PeerConnectionState.DISCONNECTED,
                        PeerConnection.PeerConnectionState.FAILED,
                        PeerConnection.PeerConnectionState.CLOSED -> {
                            endCall()
                            Toast.makeText(this@MainActivity, "Call ended", Toast.LENGTH_SHORT).show()
                        }
                        else -> {}
                    }
                }
            }

            override fun onAddRemoteStream(stream: MediaStream) {
                runOnUiThread {
                    stream.videoTracks?.firstOrNull()?.addSink(remoteVideoView)
                }
            }
        })
    }

    private fun initiateCall() {
        val recipientJid = etRecipientJid.text.toString().trim()

        if (recipientJid.isEmpty()) {
            Toast.makeText(this, "Please enter recipient JID", Toast.LENGTH_SHORT).show()
            return
        }

        remoteJid = recipientJid
        isCallActive = true
        isVideoCall = cbVideoCall.isChecked
        llCallControls.visibility = View.VISIBLE
        tvCallStatus.text = if (isVideoCall) "Video Calling..." else "Calling..."

        // Show video views if video call
        if (isVideoCall) {
            llVideoViews.visibility = View.VISIBLE
        }

        webRTCClient?.createPeerConnection(isVideoCall)

        // Initialize local video view if video call
        if (isVideoCall) {
            webRTCClient?.initLocalVideoView(localVideoView)
            webRTCClient?.initRemoteVideoView(remoteVideoView)
        }

        webRTCClient?.createOffer { offer ->
            val json = JSONObject().apply {
                put("type", "offer")
                put("sdp", offer)
                put("isVideo", isVideoCall)
            }
            xmppClient?.sendMessage(recipientJid, json.toString())
        }
    }

    private fun handleSignalingMessage(from: String, message: String) {
        try {
            println("DEBUG: Received message from $from: $message")
            val json = JSONObject(message)
            val type = json.getString("type")

            when (type) {
                "offer" -> {
                    println("DEBUG: Received offer")
                    val sdp = json.getString("sdp")
                    val isVideo = json.optBoolean("isVideo", false)
                    runOnUiThread {
                        showIncomingCallDialog(from, sdp, isVideo)
                    }
                }
                "answer" -> {
                    println("DEBUG: Received answer")
                    val sdp = json.getString("sdp")
                    webRTCClient?.setRemoteDescription(sdp, SessionDescription.Type.ANSWER)

                    runOnUiThread {
                        iceCandidateQueue.forEach { candidate ->
                            webRTCClient?.addIceCandidate(candidate)
                        }
                        iceCandidateQueue.clear()
                    }
                }
                "ice-candidate" -> {
                    println("DEBUG: Received ICE candidate")
                    val candidate = json.getString("candidate")

                    val state = webRTCClient?.getConnectionState()
                    if (state != null && state != PeerConnection.PeerConnectionState.NEW) {
                        webRTCClient?.addIceCandidate(candidate)
                    } else {
                        iceCandidateQueue.add(candidate)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showIncomingCallDialog(fromJid: String, offer: String, isVideo: Boolean) {
        val callType = if (isVideo) "Video Call" else "Audio Call"
        AlertDialog.Builder(this)
            .setTitle("Incoming $callType")
            .setMessage("$callType from $fromJid")
            .setCancelable(false)
            .setPositiveButton("Accept") { _, _ ->
                acceptCall(fromJid, offer, isVideo)
            }
            .setNegativeButton("Reject") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun acceptCall(fromJid: String, offer: String, isVideo: Boolean) {
        remoteJid = fromJid
        isCallActive = true
        isVideoCall = isVideo
        llCallControls.visibility = View.VISIBLE
        tvCallStatus.text = "Connecting..."
        etRecipientJid.setText(fromJid)

        // Show video views if video call
        if (isVideoCall) {
            llVideoViews.visibility = View.VISIBLE
        }

        webRTCClient?.createPeerConnection(isVideoCall)

        // Initialize video views if video call
        if (isVideoCall) {
            webRTCClient?.initLocalVideoView(localVideoView)
            webRTCClient?.initRemoteVideoView(remoteVideoView)
        }

        webRTCClient?.setRemoteDescription(offer, SessionDescription.Type.OFFER)

        android.os.Handler(mainLooper).postDelayed({
            webRTCClient?.createAnswer { answer ->
                val json = JSONObject().apply {
                    put("type", "answer")
                    put("sdp", answer)
                }
                xmppClient?.sendMessage(fromJid, json.toString())

                iceCandidateQueue.forEach { candidate ->
                    webRTCClient?.addIceCandidate(candidate)
                }
                iceCandidateQueue.clear()
            }
        }, 100)
    }

    private fun endCall() {
        if (!isCallActive) return

        isCallActive = false
        webRTCClient?.close()
        remoteJid = null
        iceCandidateQueue.clear()

        runOnUiThread {
            llCallControls.visibility = View.GONE
            llVideoViews.visibility = View.GONE
            tvStatus.text = "Connected"

            // Release video views
            try {
                localVideoView.release()
                remoteVideoView.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun disconnectFromXMPP() {
        endCall()
        xmppClient?.disconnect()
        webRTCClient?.dispose()
    }

    override fun onDestroy() {
        super.onDestroy()
        xmppClient?.disconnect()
        webRTCClient?.dispose()
    }
}