package com.example.xmppaudiocall

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jivesoftware.smack.AbstractXMPPConnection
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.chat2.Chat
import org.jivesoftware.smack.chat2.ChatManager
import org.jivesoftware.smack.chat2.IncomingChatMessageListener
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.impl.JidCreate

class XMPPClient(
    private val listener: XMPPListener
) {

    interface XMPPListener {
        fun onConnected(jid: String)
        fun onConnectionFailed(error: String)
        fun onMessageReceived(from: String, message: String)
        fun onDisconnected()
    }

    private var connection: AbstractXMPPConnection? = null
    private var chatManager: ChatManager? = null

    fun connect(server: String, username: String, password: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val config = XMPPTCPConnectionConfiguration.builder()
                    .setXmppDomain(server)
                    .setHost(server)
                    .setPort(5222)
                    .setSecurityMode(ConnectionConfiguration.SecurityMode.ifpossible)
                    .setCompressionEnabled(false)
                    .setSendPresence(true)
                    .build()

                connection = XMPPTCPConnection(config).apply {
                    connect()
                    login(username, password)
                }

                setupChatManager()

                val myJid = connection?.user?.toString() ?: ""
                listener.onConnected(myJid)

            } catch (e: Exception) {
                e.printStackTrace()
                listener.onConnectionFailed(e.message ?: "Connection failed")
            }
        }
    }

    private fun setupChatManager() {
        connection?.let { conn ->
            chatManager = ChatManager.getInstanceFor(conn)
            chatManager?.addIncomingListener(object : IncomingChatMessageListener {
                override fun newIncomingMessage(from: EntityBareJid, message: Message, chat: Chat) {
                    val body = message.body ?: return
                    listener.onMessageReceived(from.toString(), body)
                }
            })
        }
    }

    fun sendMessage(toJid: String, message: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val entityJid = JidCreate.entityBareFrom(toJid)
                val chat = chatManager?.chatWith(entityJid)

                val msg = Message().apply {
                    body = message
                    type = Message.Type.chat
                }

                chat?.send(msg)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun disconnect() {
        try {
            connection?.disconnect()
            listener.onDisconnected()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isConnected(): Boolean {
        return connection?.isConnected == true
    }
}