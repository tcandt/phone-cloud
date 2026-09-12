package com.cloudphone.app.webrtc

enum class WebRTCConnectionState {
    DISCONNECTED,
    SIGNALING,
    NEGOTIATING,
    ICE_CONNECTING,
    CONNECTED,
    FALLBACK_WS,
    RECONNECTING
}
