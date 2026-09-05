package com.chessassist.engine

/**
 * EXPERIMENTAL in-process JNI engine stub.
 *
 * The default (and recommended) path is the subprocess engine ([StockfishClient]):
 * crash-isolated, plain UCI over pipes. This JNI variant only exists for the
 * `-DENABLE_JNI_ENGINE=ON` CMake option; its native `send()` is currently a
 * logging stub (see jni/StockfishJni.cpp) and is NOT wired to UCI input.
 * Do not use for real analysis until stdin piping is implemented.
 */
object StockfishJni {
    interface UciCallback {
        fun onUciLine(line: String)
    }

    external fun start(callback: UciCallback)
    external fun send(cmd: String)

    fun isAvailable(): Boolean = try {
        System.loadLibrary("stockfish_jni")
        true
    } catch (_: Throwable) {
        false
    }
}
