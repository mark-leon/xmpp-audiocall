package com.example.xmppaudiocall

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat


class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.INTERNET
        )
    }

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etServer: EditText
    private lateinit var etContact: EditText
    private lateinit var btnLogin: Button
    private lateinit var btnCall: Button
    private lateinit var tvStatus: TextView

    private lateinit var xmppManager: XMPPCallManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Check and request permissions
        if (!hasPermissions()) {
            requestPermissions()
        }

        initializeViews()
        initializeManagers()
        setupClickListeners()
    }

    private fun hasPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permissions required for audio calls", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun initializeViews() {
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        etServer = findViewById(R.id.etServer)
        etContact = findViewById(R.id.etContact)
        btnLogin = findViewById(R.id.btnLogin)
        btnCall = findViewById(R.id.btnCall)
        tvStatus = findViewById(R.id.tvStatus)

        btnCall.isEnabled = false
    }

    private fun initializeManagers() {
        xmppManager = XMPPCallManager(this)

        xmppManager.setCallListener(object : XMPPCallManager.CallListener {
            override fun onIncomingCall(from: String, sessionId: String, sdpOffer: String) {
                runOnUiThread {
                    showIncomingCallDialog(from, sessionId, sdpOffer)
                }
            }

            override fun onCallAccepted(sessionId: String, sdpAnswer: String) {
                runOnUiThread {
                    tvStatus.text = "Call accepted"
                    // WebRTC manager in CallActivity will handle the answer
                }
            }

            override fun onCallRejected(sessionId: String) {
                runOnUiThread {
                    tvStatus.text = "Call rejected"
                    Toast.makeText(this@MainActivity, "Call was rejected", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onCallEnded(sessionId: String) {
                runOnUiThread {
                    tvStatus.text = "Call ended"
                    Toast.makeText(this@MainActivity, "Call ended", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onIceCandidate(
                sessionId: String,
                candidate: String,
                sdpMid: String,
                sdpMLineIndex: Int
            ) {
                // ICE candidates will be handled in CallActivity
            }

            override fun onConnectionStateChanged(state: XMPPCallManager.ConnectionState) {
                runOnUiThread {
                    when (state) {
                        XMPPCallManager.ConnectionState.DISCONNECTED -> {
                            tvStatus.text = "Status: Disconnected"
                            btnCall.isEnabled = false
                            btnLogin.isEnabled = true
                        }
                        XMPPCallManager.ConnectionState.CONNECTING -> {
                            tvStatus.text = "Status: Connecting..."
                            btnLogin.isEnabled = false
                        }
                        XMPPCallManager.ConnectionState.CONNECTED -> {
                            tvStatus.text = "Status: Connected"
                        }
                        XMPPCallManager.ConnectionState.AUTHENTICATED -> {
                            tvStatus.text = "Status: Authenticated ✓"
                            btnCall.isEnabled = true
                            btnLogin.isEnabled = false
                        }
                        XMPPCallManager.ConnectionState.ERROR -> {
                            tvStatus.text = "Status: Connection Error"
                            btnCall.isEnabled = false
                            btnLogin.isEnabled = true
                        }
                    }
                }
            }
        })

        // Store XMPP manager globally
        (application as XMPPApplication).xmppManager = xmppManager
    }

    private fun setupClickListeners() {
        btnLogin.setOnClickListener {
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()
            val server = etServer.text.toString().trim().ifEmpty { "your-ejabberd-server.com" }

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please enter username and password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            connectToXMPP(username, password, server)
        }

        btnCall.setOnClickListener {
            val contact = etContact.text.toString().trim()

            if (contact.isEmpty()) {
                Toast.makeText(this, "Please enter contact JID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!contact.contains("@")) {
                Toast.makeText(this, "Contact must be in format: user@server", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            startOutgoingCall(contact)
        }
    }

    private fun connectToXMPP(username: String, password: String, server: String) {
        Thread {
            try {
                xmppManager.connect(username, password, server)
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "Connection failed: ${e.message}"
                    Toast.makeText(this, "Failed to connect: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showIncomingCallDialog(from: String, sessionId: String, sdpOffer: String) {
        AlertDialog.Builder(this)
            .setTitle("Incoming Call")
            .setMessage("Call from:\n$from")
            .setPositiveButton("Accept") { dialog, _ ->
                dialog.dismiss()
                startIncomingCall(from, sessionId, sdpOffer)
            }
            .setNegativeButton("Reject") { dialog, _ ->
                dialog.dismiss()
                xmppManager.endCall(sessionId)
                Toast.makeText(this, "Call rejected", Toast.LENGTH_SHORT).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun startOutgoingCall(contactJid: String) {
        val intent = Intent(this, CallActivity::class.java).apply {
            putExtra("call_type", "outgoing")
            putExtra("contact_jid", contactJid)
        }
        startActivity(intent)
    }

    private fun startIncomingCall(from: String, sessionId: String, sdpOffer: String) {
        val intent = Intent(this, CallActivity::class.java).apply {
            putExtra("call_type", "incoming")
            putExtra("contact_jid", from)
            putExtra("session_id", sessionId)
            putExtra("sdp_offer", sdpOffer)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            xmppManager.disconnect()
        }
    }
}