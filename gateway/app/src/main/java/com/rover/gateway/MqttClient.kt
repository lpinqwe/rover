package com.rover.gateway

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import javax.net.ssl.SSLContext

/**
 * Тонкая обёртка над Paho MQTT v3 (чистый Java → TCP, памяти хватает).
 * Подписки: наш входящий JSON приходит в [onMessage].
 */
class MqttClient(
    private val brokerUri: String,
    private val clientId: String,
    private val status: (String) -> Unit,
) {
    private var client: MqttClient? = null
    private var user = ""
    private var pass = ""

    var onMessage: ((topic: String, payload: String) -> Unit)? = null
    var onConnectedChange: ((Boolean) -> Unit)? = null

    @Volatile var connected = false
        private set

    fun configure(user: String, pass: String) {
        this.user = user
        this.pass = pass
    }

    fun connect(topics: List<String>) {
        status("MQTT: подключаюсь к $brokerUri...")
        runCatching {
            val opts = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = false
                connectionTimeout = 15
                keepAliveInterval = 20
                // tls://host:8883 (HiveMQ Cloud) — системный trust store
                if (brokerUri.startsWith("tls://")) {
                    socketFactory = SSLContext.getDefault().socketFactory
                }
                if (user.isNotBlank()) {
                    userName = user
                    this.password = pass.toCharArray()
                }
            }
            val c = MqttClient(brokerUri, clientId, MemoryPersistence())
            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String) {
                    connected = true
                    onConnectedChange?.invoke(true)
                    status("MQTT: подключён${if (reconnect) " (reconnect)" else ""}")
                    topics.forEach { t ->
                        runCatching { c.subscribe(t, 0) }
                    }
                }

                override fun connectionLost(cause: Throwable?) {
                    connected = false
                    onConnectedChange?.invoke(false)
                    status("MQTT: потеряна связь")
                }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    onMessage?.invoke(topic, String(message.payload))
                }

                override fun deliveryComplete(token: IMqttDeliveryToken) {}
            })
            c.connect(opts)
            client = c
        }.onFailure {
            status("MQTT: ошибка подключения: ${it.message}")
        }
    }

    fun publish(topic: String, payload: String, retain: Boolean = false) {
        val c = client ?: return
        if (!c.isConnected) return
        runCatching {
            val m = MqttMessage(payload.toByteArray(Charsets.UTF_8))
            m.qos = 0
            m.isRetained = retain
            c.publish(topic, m)
        }
    }

    fun disconnect() {
        runCatching { client?.disconnect() }
        client = null
        connected = false
    }
}