package com.example.xmppaudiocall

import android.content.Context
import android.util.Log
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.StanzaListener
import org.jivesoftware.smack.filter.StanzaTypeFilter
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jxmpp.jid.impl.JidCreate
import org.json.JSONObject
import java.util.UUID

class XMPPCallManager(private val context: Context) {

    companion object {
        private const val TAG = "XMPPCallManager"
        private const val JINGLE_NAMESPACE = "urn:xmpp:jingle:1"
        private const val JINGLE_RTP_NAMESPACE = "urn:xmpp:jingle:apps:rtp:1"
    }

    private var connection: XMPPTCPConnection? = null
    private var callListener: CallListener? = null
    private var currentSessionId: String? = null
    private var currentCallJid: String? = null

    interface CallListener {
        fun onIncomingCall(from: String, sessionId: String, sdpOffer: String)
        fun onCallAccepted(sessionId: String, sdpAnswer: String)
        fun onCallRejected(sessionId: String)
        fun onCallEnded(sessionId: String)
        fun onIceCandidate(sessionId: String, candidate: String, sdpMid: String, sdpMLineIndex: Int)
        fun onConnectionStateChanged(state: ConnectionState)
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        AUTHENTICATED,
        ERROR
    }

    fun connect(username: String, password: String, server: String = "localhost") {
        try {
            callListener?.onConnectionStateChanged(ConnectionState.CONNECTING)

            val config = XMPPTCPConnectionConfiguration.builder()
                .setXmppDomain(server)
                .setHost("192.168.125.8")
                .setPort(5222)
                .setSecurityMode(ConnectionConfiguration.SecurityMode.disabled)
                .setCompressionEnabled(true)
                .setSendPresence(true)
                .build()

            connection = XMPPTCPConnection(config)
            connection?.connect()

            callListener?.onConnectionStateChanged(ConnectionState.CONNECTED)

            connection?.login(username, password)

            callListener?.onConnectionStateChanged(ConnectionState.AUTHENTICATED)

            setupListeners()

            Log.d(TAG, "Successfully connected and authenticated")

        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            callListener?.onConnectionStateChanged(ConnectionState.ERROR)
        }
    }

    private fun setupListeners() {
        connection?.addAsyncStanzaListener(object : StanzaListener {
            override fun processStanza(stanza: Stanza) {
                handleIncomingStanza(stanza)
            }
        }, StanzaTypeFilter(IQ::class.java))
    }

