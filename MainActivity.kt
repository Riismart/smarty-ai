package com.smarty.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var orb: View
    private lateinit var status: TextView
    private lateinit var logBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var micBtn: Button
    private lateinit var autoBtn: Button
    private lateinit var langBtn: Button

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var busy = false
    private var auto = false
    private var awakeUntil = 0L
    private var li = 0
    private var key = ""
    private var notes = ""
    private var gen = 0
    private var uid = 0
    private var pending = 0
    private var finished = false
    private var spoken = 0
    private var turns = 0
    private val hist = mutableListOf<Pair<String, String>>()

    private val langs = arrayOf("hi-IN", "en-IN", "gu-IN", "ur-PK")
    private val labels = arrayOf("HI", "EN", "GU", "UR")
    private val models = listOf("gemini-2.5-flash-lite", "gemini-flash-latest", "gemini-2.5-flash")
    private val cyan = Color.parseColor("#00e5ff")
    private val wake = Regex("smart\\w*|\u0938\u094d\u092e\u093e\u0930\u094d\u091f\\w*|\u0AB8\u0ACD\u0AAE\u0ABE\u0AB0\u0ACD\u0A9F\\w*", RegexOption.IGNORE_CASE)
    private val strip = Regex("\\b(hi+|hey|hello|hai)?[\\s,]*(smart\\w*|\u0938\u094d\u092e\u093e\u0930\u094d\u091f\\w*|\u0AB8\u0ACD\u0AAE\u0ABE\u0AB0\u0ACD\u0A9F\\w*)[\\s,]*", RegexOption.IGNORE_CASE)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("smarty", MODE_PRIVATE)
        key = prefs.getString("key", "") ?: ""
        notes = prefs.getString("notes", "") ?: ""
        try {
            val a = JSONArray(prefs.getString("hist", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                hist.add(Pair(o.getString("r"), o.getString("t")))
            }
        } catch (e: Exception) {
        }
        window.statusBarColor = Color.parseColor("#050a10")
        window.navigationBarColor = Color.parseColor("#050a10")
        buildUi()
        hist.forEach { addBubble(it.first == "user", it.second) }
        tts = TextToSpeech(this, this)
        if (SpeechRecognizer.isRecognitionAvailable(this)) makeRecognizer()
        else toast("Is phone mein voice recognition nahi mil raha")
        if (key.isEmpty()) keyDialog()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ---------- UI ----------
    private fun smallBtn(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 12f
        b.setTextColor(cyan)
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(dp(10), 0, dp(10), 0)
        val g = GradientDrawable()
        g.setStroke(dp(1), Color.parseColor("#0e3a47"))
        g.cornerRadius = dp(6).toFloat()
        g.setColor(Color.parseColor("#0a1c24"))
        b.background = g
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#050a10"))
        root.setPadding(dp(12), dp(8), dp(12), dp(8))

        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "SMARTY"
        title.setTextColor(cyan)
        title.textSize = 18f
        title.typeface = Typeface.MONOSPACE
        title.letterSpacing = 0.2f
        top.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        autoBtn = smallBtn("AUTO") { toggleAuto() }
        langBtn = smallBtn(labels[0]) { li = (li + 1) % langs.size; langBtn.text = labels[li] }
        val keyBtn = smallBtn("KEY") { keyDialog() }
        val bp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36))
        bp.leftMargin = dp(6)
        top.addView(autoBtn, bp)
        top.addView(langBtn, bp)
        top.addView(keyBtn, bp)
        root.addView(top)

        orb = View(this)
        val op = LinearLayout.LayoutParams(dp(110), dp(110))
        op.gravity = Gravity.CENTER_HORIZONTAL
        op.topMargin = dp(12)
        root.addView(orb, op)

        status = TextView(this)
        status.gravity = Gravity.CENTER
        status.setTextColor(Color.parseColor("#5f8794"))
        status.textSize = 12f
        status.typeface = Typeface.MONOSPACE
        status.setPadding(0, dp(8), 0, dp(8))
        root.addView(status)
        idleState()

        scroll = ScrollView(this)
        logBox = LinearLayout(this)
        logBox.orientation = LinearLayout.VERTICAL
        scroll.addView(logBox)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this)
        bar.gravity = Gravity.CENTER_VERTICAL
        input = EditText(this)
        input.hint = "Smarty se baat karo"
        input.setTextColor(Color.WHITE)
        input.setHintTextColor(Color.parseColor("#5f8794"))
        input.maxLines = 3
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.setSingleLine(true)
        input.setOnEditorActionListener { _, a, _ ->
            if (a == EditorInfo.IME_ACTION_SEND) {
                sendTyped()
                true
            } else false
        }
        bar.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        micBtn = smallBtn("MIC") {
            if (listening) {
                recognizer?.stopListening()
            } else {
                awakeUntil = System.currentTimeMillis() + 15000
                startListening()
            }
        }
        val sendBtn = smallBtn("SEND") { sendTyped() }
        val mp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44))
        mp.leftMargin = dp(6)
        bar.addView(micBtn, mp)
        bar.addView(sendBtn, mp)
        root.addView(bar)

        setContentView(root)
    }

    private fun sendTyped() {
        val t = input.text.toString().trim()
        input.setText("")
        if (t.isNotEmpty()) send(t)
    }

    private fun setOrb(color: Int, label: String) {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        g.setStroke(dp(3), Color.WHITE and 0x55FFFFFF)
        orb.background = g
        status.text = label
    }

    private fun idleState() = setOrb(cyan, "standby")

    private fun addBubble(user: Boolean, text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 15f
        tv.setTextColor(Color.parseColor("#d6f6ff"))
        tv.setPadding(dp(12), dp(8), dp(12), dp(8))
        val g = GradientDrawable()
        g.cornerRadius = dp(8).toFloat()
        g.setColor(if (user) Color.parseColor("#12333d") else Color.parseColor("#0a1820"))
        g.setStroke(dp(1), if (user) cyan else Color.parseColor("#00ff9c"))
        tv.background = g
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = if (user) Gravity.END else Gravity.START
        lp.topMargin = dp(6)
        lp.leftMargin = dp(if (user) 40 else 0)
        lp.rightMargin = dp(if (user) 0 else 40)
        logBox.addView(tv, lp)
        scrollDown()
        return tv
    }

    private fun scrollDown() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun keyDialog() {
        val et = EditText(this)
        et.setText(key)
        et.hint = "Gemini API key"
        AlertDialog.Builder(this)
            .setTitle("Gemini API key")
            .setMessage("aistudio.google.com se free key banao aur yahan paste karo")
            .setView(et)
            .setPositiveButton("SAVE") { _, _ ->
                key = et.text.toString().trim()
                prefs.edit().putString("key", key).apply()
            }
            .show()
    }

    private fun toggleAuto() {
        auto = !auto
        autoBtn.text = if (auto) "AUTO ON" else "AUTO"
        if (auto) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            awakeUntil = 0L
            startListening()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            try {
                recognizer?.cancel()
            } catch (e: Exception) {
            }
            listening = false
            micBtn.text = "MIC"
            idleState()
        }
    }

    // ---------- Speech in ----------
    private fun makeRecognizer() {
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {
                val label = if (auto && System.currentTimeMillis() > awakeUntil) "bolo: hi smarty" else "sun raha hoon"
                setOrb(Color.parseColor("#ff3b5c"), label)
                micBtn.text = "STOP"
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}

            override fun onPartialResults(p: Bundle?) {
                val t = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) input.setText(t)
            }

            override fun onError(code: Int) {
                listening = false
                micBtn.text = "MIC"
                if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    auto = false
                    autoBtn.text = "AUTO"
                    toast("Mic ki permission do (Settings > Apps > Smarty > Permissions)")
                    return
                }
                if (code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || code == SpeechRecognizer.ERROR_CLIENT) makeRecognizer()
                if (!busy) idleState()
                if (auto && !busy) ui.postDelayed({ startListening() }, 500)
            }

            override fun onResults(r: Bundle?) {
                listening = false
                micBtn.text = "MIC"
                val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                input.setText("")
                if (t.isBlank()) {
                    idleState()
                    if (auto && !busy) ui.postDelayed({ startListening() }, 300)
                    return
                }
                handle(t)
            }
        })
        recognizer = r
    }

    private fun startListening() {
        if (busy || listening) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        val r = recognizer ?: run {
            toast("Voice recognition available nahi hai")
            return
        }
        tts?.stop()
        gen++
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, langs[li])
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
        listening = true
        try {
            r.startListening(i)
        } catch (e: Exception) {
            listening = false
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) startListening()
    }

    private fun handle(text: String) {
        if (!auto) {
            send(text)
            return
        }
        if (wake.containsMatchIn(text) || System.currentTimeMillis() < awakeUntil) {
            val cmd = text.replace(strip, "").trim()
            if (cmd.length < 2) {
                speakShort("Haan bolo")
                return
            }
            send(cmd)
        } else {
            idleState()
            ui.postDelayed({ startListening() }, 300)
        }
    }

    // ---------- Speech out ----------
    override fun onInit(s: Int) {
        if (s != TextToSpeech.SUCCESS) return
        ttsReady = true
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                ui.post {
                    if (id != null && id.startsWith("$gen:")) setOrb(Color.parseColor("#00ff9c"), "bol raha hoon")
                }
            }

            override fun onDone(id: String?) {
                ui.post { done(id) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                ui.post { done(id) }
            }
        })
    }

    private fun done(id: String?) {
        if (id == null || !id.startsWith("$gen:")) return
        pending--
        if (pending <= 0 && finished) next()
    }

    private fun langOf(t: String): String = when {
        Regex("[\\u0900-\\u097F]").containsMatchIn(t) -> "hi-IN"
        Regex("[\\u0A80-\\u0AFF]").containsMatchIn(t) -> "gu-IN"
        Regex("[\\u0600-\\u06FF]").containsMatchIn(t) -> "ur-PK"
        Regex("\\b(hai|hain|kya|kaise|nahi|haan|mein|aap|tum|bhai|accha|theek|karo|batao|hoon|raha)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "hi-IN"
        else -> "en-IN"
    }

    private fun speakPiece(p: String) {
        val t = tts ?: return
        if (!ttsReady) return
        t.language = Locale.forLanguageTag(langOf(p))
        pending++
        t.speak(p.replace(Regex("[*_#`]"), ""), TextToSpeech.QUEUE_ADD, null, "$gen:${uid++}")
    }

    private fun speakShort(t: String) {
        tts?.stop()
        gen++
        pending = 0
        finished = true
        speakPiece(t)
        if (pending <= 0) next()
    }

    private fun feed(full: String, fin: Boolean) {
        if (spoken > full.length) return
        val rest = full.substring(spoken)
        var cut = -1
        if (fin) {
            cut = rest.length
        } else {
            val m = Regex("[.!?\u0964\\n]+(\\s|$)").findAll(rest).lastOrNull()
            if (m != null) cut = m.range.last + 1
        }
        if (cut > 0) {
            val piece = rest.substring(0, cut).trim()
            spoken += cut
            if (piece.isNotEmpty()) speakPiece(piece)
        }
    }

    private fun next() {
        idleState()
        if (auto && !busy) {
            awakeUntil = System.currentTimeMillis() + 15000
            ui.postDelayed({ startListening() }, 300)
        }
    }

    // ---------- Brain ----------
    private fun system(): String =
        "You are Smarty, a warm, caring personal companion and friend of one person, talking to him by voice on his phone. " +
            "Current date and time: " + java.util.Date().toString() + ".\n" +
            "RULES:\n" +
            "- Always reply in the same language and script the person used in his latest message (Hindi, Hinglish in Roman letters, English, Gujarati, Urdu, anything). Never ask which language he prefers. If he switches language, you switch too.\n" +
            "- Talk like a real human friend: natural, warm, no lists, no markdown, no emojis, usually 1 to 2 short sentences because your words are spoken aloud and speed matters. Ask about his day and feelings now and then, one question at a time.\n" +
            "- Do what he asks when you can. Be honest: if you cannot do something yet (setting alarms, reading phone notifications, using the camera), say so simply; those come in a later version of this app.\n" +
            "- Be a real friend, not a yes-man: if something he plans could hurt him, say so kindly and clearly. Gently encourage him to also stay connected with real people in his life.\n" +
            "- Use the notes below to remember him.\n\nNOTES ABOUT HIM:\n" + (if (notes.isBlank()) "(nothing yet)" else notes)

    private fun send(text: String) {
        if (busy) return
        if (key.isEmpty()) {
            keyDialog()
            return
        }
        tts?.stop()
        gen++
        pending = 0
        spoken = 0
        finished = false
        busy = true
        setOrb(Color.parseColor("#ffb300"), "soch raha hoon")
        addBubble(true, text)
        hist.add(Pair("user", text))
        val el = addBubble(false, "...")
        val contents = JSONArray()
        hist.takeLast(10).forEach {
            contents.put(
                JSONObject().put("role", if (it.first == "user") "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", it.second)))
            )
        }
        val sys = system()
        Thread {
            try {
                val raw = stream(sys, contents) { full ->
                    ui.post {
                        el.text = full.trim()
                        scrollDown()
                        feed(full, false)
                    }
                }
                ui.post {
                    el.text = raw.trim()
                    hist.add(Pair("assistant", raw.trim()))
                    save()
                    busy = false
                    feed(raw, true)
                    finished = true
                    if (pending <= 0) next()
                    turns++
                    if (turns % 5 == 0) updateNotes()
                }
            } catch (e: Exception) {
                ui.post {
                    el.text = errText(e.message)
                    if (hist.isNotEmpty()) hist.removeAt(hist.size - 1)
                    busy = false
                    idleState()
                    if (auto) next()
                }
            }
        }.start()
    }

    private fun errText(m: String?): String = when (m) {
        "400", "403" -> "Key sahi nahi lag rahi. KEY dabake dobara paste karo."
        "429" -> "Free limit abhi poori hai. Thodi der ruk ke bolo."
        "500", "503" -> "Google ka server busy hai. Dobara bolo."
        "net" -> "Internet check karo aur dobara bolo."
        else -> "Jawab nahi aaya ($m). Dobara try karo."
    }

    private fun stream(system: String, contents: JSONArray, onText: (String) -> Unit): String {
        var last = "net"
        for (attempt in 0..1) {
            for (m in models) {
                try {
                    val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:streamGenerateContent?alt=sse&key=$key")
                    val c = url.openConnection() as HttpURLConnection
                    c.requestMethod = "POST"
                    c.setRequestProperty("Content-Type", "application/json")
                    c.doOutput = true
                    c.connectTimeout = 10000
                    c.readTimeout = 30000
                    val gc = JSONObject().put("maxOutputTokens", 200)
                    if (m.contains("2.5")) gc.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                    val body = JSONObject()
                        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                        .put("contents", contents)
                        .put("generationConfig", gc)
                    c.outputStream.use { it.write(body.toString().toByteArray()) }
                    val code = c.responseCode
                    if (code != 200) {
                        last = code.toString()
                        try {
                            c.errorStream?.close()
                        } catch (e: Exception) {
                        }
                        if (code in listOf(404, 429, 500, 502, 503, 504)) continue
                        throw RuntimeException(last)
                    }
                    val sb = StringBuilder()
                    c.inputStream.bufferedReader().useLines { lines ->
                        for (ln in lines) {
                            if (!ln.startsWith("data:")) continue
                            try {
                                val j = JSONObject(ln.substring(5).trim())
                                val parts = j.getJSONArray("candidates").getJSONObject(0)
                                    .getJSONObject("content").getJSONArray("parts")
                                for (k in 0 until parts.length()) sb.append(parts.getJSONObject(k).optString("text"))
                                onText(sb.toString())
                            } catch (e: Exception) {
                            }
                        }
                    }
                    if (sb.isNotBlank()) return sb.toString()
                } catch (e: java.io.IOException) {
                    last = "net"
                }
            }
            try {
                Thread.sleep(1200)
            } catch (e: Exception) {
            }
        }
        throw RuntimeException(last)
    }

    private fun updateNotes() {
        val chat = hist.takeLast(20).joinToString("\n") { (if (it.first == "user") "Person: " else "Smarty: ") + it.second }
        val prompt = "Update the notes about this person. Keep every durable fact he stated (name, family, work, likes, plans, what he asked Smarty to do), drop small talk. Max 15 short lines, plain text only.\n\nOLD NOTES:\n" +
            (if (notes.isBlank()) "(none)" else notes) + "\n\nRECENT CHAT:\n" + chat + "\n\nNEW NOTES:"
        val c = JSONArray().put(
            JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        )
        Thread {
            try {
                val n = stream("You keep short notes about a person.", c) { }
                ui.post {
                    notes = n.trim()
                    save()
                }
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun save() {
        val a = JSONArray()
        hist.takeLast(40).forEach { a.put(JSONObject().put("r", it.first).put("t", it.second)) }
        prefs.edit().putString("hist", a.toString()).putString("notes", notes).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
        }
        tts?.shutdown()
    }
}
