package dev.joycon2.bridge.transport;

public enum ConnectionStage {
    CONNECTING,
    NEGOTIATING,
    DISCOVERING,
    SUBSCRIBING,
    READY,
    DISCONNECTED,
    ERROR
}
