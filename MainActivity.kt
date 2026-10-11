package app.mochianimes.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

private const val SITE = "https://mochianimes.netlify.app/?tv=1"

/** Seta do mouse (desenhada à mão, sem imagem). */
class CursorView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path().apply {
        val s = 1.7f * d
        moveTo(3 * s, 2 * s); lineTo(10 * s, 21 * s); lineTo(13 * s, 13 * s); lineTo(21 * s, 10 * s); close()
    }
    init { setLayerType(LAYER_TYPE_HARDWARE, null) }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((40 * d).toInt(), (40 * d).toInt())
    override fun onDraw(c: Canvas) { c.drawPath(path, fill); c.drawPath(path, line) }
}

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var cursor: CursorView
    private val ui = Handler(Looper.getMainLooper())

    private var siteWantsCursor = false      // o site avisa: "tela de info aberta"
    private var customView: View? = null      // player em tela cheia
    private var customCb: WebChromeClient.CustomViewCallback? = null

    private var cx = 0f
    private var cy = 0f
    private val held = HashSet<Int>()
    private var holdStart = 0L
    private var lastTick = 0L
    private var lastKey = 0L
    private var lastHover = 0L
    private var lastScroll = 0L
    private var pressing = false
    private var downTime = 0L

    private var popup: WebView? = null          // janela do login Google
    private val cursorOn get() = siteWantsCursor || customView != null || popup != null
    private val target: View get() = popup ?: customView ?: web

    /** O site chama MochiTV.cursor(true/false) quando abre/fecha a tela de info. */
    inner class Bridge {
        @JavascriptInterface fun cursor(on: Boolean) = runOnUiThread { siteWantsCursor = on; applyCursor() }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0e0d16")) }
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        cursor = CursorView(this).apply { visibility = View.GONE; elevation = 100f }
        root.addView(cursor, FrameLayout.LayoutParams(-2, -2))
        setContentView(root)

        configure(web)
        web.addJavascriptInterface(Bridge(), "MochiTV")
        web.webViewClient = object : WebViewClient() {
            // só o site e o login Google abrem na tela principal; link de anúncio não tira você do app
            override fun shouldOverrideUrlLoading(v: WebView, r: android.webkit.WebResourceRequest): Boolean {
                if (!r.isForMainFrame) return false
                return !hostOk(r.url.host)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            // janela do login Google (Firebase abre um popup). Qualquer outro popup é fechado.
            override fun onCreateWindow(v: WebView, isDialog: Boolean, isUserGesture: Boolean, msg: android.os.Message): Boolean {
                if (popup != null || !isUserGesture) return false
                val child = WebView(this@MainActivity)
                configure(child)
                child.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(w: WebView, url: String?, f: Bitmap?) {
                        val u = url ?: return
                        if (u.startsWith("about:")) return
                        if (!hostOk(Uri.parse(u).host)) ui.post { closePopup() }
                    }
                    override fun shouldOverrideUrlLoading(w: WebView, r: android.webkit.WebResourceRequest): Boolean {
                        if (r.url.scheme == "about" || hostOk(r.url.host)) return false
                        ui.post { closePopup() }; return true
                    }
                }
                child.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(w: WebView?) { ui.post { closePopup() } }
                }
                popup = child
                root.addView(child, root.indexOfChild(cursor), FrameLayout.LayoutParams(-1, -1))
                (msg.obj as WebView.WebViewTransport).webView = child
                msg.sendToTarget()
                applyCursor()
                return true
            }
            override fun onCloseWindow(w: WebView?) { if (w === popup) ui.post { closePopup() } }
            override fun onShowCustomView(v: View, cb: CustomViewCallback) {
                customView = v; customCb = cb
                root.addView(v, root.indexOfChild(cursor), FrameLayout.LayoutParams(-1, -1))
                web.visibility = View.INVISIBLE
                applyCursor()
            }
            override fun onHideCustomView() {
                customView?.let { root.removeView(it) }
                customView = null
                customCb?.onCustomViewHidden(); customCb = null
                web.visibility = View.VISIBLE
                web.requestFocus()
                applyCursor()
            }
        }
        web.requestFocus()
        web.loadUrl(SITE)
        root.post { cx = root.width / 2f; cy = root.height / 2f; place() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(w: WebView) {
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = userAgentString.replace("; wv", "")
                .replace(Regex("Version/\\d+\\.\\d+ "), "") + " AndroidTV MochiTVApp"
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(w, true)
    }

    /** Só estes sites podem abrir (site + login Google). Todo o resto (anúncios de embed) é bloqueado. */
    private fun hostOk(h: String?): Boolean {
        if (h == null) return false
        val ok = listOf("netlify.app", "google.com", "firebaseapp.com", "googleapis.com",
            "gstatic.com", "googleusercontent.com", "accounts.youtube.com")
        return ok.any { h == it || h.endsWith(".$it") }
    }

    private fun closePopup() {
        popup?.let { root.removeView(it); it.destroy() }
        popup = null
        web.requestFocus()
        applyCursor()
    }

    private fun applyCursor() {
        cursor.visibility = if (cursorOn) View.VISIBLE else View.GONE
        cursor.bringToFront()
        if (!cursorOn) { held.clear(); if (pressing) touch(MotionEvent.ACTION_UP) }
        else if (cx == 0f && cy == 0f) { cx = root.width / 2f; cy = root.height / 2f }
        place()
    }

    private fun place() { cursor.translationX = cx; cursor.translationY = cy }

    // ---------- movimento suave: roda a cada frame enquanto a seta estiver apertada ----------
    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            // se o botão foi solto e o "keyup" se perdeu, para sozinho
            if (held.isNotEmpty() && now - lastKey > 700) held.clear()
            if (held.isEmpty()) { lastTick = 0; return }
            val dt = if (lastTick == 0L) 16L else min(now - lastTick, 50L)
            lastTick = now
            val heldMs = now - holdStart
            val speed = min(1800f, 520f + heldMs * 2.2f)       // px por segundo (acelera)
            val step = speed * dt / 1000f
            var dx = 0f; var dy = 0f
            if (KeyEvent.KEYCODE_DPAD_LEFT in held) dx -= step
            if (KeyEvent.KEYCODE_DPAD_RIGHT in held) dx += step
            if (KeyEvent.KEYCODE_DPAD_UP in held) dy -= step
            if (KeyEvent.KEYCODE_DPAD_DOWN in held) dy += step
            move(dx, dy)
            ui.postDelayed(this, 8)
        }
    }

    private fun move(dx: Float, dy: Float) {
        cx = max(0f, min(root.width - 2f, cx + dx))
        cy = max(0f, min(root.height - 2f, cy + dy))
        place()
        val now = SystemClock.uptimeMillis()
        if (pressing) { sendTouch(MotionEvent.ACTION_MOVE); return }       // arrastar (barra de tempo)
        if (now - lastHover > 90) { lastHover = now; hover() }            // faz o player mostrar os controles
        if (customView == null && now - lastScroll > 40 && now - holdStart > 250) {   // rolagem na borda
            val sc = if (KeyEvent.KEYCODE_DPAD_DOWN in held && cy > root.height - 70) 38
                     else if (KeyEvent.KEYCODE_DPAD_UP in held && cy < 70) -38 else 0
            if (sc != 0) {
                lastScroll = now
                val js = if (popup != null) "window.scrollBy(0,$sc)"
                         else "(function(){var m=document.querySelector('.modal.open');if(m)m.scrollBy(0,$sc)})()"
                (popup ?: web).evaluateJavascript(js, null)
            }
        }
    }

    private fun hover() {
        val t = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, cx, cy, 0)
        e.source = InputDevice.SOURCE_MOUSE
        target.dispatchGenericMotionEvent(e); e.recycle()
    }

    // ---------- toque de verdade, que atravessa o iframe do player ----------
    private fun sendTouch(action: Int) {
        val t = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = t
        val e = MotionEvent.obtain(downTime, t, action, cx, cy, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        target.dispatchTouchEvent(e); e.recycle()
    }

    private fun touch(action: Int) {
        if (action == MotionEvent.ACTION_DOWN) { if (pressing) return; pressing = true; sendTouch(action) }
        else if (pressing) { sendTouch(MotionEvent.ACTION_UP); pressing = false }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (cursorOn) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (e.action == KeyEvent.ACTION_DOWN) {
                        lastKey = SystemClock.uptimeMillis()
                        if (held.add(e.keyCode)) {
                            if (held.size == 1) holdStart = lastKey
                            // pulo imediato no primeiro toque, pra não parecer travado
                            val j = 20f
                            move(when (e.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -j; KeyEvent.KEYCODE_DPAD_RIGHT -> j; else -> 0f },
                                 when (e.keyCode) { KeyEvent.KEYCODE_DPAD_UP -> -j; KeyEvent.KEYCODE_DPAD_DOWN -> j; else -> 0f })
                            ui.removeCallbacks(tick); ui.post(tick)
                        }
                    } else if (e.action == KeyEvent.ACTION_UP) {
                        held.remove(e.keyCode)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) touch(MotionEvent.ACTION_DOWN)
                    else if (e.action == KeyEvent.ACTION_UP) touch(MotionEvent.ACTION_UP)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (popup != null) { closePopup(); return }
        if (customView != null) { web.webChromeClient?.onHideCustomView(); return }
        // pergunta ao site se tem algo pra fechar (tela de info, busca, aba); se não, sai do app
        web.evaluateJavascript(
            "(function(){var b=document.body;return !!(document.querySelector('.modal.open')||document.getElementById('tour')||(b.dataset.tab&&b.dataset.tab!=='home'))})()"
        ) { r ->
            if (r == "true") {
                val t = SystemClock.uptimeMillis()
                web.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE, 0))
                web.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE, 0))
            } else finish()
        }
    }

    override fun onPause() { super.onPause(); web.onPause() }
    override fun onResume() { super.onResume(); web.onResume() }
    override fun onDestroy() { ui.removeCallbacksAndMessages(null); web.destroy(); super.onDestroy() }
}