    private fun handleIncomingStanza(stanza: Stanza) {
        if (stanza !is IQ) return

        try {
            val stanzaXml = stanza.toXML().toString()
            Log.d(TAG, "Received stanza: $stanzaXml")

            when {
                stanzaXml.contains("session-initiate") -> {
                    handleSessionInitiate(stanza)
                }
                stanzaXml.contains("session-accept") -> {
                    handleSessionAccept(stanza)
                }
                stanzaXml.contains("session-terminate") -> {
                    handleSessionTerminate(stanza)
                }
                stanzaXml.contains("transport-info") -> {
                    handleTransportInfo(stanza)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling stanza", e)
        }
    }

    private fun handleSessionInitiate(stanza: IQ) {
        val from = stanza.from.toString()
        val sessionId = stanza.stanzaId

        // Extract SDP from stanza (simplified - in production use proper XML parsing)
        val sdpOffer = extractSdpFromStanza(stanza)

        currentSessionId = sessionId
        currentCallJid = from

        callListener?.onIncomingCall(from, sessionId, sdpOffer)
    }

    private fun handleSessionAccept(stanza: IQ) {
        val sessionId = stanza.stanzaId
        val sdpAnswer = extractSdpFromStanza(stanza)

        callListener?.onCallAccepted(sessionId, sdpAnswer)
    }

    private fun handleSessionTerminate(stanza: IQ) {
        val sessionId = stanza.stanzaId

        currentSessionId = null
        currentCallJid = null

        callListener?.onCallEnded(sessionId)
    }

    private fun handleTransportInfo(stanza: IQ) {
        val sessionId = stanza.stanzaId
        // Extract ICE candidate from stanza
        val candidateData = extractIceCandidateFromStanza(stanza)

        candidateData?.let {
            callListener?.onIceCandidate(
                sessionId,
                it["candidate"] as String,
                it["sdpMid"] as String,
                it["sdpMLineIndex"] as Int
            )
        }
    }

    fun initiateCall(toJid: String, sdpOffer: String) {
        val sessionId = generateSessionId()
        currentSessionId = sessionId
        currentCallJid = toJid

        val initiateStanza = createJingleInitiateStanza(toJid, sessionId, sdpOffer)
        connection?.sendStanza(initiateStanza)

        Log.d(TAG, "Call initiated to $toJid with session $sessionId")
    }

    private fun createJingleInitiateStanza(toJid: String, sessionId: String, sdpOffer: String): IQ {
        val iq = object : IQ("jingle", JINGLE_NAMESPACE) {
            override fun getIQChildElementBuilder(xml: IQChildElementXmlStringBuilder): IQChildElementXmlStringBuilder {
                xml.attribute("action", "session-initiate")
                xml.attribute("sid", sessionId)
                xml.rightAngleBracket()

                xml.openElement("content")
                xml.attribute("creator", "initiator")
                xml.attribute("name", "audio")
                xml.rightAngleBracket()

                xml.openElement("description")
                xml.attribute("xmlns", JINGLE_RTP_NAMESPACE)
                xml.attribute("media", "audio")
                xml.rightAngleBracket()

                xml.openElement("sdp")
                xml.append(sdpOffer)
                xml.closeElement("sdp")

                xml.closeElement("description")
                xml.closeElement("content")

                return xml
            }
        }

        iq.to = JidCreate.from(toJid)
        iq.type = IQ.Type.set
        iq.stanzaId = sessionId

        return iq
    }


    fun acceptCall(sessionId: String, sdpAnswer: String) {
        currentCallJid?.let { jid ->
            val acceptStanza = createJingleAcceptStanza(jid, sessionId, sdpAnswer)
            connection?.sendStanza(acceptStanza)

            Log.d(TAG, "Call accepted for session $sessionId")
        }
    }

    private fun createJingleAcceptStanza(toJid: String, sessionId: String, sdpAnswer: String): IQ {
        val iq = object : IQ("jingle", JINGLE_NAMESPACE) {
            override fun getIQChildElementBuilder(xml: IQChildElementXmlStringBuilder): IQChildElementXmlStringBuilder {
                xml.attribute("action", "session-accept")
                xml.attribute("sid", sessionId)
                xml.rightAngleBracket()

                xml.openElement("content")
                xml.attribute("creator", "responder")
                xml.attribute("name", "audio")
                xml.rightAngleBracket()

                xml.openElement("description")
                xml.attribute("xmlns", JINGLE_RTP_NAMESPACE)
                xml.attribute("media", "audio")
                xml.rightAngleBracket()

                xml.openElement("sdp")
                xml.append(sdpAnswer)
                xml.closeElement("sdp")

                xml.closeElement("description")
                xml.closeElement("content")

                return xml
            }
        }

        iq.to = JidCreate.from(toJid)
        iq.type = IQ.Type.set
        iq.stanzaId = sessionId

        return iq
    }

    fun sendIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        currentSessionId?.let { sessionId ->
            currentCallJid?.let { jid ->
                val transportStanza = createTransportInfoStanza(jid, sessionId, candidate, sdpMid, sdpMLineIndex)
                Log.d(transportStanza.toString(), "New ICE stanza")

                connection?.sendStanza(transportStanza)
            }
        }
    }

    private fun createTransportInfoStanza(toJid: String, sessionId: String, candidate: String, sdpMid: String, sdpMLineIndex: Int): IQ {
        val iq = object : IQ("jingle", JINGLE_NAMESPACE) {
            override fun getIQChildElementBuilder(xml: IQChildElementXmlStringBuilder): IQChildElementXmlStringBuilder {
                xml.attribute("action", "transport-info")
                xml.attribute("sid", sessionId)
                xml.rightAngleBracket()

                xml.openElement("candidate")
                xml.attribute("candidate", candidate)
                xml.attribute("sdpMid", sdpMid)
                xml.attribute("sdpMLineIndex", sdpMLineIndex.toString())
                xml.closeEmptyElement()

                return xml
            }
        }

        iq.to = JidCreate.from(toJid)
        iq.type = IQ.Type.set
        iq.stanzaId = UUID.randomUUID().toString()

        return iq
    }

    fun endCall(sessionId: String) {
        currentCallJid?.let { jid ->
            val terminateStanza = createJingleTerminateStanza(jid, sessionId)
            connection?.sendStanza(terminateStanza)

            currentSessionId = null
            currentCallJid = null

            Log.d(TAG, "Call ended for session $sessionId")
        }
    }

    private fun createJingleTerminateStanza(toJid: String, sessionId: String): IQ {
        val iq = object : IQ("jingle", JINGLE_NAMESPACE) {
            override fun getIQChildElementBuilder(xml: IQChildElementXmlStringBuilder): IQChildElementXmlStringBuilder {
                xml.attribute("action", "session-terminate")
                xml.attribute("sid", sessionId)
                xml.rightAngleBracket()

                xml.openElement("reason")
                xml.rightAngleBracket()
                xml.openElement("success")
                xml.closeEmptyElement()
                xml.closeElement("reason")

                return xml
            }
        }

        iq.to = JidCreate.from(toJid)
        iq.type = IQ.Type.set
        iq.stanzaId = UUID.randomUUID().toString()

        return iq
    }

    private fun extractSdpFromStanza(stanza: IQ): String {
        // Simplified extraction - in production use proper XML parsing
        val stanzaXml = stanza.toXML().toString()
        val sdpStart = stanzaXml.indexOf("<sdp>") + 5
        val sdpEnd = stanzaXml.indexOf("</sdp>")

        return if (sdpStart > 4 && sdpEnd > sdpStart) {
            stanzaXml.substring(sdpStart, sdpEnd)
        } else {
            ""
        }
    }

    private fun extractIceCandidateFromStanza(stanza: IQ): Map<String, Any>? {
        try {
            val stanzaXml = stanza.toXML().toString()
            // Simplified extraction - in production use proper XML parsing

            val candidateStart = stanzaXml.indexOf("candidate=\"") + 11
            val candidateEnd = stanzaXml.indexOf("\"", candidateStart)
            val candidate = stanzaXml.substring(candidateStart, candidateEnd)

            val sdpMidStart = stanzaXml.indexOf("sdpMid=\"") + 8
            val sdpMidEnd = stanzaXml.indexOf("\"", sdpMidStart)
            val sdpMid = stanzaXml.substring(sdpMidStart, sdpMidEnd)

            val sdpMLineIndexStart = stanzaXml.indexOf("sdpMLineIndex=\"") + 15
            val sdpMLineIndexEnd = stanzaXml.indexOf("\"", sdpMLineIndexStart)
            val sdpMLineIndex = stanzaXml.substring(sdpMLineIndexStart, sdpMLineIndexEnd).toInt()

            return mapOf(
                "candidate" to candidate,
                "sdpMid" to sdpMid,
                "sdpMLineIndex" to sdpMLineIndex
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting ICE candidate", e)
            return null
        }
    }

    private fun generateSessionId(): String {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16)
    }

    fun setCallListener(listener: CallListener) {
        this.callListener = listener
    }

    fun getCurrentSessionId(): String? = currentSessionId

    fun isConnected(): Boolean = connection?.isConnected == true

    fun disconnect() {
        try {
            connection?.disconnect()
            currentSessionId = null
            currentCallJid = null
            callListener?.onConnectionStateChanged(ConnectionState.DISCONNECTED)
            Log.d(TAG, "Disconnected from XMPP server")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }
    }
}