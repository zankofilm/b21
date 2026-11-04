package ir.bazr.inspection

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Persist only draft edits, not server-issued receipts or authority. */
class InspectionDraft(val key: String, private val store: SecureStore) {
    var answers: JSONObject = JSONObject()
    var notes: JSONObject = JSONObject()
    var attachments: JSONObject = JSONObject() // question -> persisted content Uri
    var startedAt: String = Instant.now().toString()
    init {
        store.load("draft:$key")?.let { encoded ->
            try {
                val j = JSONObject(encoded)
                answers = j.optJSONObject("answers") ?: JSONObject()
                notes = j.optJSONObject("notes") ?: JSONObject()
                attachments = j.optJSONObject("attachments") ?: JSONObject()
                startedAt = j.optString("started_at", startedAt)
            } catch (_: Exception) { /* prefer a fresh draft to an invalid one */ }
        }
    }
    fun save() {
        val j = JSONObject().put("answers", answers).put("notes", notes)
            .put("attachments", attachments).put("started_at", startedAt)
        store.save("draft:$key", j.toString())
    }
    fun clear() = store.clear("draft:$key")
    fun validate(mission: JSONObject): List<String> {
        val issues= mutableListOf<String>()
        val questions = mission.optJSONObject("checklistSnapshot")?.optJSONArray("questions") ?: JSONArray()
        for (i in 0 until questions.length()) {
            val q = questions.optJSONObject(i) ?: continue
            val id=q.optString("id")
            if (id.isBlank()) continue
            val score = answers.optString(id)
            val number = score.toDoubleOrNull()
            if (number == null || number < 0.0 || number > 100.0) {
                issues.add("${q.optString("text", id)}: امتیاز معتبر ۰ تا ۱۰۰ لازم است")
                continue
            }
            if (number < 60 && notes.optString(id).isBlank()) issues.add("$id: برای امتیاز کمتر از ۶۰ توضیح لازم است")
            if ((number < 30 || q.optBoolean("critical")) && attachments.optString(id).isBlank()) {
                issues.add("$id: مستند برای پاسخ بحرانی لازم است")
            }
        }
        return issues
    }
}
