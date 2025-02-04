package com.gaurav.avnc.vnc

import androidx.annotation.Keep
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * This is a thin wrapper around native client.
 *
 *
 * -       +------------+                                    +----------+
 * -       | Public API |                                    | Observer |
 * -       +------------+                                    +-----A----+
 * -              |                                                |
 * -              |                                                |
 * -   JNI -------|------------------------------------------------|-----------
 * -              |                                                |
 * -              |                                                |
 * -      +-------v--------+       +--------------+       +--------v---------+
 * -      | Native Methods |------>| LibVNCClient |<----->| Native Callbacks |
 * -      +----------------+       +--------------+       +------------------+
 *
 *
 * For every new instance of [VncClient], we create a native 'rfbClient' and
 * store its pointer in [nativePtr]. Parameters for connection can be setup using
 * [configure]. Connection is then started using [connect]. Then incoming
 * messages are handled by [processServerMessage].
 *
 * To release the resources you must call [cleanup] after you are done with
 * this instance.
 */
class VncClient(private val observer: Observer) {

    /**
     * Interface for event observer.
     * DO NOT throw exceptions from these methods.
     * There is NO guarantee about which thread will invoke [Observer] methods.
     */
    interface Observer {
        fun onPasswordRequired(): String
        fun onCredentialRequired(): UserCredential
        fun onVerifyCertificate(certificate: X509Certificate): Boolean
        fun onGotXCutText(text: String)
        fun onFramebufferUpdated()
        fun onFramebufferSizeChanged(width: Int, height: Int)
        fun onPointerMoved(x: Int, y: Int)

        //fun onBell()
    }

    /**
     * Value of the pointer to native 'rfbClient'. This is passed to all native methods.
     */
    private val nativePtr: Long

    init {
        nativePtr = nativeClientCreate()
        if (nativePtr == 0L)
            throw RuntimeException("Could not create native rfbClient!")
    }

    @Volatile
    var connected = false
        private set

    private var destroyed = false

    /**
     * Lock protecting access to [connected] & [destroyed] state.
     */
    private val stateLock = ReentrantReadWriteLock()

    /**
     * In 'View-only' mode input to remote server is disabled
     */
    private var viewOnlyMode = false

    /**
     * Latest pointer position. See [moveClientPointer].
     */
    var pointerX = 0; private set
    var pointerY = 0; private set

    /**
     * Client-side cursor rendering creates a synchronization issue.
     * Suppose if pointer is moved to (50,10) by client. A PointerEvent is sent
     * to the server and cursor is immediately rendered on (50,10).
     * Some servers (e.g. Vino) will send back a PointerPosition event for (50, 10).
     * But, by the time that event is received from server, pointer on client
     * might have already moved to (60,20) (this is almost guaranteed to happen
     * with touchpad/relative action mode). So the cursor will probably 'jump back'
     * depending on the order of these events.
     *
     * This flags works around the issue by temporarily ignoring serer-side updates.
     */
    @Volatile
    var ignorePointerMovesByServer = false


    /**
     * Value of the most recent cut text sent/received from server
     */
    @Volatile
    private var lastCutText: String? = null

    /**
     * Setup different properties for this client.
     *
     * @param securityType RFB security type to use.
     */
    fun configure(viewOnly: Boolean, securityType: Int, useLocalCursor: Boolean, imageQuality: Int, useRawEncoding: Boolean) {
        stateLock.read {
            if (!connected && !destroyed) {
                viewOnlyMode = viewOnly
                nativeConfigure(nativePtr, securityType, useLocalCursor, imageQuality, useRawEncoding)
            }
        }
    }

    fun setupRepeater(serverId: Int) {
        stateLock.read {
            if (!connected && !destroyed)
                nativeSetDest(nativePtr, "ID", serverId)
        }
    }

    /**
     * Initializes VNC connection.
     */
    fun connect(host: String, port: Int) {
        stateLock.read {
            if (connected || destroyed)
                return
            if (!nativeInit(nativePtr, host, port))
                throw IOException(nativeGetLastErrorStr())
        }
        stateLock.write {
            if (!destroyed)
                connected = true
        }
    }

