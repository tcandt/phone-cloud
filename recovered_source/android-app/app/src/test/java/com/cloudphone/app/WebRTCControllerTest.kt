package com.cloudphone.app

import com.cloudphone.app.webrtc.WebRTCConnectionState
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class WebRTCControllerTest {

    @Test
    fun testWebRTCConnectionStatesEnum() {
        val states = WebRTCConnectionState.values().map { it.name }
        assertTrue(states.contains("DISCONNECTED"))
        assertTrue(states.contains("SIGNALING"))
        assertTrue(states.contains("NEGOTIATING"))
        assertTrue(states.contains("ICE_CONNECTING"))
        assertTrue(states.contains("CONNECTED"))
        assertTrue(states.contains("FALLBACK_WS"))
        assertTrue(states.contains("RECONNECTING"))
        assertEquals(7, states.size)
    }

    @Test
    fun testRequestOfferEnvelopeParity() {
        val deviceId = "device_test_123"
        val payload = JsonObject().apply {
            addProperty("type", "request-offer")
            addProperty("request", "request-offer")
        }
        val envelope = JsonObject().apply {
            addProperty("message_type", "forward")
            addProperty("device_id", deviceId)
            add("payload", payload)
        }

        assertEquals("forward", envelope.get("message_type").asString)
        assertEquals(deviceId, envelope.get("device_id").asString)
        assertEquals("request-offer", envelope.getAsJsonObject("payload").get("type").asString)
    }

    @Test
    fun testAnswerEnvelopeParity() {
        val sdp = "v=0\r\no=- 123 456 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"
        val answerPayload = JsonObject().apply {
            addProperty("type", "answer")
            addProperty("sdp", sdp)
        }
        val envelope = JsonObject().apply {
            addProperty("message_type", "forward")
            addProperty("device_id", "device_target")
            add("payload", answerPayload)
        }

        val jsonStr = envelope.toString()
        val parsed = Gson().fromJson(jsonStr, JsonObject::class.java)
        assertEquals("forward", parsed.get("message_type").asString)
        assertEquals("answer", parsed.getAsJsonObject("payload").get("type").asString)
        assertEquals(sdp, parsed.getAsJsonObject("payload").get("sdp").asString)
    }

    @Test
    fun testIceCandidateEnvelopeParity() {
        val candObj = JsonObject().apply {
            addProperty("candidate", "candidate:1 1 UDP 2122260223 192.168.1.50 50000 typ host")
            addProperty("sdpMid", "0")
            addProperty("sdpMLineIndex", 0)
        }
        val payload = JsonObject().apply {
            addProperty("type", "ice-candidate")
            add("candidate", candObj)
        }
        val envelope = JsonObject().apply {
            addProperty("message_type", "forward")
            addProperty("device_id", "phone_01")
            add("payload", payload)
        }

        val parsed = Gson().fromJson(envelope.toString(), JsonObject::class.java)
        assertEquals("forward", parsed.get("message_type").asString)
        assertEquals("ice-candidate", parsed.getAsJsonObject("payload").get("type").asString)
        val innerCand = parsed.getAsJsonObject("payload").getAsJsonObject("candidate")
        assertEquals("0", innerCand.get("sdpMid").asString)
        assertEquals(0, innerCand.get("sdpMLineIndex").asInt)
        assertTrue(innerCand.get("candidate").asString.startsWith("candidate:1 1 UDP"))
    }

    @Test
    fun testDataChannelInputPayload() {
        val touchMsg = JsonObject().apply {
            addProperty("type", "touch")
            addProperty("action", 0) // Down
            addProperty("x", 540)
            addProperty("y", 960)
            addProperty("w", 1080)
            addProperty("h", 1920)
            addProperty("id", 1L)
        }

        assertEquals("touch", touchMsg.get("type").asString)
        assertEquals(0, touchMsg.get("action").asInt)
        assertEquals(540, touchMsg.get("x").asInt)
        assertEquals(960, touchMsg.get("y").asInt)
    }

    @Test
    fun testCameraChannelCommands() {
        val cameraActions = listOf("camera_start", "camera_stop", "camera_switch", "camera_snapshot", "camera_status", "camera_zoom", "camera_rotate")
        for (action in cameraActions) {
            val cmd = JsonObject().apply {
                addProperty("action", action)
                addProperty("request_id", "cam_req_101")
            }
            assertEquals(action, cmd.get("action").asString)
            assertEquals("cam_req_101", cmd.get("request_id").asString)
        }
    }

    @Test
    fun testCameraPTZAndRotation() {
        val zoomCmd = JsonObject().apply {
            addProperty("action", "camera_zoom")
            addProperty("request_id", "req_zoom_1")
            val params = JsonObject().apply { addProperty("zoom", 2.5f) }
            add("params", params)
        }
        assertEquals("camera_zoom", zoomCmd.get("action").asString)
        assertEquals(2.5f, zoomCmd.getAsJsonObject("params").get("zoom").asFloat, 0.001f)

        val rotCmd = JsonObject().apply {
            addProperty("action", "camera_rotate")
            addProperty("request_id", "req_rot_1")
            val params = JsonObject().apply { addProperty("degrees", 180) }
            add("params", params)
        }
        assertEquals("camera_rotate", rotCmd.get("action").asString)
        assertEquals(180, rotCmd.getAsJsonObject("params").get("degrees").asInt)
    }
}
