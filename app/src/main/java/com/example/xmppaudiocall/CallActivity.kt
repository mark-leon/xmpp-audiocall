package com.example.xmppaudiocall

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

import org.webrtc.PeerConnection

class CallActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CallActivity"
    }

    private lateinit var tvCallStatus: TextView
    private lateinit var tvContactName: TextView
    private lateinit var tvDuration: TextView
    private lateinit var btnEndCall: Button
    private lateinit var btnMute: Button

    private lateinit var webrtcManager: WebRTCCallManager
    private lateinit var xmppManager: XMPPCallManager

    private var callType: String = ""
    private var contactJid: String = ""
    private var sessionId: String = ""
    private var isMuted = false
    private var isCallConnected = false

    private var callStartTime: Long = 0
    private var durationHandler: android.os.Handler? = null
    private var durationRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call)

        initializeViews()
        extractIntentData()
        initializeManagers()
        setupCall()
    }

    private fun initializeViews() {
        tvCallStatus = findViewById(R.id.tvCallStatus)
        tvContactName = findViewById(R.id.tvContactName)
        tvDuration = findViewById(R.id.tvDuration)
        btnEndCall = findViewById(R.id.btnEndCall)
        btnMute = findViewById(R.id.btnMute)

        btnEndCall.setOnClickListener {
            endCall()
        }

        btnMute.setOnClickListener {
            toggleMute()
        }
    }

    private fun extractIntentData() {
        callType = intent.getStringExtra("call_type") ?: "outgoing"
        contactJid = intent.getStringExtra("contact_jid") ?: ""
        sessionId = intent.getStringExtra("session_id") ?: ""

        tvContactName.text = contactJid
    }

    private fun initializeManagers() {
        webrtcManager = WebRTCCallManager(this)
        xmppManager = (application as XMPPApplication).xmppManager

        setupWebRTCListener()
        setupXMPPListener()
    }

    private fun setupWebRTCListener() {
        webrtcManager.setRTCListener(object : WebRTCCallManager.RTCListener {
            override fun onLocalSdpCreated(sdp: String, type: String) {
                Log.d(TAG, "Local SDP created: $type")

                when (type.lowercase()) {
                    "offer" -> {
                        // Send offer to remote peer via XMPP
                        xmppManager.initiateCall(contactJid, sdp)
                        tvCallStatus.text = "Calling..."
                    }
                    "answer" -> {
                        // Send answer to remote peer via XMPP
                        xmppManager.acceptCall(sessionId, sdp)
                        tvCallStatus.text = "Connecting..."
                    }
                }
            }

            override fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
                Log.d(TAG, "New ICE candidate")
                xmppManager.sendIceCandidate(candidate, sdpMid, sdpMLineIndex)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                runOnUiThread {
                    when (state) {
                        PeerConnection.IceConnectionState.CHECKING -> {
                            tvCallStatus.text = "Connecting..."
                        }
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            tvCallStatus.text = "Connected"
                        }
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            tvCallStatus.text = "Call in progress"
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            tvCallStatus.text = "Disconnected"
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            tvCallStatus.text = "Connection failed"
                            Toast.makeText(this@CallActivity, "Call connection failed", Toast.LENGTH_SHORT).show()
                        }
                        PeerConnection.IceConnectionState.CLOSED -> {
                            tvCallStatus.text = "Call ended"
                        }
                        else -> {}
                    }
                }
            }

            override fun onCallConnected() {
                runOnUiThread {
                    isCallConnected = true
                    tvCallStatus.text = "Call in progress"
                    Toast.makeText(this@CallActivity, "Call connected", Toast.LENGTH_SHORT).show()
                    startCallDurationTimer()
                }
            }

            override fun onCallDisconnected() {
                runOnUiThread {
                    isCallConnected = false
                    tvCallStatus.text = "Call disconnected"
                    stopCallDurationTimer()
                }
            }
        })
    }

    private fun setupXMPPListener() {
        xmppManager.setCallListener(object : XMPPCallManager.CallListener {
            override fun onIncomingCall(from: String, sessionId: String, sdpOffer: String) {
                // Already handled in MainActivity
            }

            override fun onCallAccepted(sessionId: String, sdpAnswer: String) {
                Log.d(TAG, "Call accepted, handling remote answer")
                webrtcManager.handleRemoteAnswer(sdpAnswer)
            }

            override fun onCallRejected(sessionId: String) {
                runOnUiThread {
                    Toast.makeText(this@CallActivity, "Call rejected", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }

            override fun onCallEnded(sessionId: String) {
                runOnUiThread {
                    Toast.makeText(this@CallActivity, "Call ended by remote peer", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }

            override fun onIceCandidate(
                sessionId: String,
                candidate: String,
                sdpMid: String,
                sdpMLineIndex: Int
            ) {
                Log.d(TAG, "Received remote ICE candidate")
                webrtcManager.addIceCandidate(candidate, sdpMid, sdpMLineIndex)
            }

            override fun onConnectionStateChanged(state: XMPPCallManager.ConnectionState) {
                // Handle XMPP connection state changes if needed
            }
        })
    }

    private fun setupCall() {
        webrtcManager.initializePeerConnection()

        when (callType) {
            "outgoing" -> {
                tvCallStatus.text = "Starting call..."
                webrtcManager.startCall()
            }
            "incoming" -> {
                tvCallStatus.text = "Accepting call..."
                val sdpOffer = intent.getStringExtra("sdp_offer") ?: ""
                webrtcManager.acceptCall()
                webrtcManager.handleRemoteOffer(sdpOffer)
            }
        }
    }

    private fun toggleMute() {
        isMuted = !isMuted
        webrtcManager.toggleMute(isMuted)

        btnMute.text = if (isMuted) "Unmute" else "Mute"

        Toast.makeText(
            this,
            if (isMuted) "Microphone muted" else "Microphone unmuted",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun startCallDurationTimer() {
        callStartTime = System.currentTimeMillis()
        durationHandler = android.os.Handler(mainLooper)

        durationRunnable = object : Runnable {
            override fun run() {
                val duration = System.currentTimeMillis() - callStartTime
                val seconds = (duration / 1000) % 60
                val minutes = (duration / (1000 * 60)) % 60
                val hours = (duration / (1000 * 60 * 60))

                val timeString = if (hours > 0) {
                    String.format("%02d:%02d:%02d", hours, minutes, seconds)
                } else {
                    String.format("%02d:%02d", minutes, seconds)
                }

                tvDuration.text = timeString
                durationHandler?.postDelayed(this, 1000)
            }
        }

        durationHandler?.post(durationRunnable!!)
    }

    private fun stopCallDurationTimer() {
        durationRunnable?.let {
            durationHandler?.removeCallbacks(it)
        }
        durationHandler = null
        durationRunnable = null
    }

    private fun endCall() {
        val currentSessionId = xmppManager.getCurrentSessionId()

        if (!currentSessionId.isNullOrEmpty()) {
            xmppManager.endCall(currentSessionId)
        } else if (sessionId.isNotEmpty()) {
            xmppManager.endCall(sessionId)
        }

        webrtcManager.endCall()
        stopCallDurationTimer()

        Toast.makeText(this, "Call ended", Toast.LENGTH_SHORT).show()
        finish()
    }

//    override fun onBackPressed() {
//        // Disable back button during call - user must use end call button
//        Toast.makeText(this, "Please use End Call button", Toast.LENGTH_SHORT).show()
//    }

    override fun onDestroy() {
        super.onDestroy()
        stopCallDurationTimer()

        if (isFinishing) {
            webrtcManager.endCall()
        }
    }
}