package com.chessassist

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.chessassist.engine.EngineLoop
import org.json.JSONObject

/**
 * Cross-process bridge between the LSPosed hook (running inside com.duolingo) and the
 * companion app (running Stockfish). ContentProvider `call()` transport:
 *
 *   hook → "position"  : publish new position (fen, userColor, turnColor, move history)
 *   hook → "ack"       : hook confirms the arrow was drawn
 *   app  ← "fen"       : hook asks for the current FEN (used after process restart)
 *
 * NOTE: live traffic goes over loopback TCP (BridgeServer), NOT here. Duolingo cannot
 * resolve this authority on Android 11+ (package visibility), so this provider is
 * diagnostics-only (adb shell) since the v2 bridge.
 *
 * Security note: exported because the hook runs inside com.duolingo's uid. The payload
 * is non-sensitive (FEN + moves). A signature-level permission would block the hook
 * (different signer), so instead we allowlist caller packages at runtime: only
 * com.duolingo (hook side) and our own package may use the bridge.
 */
class BridgeProvider : ContentProvider() {

    companion object {
        // com.android.shell (adb) is intentionally allowed: shell already has full
        // device control, and this keeps field diagnostics (`content call`) working.
        private val ALLOWED_CALLERS = setOf("com.duolingo", "com.chessassist", "com.android.shell")
    }

    override fun onCreate(): Boolean = true

    private fun isCallerAllowed(): Boolean {
        val caller = try {
            callingPackage
        } catch (_: Throwable) {
            null
        }
        // callingPackage is null for our own in-process calls (activity/service):
        // always allow those; otherwise enforce the allowlist so random apps on
        // the device can't spam positions (battery DoS) or steal bestmoves.
        if (caller == null) return true
        return caller in ALLOWED_CALLERS
    }

    override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? {
        if (!isCallerAllowed()) return null
        val out = android.os.Bundle()
        when (method) {
            "position" -> {
                val fen = extras?.getString("fen") ?: return null
                EngineLoop.onPosition(
                    fen = fen,
                    userColor = extras.getString("userColor"),
                    turnColor = extras.getString("turnColor"),
                    moves = extras.getString("moves").orEmpty().split(' ').filter { it.length >= 4 }
                )
            }
            "ack" -> {
                EngineLoop.onArrowAck(extras?.getString("drawn").orEmpty())
            }
            "fen" -> {
                out.putString("fen", EngineLoop.currentFen)
            }
            // Hidden probe (adb shell only): ask the hook to dump the current
            // screen's view + Compose-semantics structure to the LSPosed log
            // without tapping anything. Learns the promotion picker's structure
            // on a live game screen — no promotion needed.
            "promoscan" -> {
                BridgeServer.push(JSONObject().apply { put("t", "promoscan") })
                out.putString("sent", "1")
            }
        }
        return out
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        // Same allowlist as call(): query() used to skip it entirely, leaking the
        // live FEN (and with it "this user runs the assistant") to any app.
        // Empty cursor instead of an exception — same contract as call() returning
        // null for denied callers.
        if (!isCallerAllowed()) return MatrixCursor(arrayOf("fen"))
        // Diagnostics for stuck games (adb shell): last processed FEN + worker
        // heartbeat age + engine state — no UI needed to tell "positions stopped"
        // apart from "worker dead".
        val now = System.currentTimeMillis()
        val loopAgo = EngineLoop.lastLoopAt.let { if (it <= 0) -1 else (now - it) / 1000 }
        val posAgo = EngineLoop.lastPositionAt.let { if (it <= 0) -1 else (now - it) / 1000 }
        val cursor = MatrixCursor(arrayOf("fen", "loopAgoSec", "posAgoSec", "engine", "spin"))
        cursor.addRow(
            arrayOf(
                EngineLoop.currentFen, loopAgo, posAgo, EngineLoop.engineState,
                if (OverlayManager.isSpinning()) 1 else 0
            )
        )
        return cursor
    }

    override fun getType(uri: Uri): String? = "vnd.android.cursor.item/fen"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
