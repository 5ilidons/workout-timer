package app.bagtimer

import android.content.Context
import app.bagtimer.engine.Kind
import app.bagtimer.engine.Presets
import app.bagtimer.engine.Step
import app.bagtimer.engine.Workout
import org.json.JSONArray
import org.json.JSONObject

/** Saves workouts on the device as JSON. No account, no network. */
class PresetStore(context: Context) {
    private val prefs = context.getSharedPreferences("presets", Context.MODE_PRIVATE)

    fun load(): MutableList<Workout> {
        val raw = prefs.getString("workouts", null)
        if (raw == null) {
            val d = Presets.defaults()
            save(d)
            return d.toMutableList()
        }
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Presets.defaults().toMutableList()
        }
    }

    fun save(list: List<Workout>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs.edit().putString("workouts", arr.toString()).apply()
    }

    fun upsert(w: Workout) {
        val list = load()
        val i = list.indexOfFirst { it.id == w.id }
        if (i >= 0) list[i] = w else list += w
        save(list)
    }

    fun delete(id: String) = save(load().filterNot { it.id == id })

    fun restoreDefaults() {
        val list = load()
        for (d in Presets.defaults()) if (list.none { it.id == d.id }) list += d
        save(list)
    }

    var lastUsedId: String?
        get() = prefs.getString("last", null)
        set(v) { prefs.edit().putString("last", v).apply() }

    private fun toJson(w: Workout) = JSONObject().apply {
        put("id", w.id)
        put("name", w.name)
        put("rounds", w.rounds)
        put("prep", w.prepSeconds)
        put("steps", JSONArray().apply {
            w.steps.forEach { s -> put(JSONObject().put("k", s.kind.name).put("s", s.seconds)) }
        })
    }

    private fun fromJson(o: JSONObject): Workout {
        val steps = o.getJSONArray("steps")
        return Workout(
            id = o.getString("id"),
            name = o.getString("name"),
            rounds = o.getInt("rounds"),
            prepSeconds = o.optInt("prep", 10),
            steps = List(steps.length()) {
                val s = steps.getJSONObject(it)
                Step(Kind.valueOf(s.getString("k")), s.getInt("s"))
            },
        )
    }
}