    /**
     * Waits for incoming server message, parses it and then invokes appropriate callbacks.
     *
     * @param uSecTimeout Timeout in microseconds.
     */
    fun processServerMessage(uSecTimeout: Int = 1000000) {
        stateLock.read {
            if (connected && nativeProcessServerMessage(nativePtr, uSecTimeout))
                return
        }

        // Either not connected or an error occurred when processing message
        stateLock.write {
            connected = false
            throw IOException(nativeGetLastErrorStr())
        }
    }

    /**
     * Name of remote desktop
     */
    fun getDesktopName(): String {
        ifConnected {
            return nativeGetDesktopName(nativePtr)
        }
        return ""
    }

    /**
     * Whether connected to a MacOS server
     */
    fun isConnectedToMacOS(): Boolean {
        ifConnected {
            return nativeIsServerMacOS(nativePtr)
        }
        return false
    }

    /**
     * Sends Key event to remote server.
     *
     * @param keySym    Key symbol
     * @param xtCode    Key code from [XTKeyCode]
     * @param isDown    true for key down, false for key up
     */
    fun sendKeyEvent(keySym: Int, xtCode: Int, isDown: Boolean) = ifConnectedAndInteractive {
        nativeSendKeyEvent(nativePtr, keySym, xtCode, isDown)
    }

    /**
     * Sends pointer event to remote server.
     *
     * @param x    Horizontal pointer coordinate
     * @param y    Vertical pointer coordinate
     * @param mask Button mask to identify which button was pressed.
     */
    fun sendPointerEvent(x: Int, y: Int, mask: Int) = ifConnectedAndInteractive {
        nativeSendPointerEvent(nativePtr, x, y, mask)
    }

    /**
     * Updates client-side pointer position.
     * No event is sent to server.
     *
     * Primary use-case is to update pointer position during gestures.
     * This way we can immediately render the cursor on new position without
     * waiting for Network IO.
     *
     * It also helps with servers which don't send pointer-position updates
     * if pointer was moved by the client.
     *
     * @param x    Horizontal pointer coordinate
     * @param y    Vertical pointer coordinate
     */
    fun moveClientPointer(x: Int, y: Int) {
        pointerX = x
        pointerY = y
        observer.onPointerMoved(x, y)
    }


    /**
     * Sends text to remote desktop's clipboard.
     */
    fun sendCutText(text: String) = ifConnectedAndInteractive {
        if (text != lastCutText) {
            val sent = if (nativeIsUTF8CutTextSupported(nativePtr))
                nativeSendCutText(nativePtr, text.toByteArray(StandardCharsets.UTF_8), true)
            else
                nativeSendCutText(nativePtr, text.toByteArray(StandardCharsets.ISO_8859_1), false)
            if (sent)
                lastCutText = text
        }
    }

    /**
     * Set remote desktop size to given dimensions.
     * This needs server support to actually work.
     * Non-positive [width] & [height] are ignored.
     */
    fun setDesktopSize(width: Int, height: Int) = ifConnected {
        if (width > 0 && height > 0)
            nativeSetDesktopSize(nativePtr, width, height)
    }

    /**
     * Sends frame buffer update request to remote server.
     */
    fun refreshFrameBuffer() = ifConnected {
        nativeRefreshFrameBuffer(nativePtr)
    }

    /**
     * Change framebuffer update status.
     * If paused, client will effectively stop asking for framebuffer updates from server.
     */
    fun pauseFramebufferUpdates(pause: Boolean) = ifConnected {
        nativePauseFramebufferUpdates(nativePtr, pause)
    }

    /**
     * Puts framebuffer contents in currently active OpenGL texture.
     * Must be called from an OpenGL ES context (i.e. from renderer thread).
     */
    fun uploadFrameTexture() = ifConnected {
        nativeUploadFrameTexture(nativePtr)
    }

    /**
     * Upload cursor shape into framebuffer texture.
     */
    fun uploadCursor() = ifConnected {
        nativeUploadCursor(nativePtr, pointerX, pointerY)
    }

    /**
     * Set the interrupt flag.
     * If [connect] is executing in another thread (and not yet successful),
     * it will abandon the attempt and throw an error.
     */
    fun interrupt() {
        stateLock.read {
            if (!destroyed)
                nativeInterrupt(nativePtr)
        }
    }

