package app.bagtimer.engine

// Dependency-free test runner so the engine can be checked with plain kotlinc.
// Run: kotlinc Timeline.kt Cues.kt EngineTest.kt -include-runtime -d t.jar && java -cp t.jar app.bagtimer.engine.EngineTestKt

private var failures = 0
private fun check(name: String, cond: Boolean) {
    if (!cond) { failures++; println("FAIL: $name") } else println("ok:   $name")
}

fun main() {
    val ladder = Presets.defaults().first { it.id == "bag-ladder" }
    val t = Timeline(ladder)

    check("ladder round is 6:30", ladder.roundSeconds == 390)
    check("ladder total is 39:00 plus 10 s prep", ladder.totalSeconds == 39 * 60 + 10)
    check("ladder has 1 prep + 54 intervals", t.segments.size == 55)
    check("timeline total matches", t.totalMs == (39 * 60 + 10) * 1000L)

    val p0 = t.positionAt(0)
    check("starts in prep", p0.segment.kind == Kind.PREP && p0.remainingMs == 10_000L)

    val p1 = t.positionAt(10_000)
    check("first work at 10 s", p1.segment.kind == Kind.WORK && p1.segment.round == 1 && p1.remainingMs == 120_000L)

    val p2 = t.positionAt(10_000 + 120_000 + 30_000)
    check("bag after work+rest", p2.segment.kind == Kind.BAG)

    val r2 = t.positionAt(10_000 + 390_000)
    check("round 2 starts at 6:30", r2.segment.round == 2 && r2.segment.kind == Kind.WORK)

    val end = t.positionAt(t.totalMs)
    check("finished at end", end.finished && end.totalRemainingMs == 0L)

    check("skip goes to segment end", t.skipTarget(15_000) == 130_000L)
    check("back early in segment goes to previous", t.backTarget(11_000) == 0L)
    check("back late in segment restarts it", t.backTarget(20_000) == 10_000L)

    check("format 0", formatClock(0) == "0:00")
    check("format rounds up", formatClock(59_001) == "1:00")
    check("format 2:00", formatClock(120_000) == "2:00")

    // Cue tracking over a simulated run at 50 ms ticks.
    val cues = CueTracker()
    var starts = 0; var pips = 0; var finishes = 0
    var ms = 0L
    while (ms <= t.totalMs + 500) {
        for (c in cues.update(t.positionAt(ms))) when (c) {
            is Cue.Start -> starts++
            Cue.Pip -> pips++
            Cue.Finish -> finishes++
        }
        ms += 50
    }
    check("one start cue per segment (55), got $starts", starts == 55)
    check("three pips per segment (165), got $pips", pips == 165)
    check("one finish cue, got $finishes", finishes == 1)

    // Tabata 20/10 x8 = 4:00 plus prep.
    val tab = Timeline(Presets.defaults().first { it.id == "tabata" })
    check("tabata is 4:10 with prep", tab.totalMs == 250_000L)

    // EMOM fires a start every minute even though kinds repeat.
    val emom = Timeline(Workout("e", "e", 3, listOf(Step(Kind.WORK, 60)), prepSeconds = 0))
    val ec = CueTracker(); var es = 0
    var m = 0L
    while (m < emom.totalMs) { es += ec.update(emom.positionAt(m)).count { it is Cue.Start }; m += 100 }
    check("emom 3 start cues, got $es", es == 3)

    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
    if (failures != 0) kotlin.system.exitProcess(1)
}
