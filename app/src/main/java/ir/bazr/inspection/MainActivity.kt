package ir.bazr.inspection

import android.content.Intent
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.Cipher

/** Native Android inspector client. Server remains the source of authority. */
class MainActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var store: SecureStore
    private lateinit var api: ApiClient
    private lateinit var keys: DeviceKeys
    private var accountToken: String? = null  // Web login token: never persisted.
    private var deviceToken: String? = null   // In-memory after normal or biometric unlock.
    private var sessionToken: String? = null  // Short-lived mobile bearer.
    private var serverSnapshot = JSONObject()
    private var selectedMission: JSONObject? = null
    private var selectedMissionKey: String? = null
    private var openDraft: InspectionDraft? = null
    private var pickerQuestion: String? = null
    private var busy = false
    private lateinit var content: LinearLayout
    private lateinit var shell: LinearLayout
    private var loadingBar: LinearProgressIndicator? = null
    private var activeTab = "home"
    private var currentQuestionIndex = 0
    // Official institutional palette; the accents are intentionally restrained.
    private val navy = Color.rgb(8, 31, 56)
    private val teal = Color.rgb(170, 130, 56) // legacy accent identifier; now ceremonial gold
    private val gold = Color.rgb(197, 161, 90)
    private val muted = Color.rgb(103, 117, 135)
    private val ink = Color.rgb(20, 45, 71)
    private val pageBg = Color.rgb(245, 247, 250)
    private val line = Color.rgb(220, 227, 234)
    private val evidencePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = pickerQuestion
        if (uri != null && id != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* picker may supply temporary access only */ }
            openDraft?.attachments?.put(id, uri.toString())
            openDraft?.save()
            selectedMission?.let { showMission(it, currentQuestionIndex) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureStore(this)
        keys = DeviceKeys()
        api = ApiClient(BuildConfig.API_BASE_URL)
        store.getString("device_id") ?: store.putString("device_id", UUID.randomUUID().toString())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectedMission != null) showHome("missions")
                else if (activeTab != "home" && ::content.isInitialized) showHome("home")
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        val cached = store.load("mobile_sync")
        if (cached != null) try { serverSnapshot = JSONObject(cached) } catch (_: Exception) { }
        when {
            store.biometricEnabled() -> showBiometricLock()
            store.load("device_token") != null -> {
                deviceToken = store.load("device_token")
                showHome()
                connectAndSync()
            }
            store.getString("request_id") != null -> showPending()
            else -> showLogin()
        }
    }

    override fun onDestroy() { io.shutdown(); super.onDestroy() }
    private fun deviceId() = store.getString("device_id") ?: error("missing device ID")
    private fun notifyShort(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun dp(x: Int): Int = (x * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 16): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun text(text: String, size: Int, bold: Boolean = false, color: Int = ink): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            includeFontPadding = true
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
    private fun gap(height: Int = 12) {
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }
    private fun screen(title: String, subtitle: String, withNav: Boolean = false): LinearLayout {
        window.statusBarColor = navy
        window.navigationBarColor = Color.WHITE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(pageBg)
        }
        loadingBar = LinearProgressIndicator(this).apply {
            isIndeterminate = true
            setIndicatorColor(teal)
            trackColor = Color.rgb(230,236,243)
            visibility = if (busy) View.VISIBLE else View.GONE
        }
        shell.addView(loadingBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        val scroll = ScrollView(this).apply {
            fillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(16), dp(16), dp(26))
        }
        scroll.addView(content)
        shell.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (withNav) shell.addView(bottomNavigation())
        setContentView(shell)
        officialHeader()
        if (title.isNotBlank()) label(title, 22, true, navy)
        if (subtitle.isNotBlank()) label(subtitle, 13, false, muted)
        if (title.isNotBlank() || subtitle.isNotBlank()) gap(8)
        return content
    }
    /** Shared responsive native banner; never renders a website/WebView. */
    private fun officialHeader() {
        val holder = MaterialCardView(this).apply {
            radius = dp(15).toFloat()
            strokeColor = gold
            strokeWidth = dp(1)
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(navy)
        }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val image = ImageView(this).apply {
            setImageResource(R.drawable.official_brand_banner)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "وزارت کشور، استانداری کرمانشاه، فرمانداری شهرستان جوانرود؛ بازرسی ادارات"
        }
        layout.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        layout.addView(View(this).apply { setBackgroundColor(gold) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
        holder.addView(layout)
        content.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        gap(16)
    }

    /** Subtly raised two-tone icon, consistent across dashboard and missions. */
    private fun iconBadge(drawable: Int, size: Int = 24): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(13).toFloat()
        strokeWidth = dp(1)
        strokeColor = Color.rgb(222, 200, 151)
        cardElevation = dp(1).toFloat()
        setCardBackgroundColor(Color.rgb(251, 247, 238))
        val image = icon(drawable, navy, size).apply {
            layoutParams = FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER)
            contentDescription = null // descriptive text immediately follows the icon
        }
        addView(image)
    }

    private fun label(value: String, size: Int = 16, bold: Boolean = false, color: Int = ink): TextView {
        val view = text(value, size, bold, color)
        view.setPadding(0, dp(3), 0, dp(6))
        content.addView(view)
        return view
    }
    private fun field(hint: String, value: String = "", password: Boolean = false, numeric: Boolean = false): EditText {
        val wrapper = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = Color.WHITE
            boxStrokeColor = gold
            setBoxCornerRadii(dp(13).toFloat(),dp(13).toFloat(),dp(13).toFloat(),dp(13).toFloat())
            this.hint = hint
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val input = TextInputEditText(this).apply {
            setText(value)
            textSize = 15f
            setTextColor(ink)
            setHintTextColor(muted)
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                hint.contains("توضیح") -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (hint.contains("توضیح")) { minLines = 3; maxLines = 5; gravity = Gravity.TOP or Gravity.RIGHT }
        }
        wrapper.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.bottomMargin = dp(10)
        content.addView(wrapper, params)
        return input
    }
    private fun makeButton(caption: String, primary: Boolean = true, action: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = caption
            textSize = 15f
            isAllCaps = false
            cornerRadius = dp(13)
            minHeight = dp(52)
            backgroundTintList = ColorStateList.valueOf(if (primary) navy else Color.WHITE)
            setTextColor(if (primary) Color.WHITE else navy)
            strokeColor = ColorStateList.valueOf(if (primary) gold else line)
            strokeWidth = dp(1)
            elevation = if (primary) dp(1).toFloat() else 0f
            setOnClickListener { if (!busy) action() }
        }
    private fun button(caption: String, action: () -> Unit): MaterialButton {
        val b = makeButton(caption, true, action)
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
        params.bottomMargin = dp(9)
        content.addView(b, params)
        return b
    }
    private fun secondaryButton(caption: String, action: () -> Unit): MaterialButton {
        val b = makeButton(caption, false, action)
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        params.bottomMargin = dp(9)
        content.addView(b, params)
        return b
    }
    private fun icon(res: Int, color: Int = teal, size: Int = 22): ImageView = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        contentDescription = null // text is supplied by the adjacent accessible label
    }
    private fun card(textValue: String, action: () -> Unit) {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat(); cardElevation = 0f; strokeWidth = dp(1); strokeColor = line
            setCardBackgroundColor(Color.WHITE)
            isClickable = true; isFocusable = true
            setOnClickListener { if (!busy) action() }
        }
        val label = text(textValue, 15, true)
        label.setPadding(dp(16), dp(16), dp(16), dp(16))
        card.addView(label)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        content.addView(card, lp)
    }
    private fun chip(caption: String, background: Int, foreground: Int): TextView =
        text(caption, 12, true, foreground).apply {
            setBackground(rounded(background, 100))
            setPadding(dp(12), dp(7), dp(12), dp(7))
        }
    private fun sectionTitle(caption: String, actionLabel: String? = null, onClick: (() -> Unit)? = null) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(caption, 18, true, navy), LinearLayout.LayoutParams(0, dp(44), 1f))
        if (actionLabel != null && onClick != null) {
            val link = text(actionLabel, 13, true, teal)
            link.setPadding(dp(8), 0, dp(8), 0)
            link.isClickable = true; link.isFocusable = true
            link.setOnClickListener { onClick() }
            row.addView(link)
        }
        content.addView(row)
    }
    private fun bottomNavigation(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(9))
            elevation = dp(5).toFloat()
        }
        val items = listOf(
            Triple("home", "خانه", R.drawable.ic_home),
            Triple("missions", "مأموریت‌ها", R.drawable.ic_clipboard),
            Triple("drafts", "پیش‌نویس‌ها", R.drawable.ic_notes),
            Triple("account", "حساب", R.drawable.ic_person)
        )
        for ((id, caption, drawable) in items) {
            val chosen = activeTab == id
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(1), dp(8), dp(1), dp(7))
                background = rounded(if (chosen) navy else Color.WHITE, 13)
                isClickable = true; isFocusable = true
                contentDescription = caption
                setOnClickListener { if (!busy) showHome(id) }
            }
            item.addView(icon(drawable, if (chosen) gold else muted, 21))
            val label = text(caption, 11, chosen, if (chosen) Color.WHITE else muted)
            label.gravity = Gravity.CENTER
            item.addView(label)
            bar.addView(item, LinearLayout.LayoutParams(0, dp(63), 1f))
        }
        return bar
    }
    private fun statCard(number: String, caption: String, drawable: Int, background: Int, foreground: Int): View {
        val outer = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            strokeWidth = dp(1)
            strokeColor = line
            cardElevation = dp(1).toFloat()
            setCardBackgroundColor(Color.WHITE)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(14))
        }
        val badge = iconBadge(drawable, 23)
        inner.addView(badge, LinearLayout.LayoutParams(dp(44), dp(44)))
        inner.addView(text(number, 27, true, navy).apply { setPadding(0, dp(6), 0, 0) })
        inner.addView(text(caption, 12, false, muted))
        outer.addView(inner)
        return outer
    }
    private fun statsRow(left: View, right: View) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val a = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) }
        val b = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(5) }
        row.addView(left, a); row.addView(right, b)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        content.addView(row, lp)
    }
    private fun hero(profileName: String) {
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            strokeWidth = dp(1)
            strokeColor = Color.rgb(221, 202, 160)
            cardElevation = dp(1).toFloat()
            setCardBackgroundColor(Color.WHITE)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(17), dp(18), dp(17))
        }
        box.addView(text("میز کار بازرس  •  دسترسی سازمانی", 12, true, teal))
        val name = profileName.trim().ifBlank { "بازرس" }
        box.addView(text("سلام، $name", 22, true, navy).apply { setPadding(0, dp(10), 0, dp(5)) })
        box.addView(text("مأموریت‌ها و گزارش‌های شما در یک نگاه", 13, false, muted))
        card.addView(box)
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        gap(13)
    }
    private fun emptyState(title: String, detail: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(23), dp(20), dp(23))
            setBackground(rounded(Color.WHITE, 16))
        }
        box.addView(icon(R.drawable.ic_clipboard, muted, 32))
        val titleView = text(title, 16, true, navy).apply { gravity = Gravity.CENTER }
        titleView.setPadding(0,dp(10),0,dp(6))
        box.addView(titleView)
        val detailView = text(detail, 13, false, muted).apply { gravity = Gravity.CENTER }
        box.addView(detailView)
        content.addView(box)
    }
    private fun <T> work(task: () -> T, done: (T) -> Unit) {
        if (busy) return
        busy = true
        loadingBar?.visibility = View.VISIBLE
        io.execute {
            try {
                val result = task()
                runOnUiThread {
                    busy = false
                    loadingBar?.visibility = View.GONE
                    if (!isFinishing && !isDestroyed) done(result)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    loadingBar?.visibility = View.GONE
                    if (!isFinishing && !isDestroyed) notifyShort(e.message?.take(280) ?: "خطا در ارتباط")
                }
            }
        }
    }

    private fun showLogin() {
        selectedMission = null; activeTab = "home"
        screen("ورود به حساب سازمانی", "دسترسی ویژه بازرسان مجاز")
        label("برای شروع، نام کاربری و رمز عبور خود را وارد کنید. ثبت دستگاه جدید با تأیید مدیر انجام می‌شود.", 13, false, muted)
        gap(12)
        val username = field("نام کاربری")
        val password = field("رمز عبور", password = true)
        button("ورود امن و ثبت دستگاه") {
            val name = username.text.toString().trim()
            val pass = password.text.toString()
            if (name.isBlank() || pass.isBlank()) { notifyShort("نام کاربری و رمز عبور لازم است"); return@button }
            work({
                val login = api.post("login.php", JSONObject().put("kind", "inspector").put("username", name).put("password", pass))
                val token = login.getString("token")
                val req = JSONObject()
                    .put("device_id", deviceId())
                    .put("public_key_pem", keys.publicKeyPem())
                    .put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}".take(190))
                    .put("app_version", BuildConfig.VERSION_NAME)
                val result = api.post("mobile_activate_authenticated.php", req, token)
                accountToken = token
                result
            }) { response ->
                store.putString("request_id", response.getString("request_id"))
                showPending()
                if (response.optString("status") == "approved") checkApproval()
            }
        }
        label("ورود اولیه به اینترنت نیاز دارد. رمز عبور روی دستگاه ذخیره نمی‌شود.", 12, false, muted)
        gap(10)
        if (store.getString("request_id") != null) secondaryButton("پیگیری درخواست فعال‌سازی") { showPending() }
    }

    private fun showPending() {
        selectedMission = null
        screen("تأیید دستگاه", "یک مرحله تا دسترسی به مأموریت‌ها باقی مانده است")
        gap(12)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18),dp(20),dp(18),dp(20))
            setBackground(rounded(Color.WHITE,18))
        }
        panel.addView(icon(R.drawable.ic_shield, teal, 30))
        val title = text("در انتظار تأیید مدیر سامانه", 17, true, navy)
        title.setPadding(0,dp(12),0,dp(8))
        panel.addView(title)
        panel.addView(text("برای حفظ امنیت اطلاعات بازرسی، این دستگاه باید توسط مدیر تأیید شود.",13,false,muted))
        panel.addView(text("کد درخواست: ${store.getString("request_id") ?: "ثبت نشده"}",12,false,muted).apply {
            setPadding(0,dp(12),0,0)
        })
        val lp=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin=dp(16)
        content.addView(panel,lp)
        button("بررسی وضعیت تأیید") { checkApproval() }
        secondaryButton("بازگشت به ورود") { showLogin() }
    }

    private fun signedBody(prefix: String, requestId: String? = null): JSONObject {
        val ts = Instant.now().toString()
        val nonce = keys.nonce()
        val id = deviceId()
        val message = if (prefix == "session") "session|$id|$ts|$nonce"
            else "activation-status|$requestId|$id|$ts|$nonce"
        val json = JSONObject().put("device_id", id).put("timestamp", ts)
            .put("nonce", nonce).put("signature", keys.signed(message))
        if (requestId != null) json.put("request_id", requestId)
        return json
    }
    private fun checkApproval() {
        val id=store.getString("request_id") ?: return
        work({ api.post("mobile_activate_status.php", signedBody("activation-status", id)) }) { response ->
            when (response.optString("status")) {
                "approved" -> {
                    val fresh = response.getString("device_token")
                    // Status checks rotate the device token; discard any old biometric binding.
                    store.clearBio()
                    store.save("device_token", fresh)
                    deviceToken = fresh
                    notifyShort("دستگاه تأیید شد")
                    showHome("home")
                    connectAndSync()
                }
                "revoked" -> { store.clear("device_token"); store.clearBio(); showPending(); notifyShort("دستگاه لغو شده است") }
                else -> notifyShort("در انتظار تأیید مدیر")
            }
        }
    }

    private fun sessionFor(token: String): String {
        val body = signedBody("session").put("device_token", token)
        return api.post("mobile_session.php", body).getString("token")
    }
    private fun connectAndSync() {
        val token=deviceToken ?: return
        work({
            val session = sessionFor(token)
            val payload = api.get("mobile_sync.php", session)
            Pair(session, payload)
        }) { (session, payload) ->
            sessionToken = session
            serverSnapshot = payload
            store.save("mobile_sync", payload.toString())
            store.putString("last_sync", Instant.now().toString())
            showHome(activeTab)
        }
    }

    private fun missionKey(mission: JSONObject): String =
        mission.optString("_server_key", mission.optString("id"))

    private fun missionDraft(mission: JSONObject): Boolean =
        missionKey(mission).let { it.isNotBlank() && store.load("draft:$it") != null }

    private fun reportCount(): Int = (serverSnapshot.optJSONArray("reports") ?: JSONArray()).length()

    private fun missionItem(mission: JSONObject) {
        val key = missionKey(mission)
        val name = mission.optString("orgName", "دستگاه اجرایی").ifBlank { "دستگاه اجرایی" }
        val date = mission.optString("date", "").trim()
        val available = mission.optBoolean("can_execute", false)
        val state = mission.optString("schedule_label", "").ifBlank { if (available) "قابل انجام" else "زمان‌بندی مأموریت" }
        val isDraft = key.isNotBlank() && missionDraft(mission)
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            strokeWidth = dp(1)
            strokeColor = line
            cardElevation = 0f
            setCardBackgroundColor(Color.WHITE)
            isClickable = true; isFocusable = true
            setOnClickListener { if (!busy) showMission(mission) }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14),dp(15),dp(14),dp(15))
        }
        val symbol = iconBadge(R.drawable.ic_clipboard, 24)
        root.addView(symbol, LinearLayout.LayoutParams(dp(49), dp(49)))
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12),0,dp(9),0)
        }
        info.addView(text(name,15,true,navy))
        info.addView(text(listOf(date,state).filter { it.isNotBlank() }.joinToString("  •  "),12,false,muted))
        if (isDraft) info.addView(text("پیش‌نویس ذخیره شده",12,true,teal))
        root.addView(info,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        root.addView(icon(R.drawable.ic_chevron, muted, 19))
        card.addView(root)
        val lp=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin=dp(10)
        content.addView(card,lp)
    }

    private fun showHome(tab: String = "home") {
        selectedMission = null
        openDraft = null
        activeTab = tab
        val profile = serverSnapshot.optJSONObject("profile")
        val profileName = profile?.optString("name", "بازرس") ?: "بازرس"
        val missions = serverSnapshot.optJSONArray("missions") ?: JSONArray()
        val all = (0 until missions.length()).mapNotNull { missions.optJSONObject(it) }
        val available = all.filter { it.optBoolean("can_execute",false) }
        val drafted = all.filter { missionDraft(it) }
        when (tab) {
            "home" -> {
                screen("", "", withNav = true)
                hero(profileName)
                val indicators = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                indicators.addView(chip(if (sessionToken != null) "نشست فعال" else "اطلاعات ذخیره‌شده", 
                    if (sessionToken != null) Color.rgb(228,245,237) else Color.rgb(255,246,228),
                    if (sessionToken != null) Color.rgb(25,124,79) else Color.rgb(154,104,27)))
                val lpChip = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT)
                lpChip.marginStart=dp(7)
                indicators.addView(chip(if (store.getString("last_sync") != null) "همگام‌سازی قبلی ثبت شده" else "نیازمند همگام‌سازی",
                    Color.rgb(235,242,251),navy),lpChip)
                content.addView(indicators)
                gap(20)
                sectionTitle("خلاصه کار من")
                statsRow(
                    statCard(all.size.toString(), "کل مأموریت‌ها", R.drawable.ic_clipboard, Color.rgb(231,241,252),navy),
                    statCard(available.size.toString(), "قابل انجام", R.drawable.ic_check, Color.rgb(228,247,240),Color.rgb(21,116,77))
                )
                statsRow(
                    statCard(drafted.size.toString(), "پیش‌نویس‌ها", R.drawable.ic_notes, Color.rgb(243,238,252),Color.rgb(99,69,150)),
                    statCard(reportCount().toString(), "گزارش‌های همگام‌شده", R.drawable.ic_chart, Color.rgb(255,245,226),Color.rgb(156,99,27))
                )
                gap(5)
                button("شروع بازرسی") {
                    if (available.isNotEmpty()) showMission(available.first())
                    else showHome("missions")
                }
                secondaryButton("همگام‌سازی مأموریت‌ها") {
                    if (deviceToken == null) notifyShort("نشست دستگاه فعال نیست") else connectAndSync()
                }
                gap(12)
                sectionTitle("مأموریت‌های من", "نمایش همه") { showHome("missions") }
                if (all.isEmpty()) emptyState("مأموریتی دریافت نشده", "اگر به شما مأموریت داده شده، با اتصال اینترنت همگام‌سازی کنید.")
                else all.take(3).forEach { missionItem(it) }
                gap(8)
                label("پیش‌نویس‌ها بدون اینترنت هم ذخیره می‌شوند؛ ارسال نهایی نیازمند اینترنت است.",12,false,muted)
            }
            "missions" -> {
                screen("مأموریت‌های من", "مأموریت‌های دریافت‌شده از سامانه",withNav=true)
                val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
                row.addView(chip("همه: ${all.size}",Color.rgb(231,241,252),navy))
                val lp=LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.marginStart=dp(8)
                row.addView(chip("قابل انجام: ${available.size}",Color.rgb(228,247,240),Color.rgb(21,116,77)),lp)
                content.addView(row)
                gap(18)
                if (all.isEmpty()) emptyState("فهرست مأموریت‌ها خالی است","برای دریافت مأموریت‌ها همگام‌سازی کنید.")
                else all.forEach { missionItem(it) }
                gap(10)
                secondaryButton("همگام‌سازی") { connectAndSync() }
            }
            "drafts" -> {
                screen("پیش‌نویس‌های من", "کارهای ذخیره‌شده روی همین دستگاه",withNav=true)
                label("${drafted.size} پیش‌نویس آماده ادامه",18,true,navy)
                gap(10)
                if (drafted.isEmpty()) emptyState("پیش‌نویسی ندارید","هنگام پاسخ به چک‌لیست، اطلاعات به‌صورت خودکار ذخیره می‌شود.")
                else drafted.forEach { missionItem(it) }
                gap(12)
                label("برای جلوگیری از حذف اطلاعات، داده‌های برنامه را از تنظیمات گوشی پاک نکنید.",12,false,muted)
            }
            "account" -> {
                screen("حساب بازرس", "تنظیمات امنیت و ارتباط",withNav=true)
                val panel = LinearLayout(this).apply {
                    orientation=LinearLayout.VERTICAL
                    setPadding(dp(18),dp(20),dp(18),dp(20))
                    setBackground(rounded(Color.WHITE,18))
                }
                panel.addView(iconBadge(R.drawable.ic_person, 28), LinearLayout.LayoutParams(dp(48), dp(48)))
                panel.addView(text(profileName,21,true,navy).apply { setPadding(0,dp(12),0,dp(4)) })
                panel.addView(text("حساب سازمانی بازرس",13,false,muted))
                content.addView(panel)
                gap(20)
                sectionTitle("امنیت حساب")
                if (deviceToken != null && !store.biometricEnabled()) {
                    button("فعال‌سازی ورود با اثرانگشت") { enableBiometric() }
                } else if (store.biometricEnabled()) {
                    label("ورود با اثرانگشت برای این دستگاه فعال است.",14,false,teal)
                }
                sectionTitle("ارتباط با سامانه")
                secondaryButton("همگام‌سازی اطلاعات") { connectAndSync() }
                secondaryButton("خروج از حساب") { confirmLogout() }
                gap(12)
                label("نسخه ${BuildConfig.VERSION_NAME}  •  اتصال امن HTTPS",12,false,muted)
            }
            else -> showHome("home")
        }
    }

    private fun showMission(mission: JSONObject, questionIndex: Int = 0) {
        val key=missionKey(mission)
        if (key.isBlank()) { notifyShort("شناسه مأموریت نامعتبر است"); return }
        selectedMission=mission; selectedMissionKey=key
        val draft=if(openDraft?.key == key) openDraft!! else InspectionDraft(key,store)
        openDraft=draft
        val enabled=mission.optBoolean("can_execute",false)
        val questions=mission.optJSONObject("checklistSnapshot")?.optJSONArray("questions") ?: JSONArray()
        val count=questions.length()
        currentQuestionIndex=questionIndex.coerceIn(0,(count-1).coerceAtLeast(0))
        screen("ثبت بازرسی", mission.optString("orgName", "دستگاه اجرایی"))
        secondaryButton("بازگشت به مأموریت‌ها") { showHome("missions") }
        val summary=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(16),dp(14),dp(16),dp(14))
            setBackground(rounded(Color.WHITE,16))
        }
        summary.addView(text(mission.optString("orgName", "بازرسی"),16,true,navy))
        summary.addView(text(listOf(mission.optString("date"),mission.optString("schedule_label")).filter { it.isNotBlank() }.joinToString("  •  "),12,false,muted))
        content.addView(summary)
        gap(14)
        if (!enabled) label("این مأموریت فعلاً قابل ارسال نیست؛ امکان ثبت پیش‌نویس وجود دارد.",13,true,Color.rgb(157,99,29))
        if (count == 0) {
            emptyState("چک‌لیست خالی است","برای دریافت اطلاعات جدید، مأموریت‌ها را همگام‌سازی کنید.")
            secondaryButton("بازگشت") { showHome("missions") }
            return
        }
        val validAnswers = (0 until count).count { idx ->
            val q=questions.optJSONObject(idx)
            val number=draft.answers.optString(q?.optString("id") ?: "").toDoubleOrNull()
            number != null && number in 0.0..100.0
        }
        val stepTitle=label("سؤال ${currentQuestionIndex+1} از $count",15,true,navy)
        val progressLabel=label("$validAnswers از $count سؤال امتیازدهی شده",12,false,muted)
        val progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=count; this.progress=validAnswers
            progressTintList=ColorStateList.valueOf(gold)
            progressBackgroundTintList=ColorStateList.valueOf(line)
        }
        val progressParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(6))
        progressParams.bottomMargin=dp(18)
        content.addView(progress,progressParams)
        val q=questions.optJSONObject(currentQuestionIndex) ?: JSONObject()
        val id=q.optString("id")
        if (id.isBlank()) {
            label("شناسه سؤال نامعتبر است؛ اطلاعات چک‌لیست را با مدیر بررسی کنید.",14,true)
            return
        }
        val questionText=q.optString("text",q.optString("question",q.optString("title",q.optString("label",id))))
        val questionCard=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(16),dp(20),dp(16),dp(18))
            setBackground(rounded(Color.WHITE,18))
        }
        questionCard.addView(text("عنوان سؤال",12,true,teal))
        questionCard.addView(text(questionText,18,true,navy).apply { setPadding(0,dp(9),0,dp(4)) })
        content.addView(questionCard)
        gap(18)
        label("امتیاز و مستندات",16,true,navy)
        val score=field("امتیاز از ۰ تا ۱۰۰",draft.answers.optString(id),numeric=true)
        val note=field("توضیح در صورت نیاز",draft.notes.optString(id))
        fun refreshProgress() {
            val filled=(0 until count).count { idx ->
                val qid=questions.optJSONObject(idx)?.optString("id") ?: ""
                val value=draft.answers.optString(qid).toDoubleOrNull()
                value != null && value in 0.0..100.0
            }
            progress.progress=filled
            progressLabel.text="$filled از $count سؤال امتیازدهی شده"
        }
        score.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                draft.answers.put(id,s?.toString() ?: ""); draft.save(); refreshProgress()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        note.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                draft.notes.put(id,s?.toString() ?: ""); draft.save()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        secondaryButton(if(draft.attachments.optString(id).isBlank()) "افزودن عکس یا PDF" else "مستند پیوست شده · تغییر") {
            pickerQuestion=id
            evidencePicker.launch(arrayOf("image/jpeg","image/png","image/webp","application/pdf"))
        }
        gap(13)
        val nav=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        if (currentQuestionIndex > 0) {
            val prev=makeButton("سؤال قبلی",false) { showMission(mission,currentQuestionIndex-1) }
            nav.addView(prev,LinearLayout.LayoutParams(0,dp(52),1f).apply { marginEnd=dp(5) })
        }
        if (currentQuestionIndex < count-1) {
            val next=makeButton("سؤال بعدی",true) { showMission(mission,currentQuestionIndex+1) }
            nav.addView(next,LinearLayout.LayoutParams(0,dp(52),1f).apply { marginStart=dp(5) })
        }
        content.addView(nav)
        gap(15)
        secondaryButton("ذخیره پیش‌نویس") { draft.save(); notifyShort("پیش‌نویس روی دستگاه ذخیره شد") }
        label("پاسخ‌ها هنگام تایپ به‌صورت خودکار و رمزنگاری‌شده ذخیره می‌شوند.",12,false,muted)
        if (enabled && currentQuestionIndex == count-1) {
            gap(8)
            button("بررسی و ارسال نهایی") { submitDraft(mission,draft) }
            label("ارسال نهایی تنها با اتصال اینترنت انجام می‌شود.",12,false,muted)
        }
    }

    private fun submitDraft(mission: JSONObject, draft: InspectionDraft) {
        val issues = draft.validate(mission)
        if (issues.isNotEmpty()) {
            AlertDialog.Builder(this).setTitle("گزارش نیازمند تکمیل است")
                .setMessage(issues.take(8).joinToString("\n\n"))
                .setPositiveButton("متوجه شدم",null).show()
            return
        }
        val token=deviceToken ?: run { notifyShort("نشست دستگاه فعال نیست");return }
        val key=draft.key
        AlertDialog.Builder(this)
            .setTitle("ارسال نهایی")
            .setMessage("پس از تأیید، گزارش برای بررسی مدیر ارسال می‌شود. در صورت قطع ارتباط، پیش‌نویس حفظ خواهد شد.")
            .setNegativeButton("انصراف",null)
            .setPositiveButton("ارسال") { _, _ ->
                work({
                    val session = sessionFor(token) // Never submit with a stale 30-minute session.
                    val uploads=JSONArray()
                    val ids=draft.attachments.keys()
                    while(ids.hasNext()) {
                        val question=ids.next()
                        val uri=Uri.parse(draft.attachments.getString(question))
                        val uploaded=api.uploadEvidence(session,contentResolver,uri,key,question)
                        uploads.put(JSONObject().put("file_id",uploaded.getInt("file_id"))
                            .put("question_id",question))
                    }
                    val request=JSONObject().put("mission_key",key).put("answers",draft.answers)
                        .put("notes",draft.notes).put("uploads",uploads)
                        .put("started_at",draft.startedAt).put("finished_at",Instant.now().toString())
                    api.post("mobile_submit.php",request,session,UUID.randomUUID().toString())
                }) { response ->
                    if (response.optBoolean("ok")) {
                        draft.clear()
                        openDraft=null
                        AlertDialog.Builder(this).setTitle("گزارش ثبت شد")
                            .setMessage("کد رسید: ${response.optString("receipt_code", "ثبت‌شده")}")
                            .setPositiveButton("تأیید") { _, _ -> showHome() }.show()
                    }
                }
            }.show()
    }

    private fun biometricsAvailable(): Boolean = BiometricManager.from(this)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    private fun biometricPrompt(title:String, cipher:Cipher, callback:(Cipher)->Unit) {
        val prompt=BiometricPrompt(this,ContextCompat.getMainExecutor(this),object:BiometricPrompt.AuthenticationCallback(){
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                val authorized=result.cryptoObject?.cipher
                if (authorized != null) callback(authorized) else notifyShort("تأیید بیومتریک ناقص بود")
            }
            override fun onAuthenticationError(errorCode:Int, errString:CharSequence) {
                super.onAuthenticationError(errorCode,errString)
                notifyShort(errString.toString())
            }
        })
        val info=BiometricPrompt.PromptInfo.Builder()
            .setTitle(title).setSubtitle("سامانه بازرسی")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("انصراف").build()
        prompt.authenticate(info,BiometricPrompt.CryptoObject(cipher))
    }
    private fun enableBiometric() {
        val token=deviceToken ?: return
        if (!biometricsAvailable()) {notifyShort("اثر انگشت قوی در تنظیمات دستگاه فعال نشده است");return}
        try {
            biometricPrompt("فعال‌سازی ورود با اثرانگشت",store.bioEncryptCipher()) { cipher ->
                try { store.finalizeBioEncryption(cipher,token); notifyShort("اثر انگشت فعال شد");showHome() }
                catch (_:Exception) { notifyShort("ذخیره کلید بیومتریک ناموفق بود") }
            }
        }catch(_ :Exception) {notifyShort("فعالسازی اثرانگشت ممکن نشد")}
    }
    private fun showBiometricLock() {
        screen("ورود ایمن", "دسترسی با تأیید هویت دستگاه")
        label("برای مشاهده مأموریت‌ها، اثر انگشت خود را تأیید کنید.",14,false,muted)
        gap(18)
        button("ورود با اثرانگشت") {
            if(!biometricsAvailable()) {notifyShort("اثر انگشت قوی در دسترس نیست");return@button}
            try {
                val cipher=store.bioDecryptCipher() ?: error("token missing")
                biometricPrompt("ورود با اثرانگشت",cipher) { authorized ->
                    try { deviceToken=store.decryptBio(authorized);showHome();connectAndSync() }
                    catch (_:Exception) {notifyShort("کلید دستگاه نامعتبر است؛ ورود مجدد لازم است");showLogin()}
                }
            } catch (_: Exception) {notifyShort("اطلاعات بیومتریک نیازمند فعال‌سازی مجدد است");showLogin()}
        }
        secondaryButton("ورود با نام کاربری و رمز عبور") { showLogin() }
    }
    private fun confirmLogout() {
        AlertDialog.Builder(this).setTitle("خروج")
            .setMessage("از حساب خارج می‌شوید. پیش‌نویس‌های ذخیره‌شده روی دستگاه حذف نمی‌شوند. برای حذف کامل داده‌ها باید از تنظیمات اندروید اقدام کنید.")
            .setNegativeButton("انصراف",null)
            .setPositiveButton("خروج") { _,_ ->
                deviceToken=null;sessionToken=null;accountToken=null
                store.clear("device_token");store.clearBio()
                showLogin()
            }.show()
    }
}