    /**
     * Release all resources allocated by the client.
     * DO NOT use this client after [cleanup].
     */
    fun cleanup() {
        stateLock.write {
            if (!destroyed) {
                nativeCleanup(nativePtr)
                connected = false
                destroyed = true
            }
        }
    }

    private inline fun ifConnected(block: () -> Unit) = stateLock.read {
        if (connected && !destroyed)
            block()
    }

    private inline fun ifConnectedAndInteractive(block: () -> Unit) = ifConnected {
        if (!viewOnlyMode)
            block()
    }

    private external fun nativeClientCreate(): Long
    private external fun nativeConfigure(clientPtr: Long, securityType: Int, useLocalCursor: Boolean, imageQuality: Int, useRawEncoding: Boolean)
    private external fun nativeInit(clientPtr: Long, host: String, port: Int): Boolean
    private external fun nativeSetDest(clientPtr: Long, host: String, port: Int)
    private external fun nativeProcessServerMessage(clientPtr: Long, uSecTimeout: Int): Boolean
    private external fun nativeSendKeyEvent(clientPtr: Long, keySym: Int, xtCode: Int, isDown: Boolean): Boolean
    private external fun nativeSendPointerEvent(clientPtr: Long, x: Int, y: Int, mask: Int): Boolean
    private external fun nativeSendCutText(clientPtr: Long, bytes: ByteArray, isUTF8: Boolean): Boolean
    private external fun nativeIsUTF8CutTextSupported(clientPtr: Long): Boolean
    private external fun nativeSetDesktopSize(clientPtr: Long, width: Int, height: Int): Boolean
    private external fun nativeRefreshFrameBuffer(clientPtr: Long): Boolean
    private external fun nativePauseFramebufferUpdates(clientPtr: Long, pause: Boolean)
    private external fun nativeGetDesktopName(clientPtr: Long): String
    private external fun nativeGetWidth(clientPtr: Long): Int
    private external fun nativeGetHeight(clientPtr: Long): Int
    private external fun nativeIsEncrypted(clientPtr: Long): Boolean
    private external fun nativeUploadFrameTexture(clientPtr: Long)
    private external fun nativeUploadCursor(clientPtr: Long, px: Int, py: Int)
    private external fun nativeGetLastErrorStr(): String
    private external fun nativeIsServerMacOS(clientPtr: Long): Boolean
    private external fun nativeInterrupt(clientPtr: Long)
    private external fun nativeCleanup(clientPtr: Long)

    @Keep
    private fun cbGetPassword() = observer.onPasswordRequired()

    @Keep
    private fun cbGetCredential() = observer.onCredentialRequired()

    @Keep
    private fun cbVerifyServerCertificate(der: ByteArray): Boolean {
        val cert = ByteArrayInputStream(der).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        return observer.onVerifyCertificate(cert as X509Certificate)
    }

    @Keep
    private fun cbGotXCutText(bytes: ByteArray, isUTF8: Boolean) {
        (if (isUTF8) StandardCharsets.UTF_8 else StandardCharsets.ISO_8859_1).let {
            val cutText = it.decode(ByteBuffer.wrap(bytes)).toString()
            if (cutText != lastCutText) {
                lastCutText = cutText
                observer.onGotXCutText(cutText)
            }
        }
    }

    @Keep
    private fun cbFinishedFrameBufferUpdate() = observer.onFramebufferUpdated()

    @Keep
    private fun cbFramebufferSizeChanged(w: Int, h: Int) = observer.onFramebufferSizeChanged(w, h)


    @Keep
    private fun cbBell() = Unit // observer.onBell()

    @Keep
    private fun cbHandleCursorPos(x: Int, y: Int) {
        if (!ignorePointerMovesByServer)
            moveClientPointer(x, y)
    }


    /**
     * Native library initialization
     */
    companion object {
        fun loadLibrary() {
            System.loadLibrary("native-vnc")
        }

        @JvmStatic
        private external fun initLibrary()

        init {
            loadLibrary()
            initLibrary()
        }
    }
}