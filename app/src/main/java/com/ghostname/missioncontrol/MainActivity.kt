package com.ghostname.missioncontrol

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Mission Control: a read-only live view of the owner's AI worker fleet.
 * Polls the published dashboard snapshot JSON every 30 seconds and renders
 * header counts, a needs-you queue, agent cards, and today's completions.
 */
class MainActivity : Activity() {

    companion object {
        private const val FEED_URL =
            "https://raw.githubusercontent.com/Yametech12/mission-control-app/main/snapshot.json"
        private const val POLL_MS = 30_000L
        private const val STUCK_AFTER_MS = 15 * 60 * 1000L

        val colRunning: Int = Color.parseColor("#2FA84F")
        val colDone: Int = Color.parseColor("#3E7CB1")
        val colAlert: Int = Color.parseColor("#D64545")
        val colIdle: Int = Color.parseColor("#8A8378")

        fun parseIso(value: String?): Long? {
            if (value.isNullOrBlank()) return null
            val patterns = arrayOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX"
            )
            for (p in patterns) {
                try {
                    val f = SimpleDateFormat(p, Locale.US)
                    f.timeZone = TimeZone.getTimeZone("UTC")
                    return f.parse(value)?.time
                } catch (e: Exception) {
                    // try the next pattern
                }
            }
            return null
        }
    }

    private val manila: TimeZone = TimeZone.getTimeZone("Asia/Manila")
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var rootColumn: LinearLayout
    private lateinit var clockView: TextView
    private lateinit var feedStateView: TextView
    private lateinit var countsRow: LinearLayout
    private lateinit var sectionsColumn: LinearLayout

    private var feed: JSONObject? = null
    private var lastTickMinute: Long = -1

    // Palette, chosen for warm graphite (dark) and warm paper (light).
    private var bg = 0
    private var cardBg = 0
    private var textMain = 0
    private var textMuted = 0
    private var accent = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPalette()
        buildUi()
        fetchFeed()
        handler.post(pollLoop)
        handler.post(clockLoop)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    private fun applyPalette() {
        val night = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (night) {
            bg = Color.parseColor("#141210")
            cardBg = Color.parseColor("#201C17")
            textMain = Color.parseColor("#F2EDE4")
            textMuted = Color.parseColor("#A79E8E")
            accent = Color.parseColor("#E8B04B")
        } else {
            bg = Color.parseColor("#F6F1E7")
            cardBg = Color.parseColor("#FFFFFF")
            textMain = Color.parseColor("#211D16")
            textMuted = Color.parseColor("#7A7263")
            accent = Color.parseColor("#9A6A14")
        }
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)
        scroll.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        rootColumn = LinearLayout(this)
        rootColumn.orientation = LinearLayout.VERTICAL
        rootColumn.setPadding(dp(16), dp(18), dp(16), dp(24))
        scroll.addView(rootColumn)
        setContentView(scroll)

        val eyebrow = TextView(this)
        eyebrow.text = "GHOSTNAME OPERATIONS"
        eyebrow.setTextColor(accent)
        eyebrow.textSize = 11f
        eyebrow.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        eyebrow.letterSpacing = 0.18f
        rootColumn.addView(eyebrow)

        val title = TextView(this)
        title.text = "Mission Control"
        title.setTextColor(textMain)
        title.textSize = 30f
        title.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        title.setPadding(0, dp(2), 0, 0)
        rootColumn.addView(title)

        clockView = TextView(this)
        clockView.setTextColor(textMuted)
        clockView.textSize = 14f
        clockView.typeface = Typeface.MONOSPACE
        rootColumn.addView(clockView)

        feedStateView = TextView(this)
        feedStateView.text = "Loading the latest snapshot..."
        feedStateView.setTextColor(textMuted)
        feedStateView.textSize = 12f
        feedStateView.setPadding(0, dp(4), 0, dp(10))
        rootColumn.addView(feedStateView)

        countsRow = LinearLayout(this)
        countsRow.orientation = LinearLayout.HORIZONTAL
        rootColumn.addView(countsRow)

        sectionsColumn = LinearLayout(this)
        sectionsColumn.orientation = LinearLayout.VERTICAL
        sectionsColumn.setPadding(0, dp(14), 0, 0)
        rootColumn.addView(sectionsColumn)

        val footer = TextView(this)
        footer.text = "Made by Mohammad Sidique Ibrahim"
        footer.setTextColor(textMuted)
        footer.textSize = 11f
        footer.gravity = Gravity.CENTER
        footer.setPadding(0, dp(22), 0, 0)
        rootColumn.addView(footer)
    }

    private fun countCard(value: String, label: String, color: Int): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER
        card.background = rounded(cardBg, dp(12))
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.marginEnd = dp(8)
        card.layoutParams = lp
        card.setPadding(dp(6), dp(10), dp(6), dp(10))
        val v = TextView(this)
        v.text = value
        v.setTextColor(color)
        v.textSize = 22f
        v.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        v.gravity = Gravity.CENTER
        card.addView(v)
        val l = TextView(this)
        l.text = label
        l.setTextColor(textMuted)
        l.textSize = 10f
        l.gravity = Gravity.CENTER
        card.addView(l)
        return card
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = radius.toFloat()
        d.setColor(color)
        return d
    }

    private fun sectionHeader(text: String, color: Int) {
        val h = TextView(this)
        h.text = text
        h.setTextColor(color)
        h.textSize = 13f
        h.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        h.letterSpacing = 0.12f
        h.setPadding(0, dp(18), 0, dp(8))
        sectionsColumn.addView(h)
    }

    private fun emptyLine(text: String) {
        val t = TextView(this)
        t.text = text
        t.setTextColor(textMuted)
        t.textSize = 13f
        t.setPadding(dp(2), 0, 0, dp(4))
        sectionsColumn.addView(t)
    }

    // ------------------------------------------------------------- data

    private data class Agent(
        val name: String,
        val role: String?,
        val status: String?,
        val agentStatus: String?,
        val title: String?,
        val detail: String?,
        val currentTask: String?,
        val lastHeartbeat: String?,
        val observedAt: String?,
        val needsUser: Boolean
    ) {
        fun activityMillis(): Long? = parseIso(lastHeartbeat) ?: parseIso(observedAt)
        fun isStuck(now: Long): Boolean {
            if (status != "working") return false
            val last = activityMillis() ?: return false
            return now - last > STUCK_AFTER_MS
        }
        fun isAlert(now: Long): Boolean =
            needsUser || status == "attention" || agentStatus == "error" || isStuck(now)
        fun isRunning(now: Long): Boolean =
            status == "working" && agentStatus != "error" && !isStuck(now)
        fun statusColor(now: Long): Int = when {
            isStuck(now) || status == "attention" || agentStatus == "error" -> colAlert
            status == "working" -> colRunning
            status == "completed" -> colDone
            else -> colIdle
        }
        fun statusWord(now: Long): String = when {
            isStuck(now) -> "STUCK"
            agentStatus == "error" -> "ERROR"
            status == "attention" -> "NEEDS ATTENTION"
            status == "working" -> "RUNNING"
            status == "completed" -> "DONE"
            status == "scheduled" -> "SCHEDULED"
            else -> (status ?: "UNKNOWN").uppercase(Locale.US)
        }
    }

    private fun parseAgents(root: JSONObject): List<Agent> {
        val arr = root.optJSONArray("agents") ?: return emptyList()
        val out = ArrayList<Agent>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Agent(
                    name = o.optString("name").ifBlank { o.optString("title", "Agent") },
                    role = o.optString("role").ifBlank { null },
                    status = o.optString("status").ifBlank { null },
                    agentStatus = o.optString("agentStatus").ifBlank { null },
                    title = o.optString("title").ifBlank { null },
                    detail = o.optString("detail").ifBlank { null },
                    currentTask = o.optString("currentTask").ifBlank { null },
                    lastHeartbeat = o.optString("lastHeartbeat").ifBlank { null },
                    observedAt = o.optString("observedAt").ifBlank { null },
                    needsUser = o.optBoolean("needsUser", false)
                )
            )
        }
        return out
    }

    private fun fetchFeed() {
        io.execute {
            var text: String? = null
            var error: String? = null
            try {
                val conn = URL(FEED_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 12000
                conn.readTimeout = 12000
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("Cache-Control", "no-cache")
                if (conn.responseCode in 200..299) {
                    text = conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    error = "Feed returned HTTP " + conn.responseCode
                }
                conn.disconnect()
            } catch (e: Exception) {
                error = e.message ?: "Network error"
            }
            val body = text
            val err = error
            handler.post {
                if (body != null) {
                    try {
                        feed = JSONObject(body)
                        render()
                    } catch (e: Exception) {
                        feedStateView.text = "Feed could not be read. Retrying..."
                    }
                } else if (feed == null) {
                    feedStateView.text =
                        "Cannot reach the feed yet (" + (err ?: "offline") + "). Retrying every 30s."
                }
            }
        }
    }

    // ------------------------------------------------------------ render

    private fun render() {
        val root = feed ?: return
        val now = System.currentTimeMillis()
        val agents = parseAgents(root)
        val generatedAt = root.optString("generatedAt").ifBlank { null }

        val running = agents.count { it.isRunning(now) }
        val alerts = agents.count { it.isAlert(now) }
        val doneToday = agents.count {
            it.status == "completed" && isTodayManila(it.observedAt)
        }

        countsRow.removeAllViews()
        countsRow.addView(countCard(running.toString(), "RUNNING NOW", colRunning))
        countsRow.addView(countCard(doneToday.toString(), "DONE TODAY", colDone))
        val alertLp = countCard(alerts.toString(), "STUCK / BLOCKED", colAlert)
        countsRow.addView(alertLp)

        feedStateView.text = if (generatedAt != null) {
            "Snapshot " + manilaTime(generatedAt) + " PHT, refreshes every 30s"
        } else {
            "Live, refreshes every 30s"
        }

        sectionsColumn.removeAllViews()

        val needsYou = agents.filter { it.isAlert(now) }
        sectionHeader("NAGKINAHANGLAN SA IMO", colAlert)
        if (needsYou.isEmpty()) {
            emptyLine("Nothing is waiting on you right now.")
        } else {
            needsYou.take(40).forEach { agentCard(it, now, waiting = true) }
        }

        val runningList = agents.filter { it.isRunning(now) }
        sectionHeader("RUNNING NOW", colRunning)
        if (runningList.isEmpty()) {
            emptyLine("No workers are running right now.")
        } else {
            runningList.take(40).forEach { agentCard(it, now, waiting = false) }
        }

        val doneList = agents.filter {
            it.status == "completed" && isTodayManila(it.observedAt)
        }
        sectionHeader("NAHUMAN KARONG ADLAWA", colDone)
        if (doneList.isEmpty()) {
            emptyLine("Nothing has finished yet today.")
        } else {
            doneList.take(60).forEach { agentCard(it, now, waiting = false) }
        }

        val shown = HashSet<Agent>()
        shown.addAll(needsYou); shown.addAll(runningList); shown.addAll(doneList)
        val others = agents.filter { it !in shown }
        if (others.isNotEmpty()) {
            sectionHeader("OTHER RECENT WORK", textMuted)
            others.take(25).forEach { agentCard(it, now, waiting = false) }
        }
    }

    private fun agentCard(a: Agent, now: Long, waiting: Boolean) {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.background = rounded(cardBg, dp(12))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(8)
        card.layoutParams = lp

        val stripe = View(this)
        stripe.background = rounded(a.statusColor(now), dp(3))
        val slp = LinearLayout.LayoutParams(dp(5), ViewGroup.LayoutParams.MATCH_PARENT)
        stripe.layoutParams = slp
        card.addView(stripe)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), dp(10), dp(12), dp(10))
        col.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        card.addView(col)

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        val name = TextView(this)
        name.text = a.name
        name.setTextColor(textMain)
        name.textSize = 15f
        name.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        name.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        head.addView(name)
        val badge = TextView(this)
        badge.text = a.statusWord(now)
        badge.setTextColor(a.statusColor(now))
        badge.textSize = 10f
        badge.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        head.addView(badge)
        col.addView(head)

        val roleLine = listOfNotNull(
            a.role?.replaceFirstChar { it.uppercase() },
            a.title
        ).joinToString("  ·  ")
        if (roleLine.isNotBlank()) {
            val r = TextView(this)
            r.text = roleLine
            r.setTextColor(textMuted)
            r.textSize = 11f
            col.addView(r)
        }

        val body = when {
            waiting -> a.detail ?: a.currentTask
            else -> a.currentTask ?: a.detail
        }
        if (!body.isNullOrBlank()) {
            val b = TextView(this)
            b.text = body
            b.setTextColor(textMain)
            b.textSize = 13f
            b.setPadding(0, dp(4), 0, 0)
            col.addView(b)
        }

        extractProgress(a)?.let { (done, total) ->
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
            bar.max = total
            bar.progress = done
            val blp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            blp.topMargin = dp(6)
            bar.layoutParams = blp
            col.addView(bar)
            val cap = TextView(this)
            cap.text = "$done of $total"
            cap.setTextColor(textMuted)
            cap.textSize = 10f
            col.addView(cap)
        }

        val ago = a.activityMillis()?.let { agoText(now - it) }
        val foot = TextView(this)
        foot.text = if (ago != null) "Last activity $ago" else "No heartbeat recorded"
        foot.setTextColor(if (a.isStuck(now)) colAlert else textMuted)
        foot.textSize = 11f
        foot.setPadding(0, dp(4), 0, 0)
        col.addView(foot)

        sectionsColumn.addView(card)
    }

    private fun extractProgress(a: Agent): Pair<Int, Int>? {
        val texts = listOfNotNull(a.detail, a.currentTask, a.title)
        val frac = Regex("""(\d+)\s*(?:of|/)\s*(\d+)""")
        val pct = Regex("""(\d+)\s*%""")
        for (t in texts) {
            val m = frac.find(t)
            if (m != null) {
                val d = m.groupValues[1].toIntOrNull()
                val tot = m.groupValues[2].toIntOrNull()
                if (d != null && tot != null && tot > 0 && d <= tot) return d to tot
            }
        }
        for (t in texts) {
            val m = pct.find(t)
            if (m != null) {
                val p = m.groupValues[1].toIntOrNull()
                if (p != null && p in 0..100) return p to 100
            }
        }
        return null
    }

    // ------------------------------------------------------------- loops

    private val pollLoop = object : Runnable {
        override fun run() {
            fetchFeed()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val clockLoop = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val fmt = SimpleDateFormat("EEE, MMM d  ·  h:mm:ss a", Locale.US)
            fmt.timeZone = manila
            clockView.text = fmt.format(now) + " PHT"
            val minute = now / 60_000
            if (lastTickMinute != minute) {
                lastTickMinute = minute
                if (feed != null) render()
            }
            handler.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------ helpers

    private fun isTodayManila(iso: String?): Boolean {
        val t = parseIso(iso) ?: return false
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        dayFmt.timeZone = manila
        return dayFmt.format(t) == dayFmt.format(System.currentTimeMillis())
    }

    private fun manilaTime(iso: String): String {
        val t = parseIso(iso) ?: return iso
        val f = SimpleDateFormat("h:mm a", Locale.US)
        f.timeZone = manila
        return f.format(t)
    }

    private fun agoText(diffMs: Long): String {
        if (diffMs < 0) return "just now"
        val mins = diffMs / 60_000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 60 * 24 -> {
                val h = mins / 60
                if (h == 1L) "1 hr ago" else "$h hrs ago"
            }
            else -> {
                val d = mins / (60 * 24)
                if (d == 1L) "1 day ago" else "$d days ago"
            }
        }
    }
}
