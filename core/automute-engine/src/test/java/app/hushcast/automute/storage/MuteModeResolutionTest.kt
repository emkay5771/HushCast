package app.hushcast.automute.storage

import app.hushcast.automute.dsp.MuteActionMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercises [resolveMuteWindows], the resolver that every window loader uses. */
class MuteModeResolutionTest {

    private fun segment(ruleId: Long, start: Long, storedMode: String) = MuteSegmentEntity(
        fileSha256 = "sha1",
        episodeId = "ep1",
        startMs = start,
        endMs = start + 300,
        word = "w$ruleId",
        ruleId = ruleId,
        actionMode = storedMode,
        enabled = true
    )

    @Test
    fun `rules created with the default mode follow the global mode after it changes`() {
        // New rules are saved as DEFAULT (MuteRuleEntity's default), as Settings and the player now do.
        val rules = listOf(MuteRuleEntity(id = 1L, pattern = "uh"), MuteRuleEntity(id = 2L, pattern = "darn"))
        assertEquals(DEFAULT_RULE_MODE, rules[0].actionMode)
        // Segments written before the change still carry a concrete mode; the rule decides.
        val segments = listOf(segment(1L, 1_000, "HARD_SILENCE"), segment(2L, 2_000, "DEFAULT"))

        val beep = resolveMuteWindows(segments, rules.associateBy { it.id }, MuteActionMode.BEEP)
        assertEquals(listOf(MuteActionMode.BEEP, MuteActionMode.BEEP), beep.map { it.actionMode })

        val duck = resolveMuteWindows(segments, rules.associateBy { it.id }, MuteActionMode.VOLUME_DUCK)
        assertEquals(listOf(MuteActionMode.VOLUME_DUCK, MuteActionMode.VOLUME_DUCK), duck.map { it.actionMode })
    }

    @Test
    fun `a rule with its own mode keeps it`() {
        val rules = listOf(
            MuteRuleEntity(id = 1L, pattern = "uh"),
            MuteRuleEntity(id = 2L, pattern = "beep", actionMode = MuteActionMode.BEEP.name)
        )
        val segments = listOf(segment(1L, 1_000, "HARD_SILENCE"), segment(2L, 2_000, "BEEP"))
        val resolved = resolveMuteWindows(segments, rules.associateBy { it.id }, MuteActionMode.VOLUME_DUCK)
        assertEquals(MuteActionMode.VOLUME_DUCK, resolved[0].actionMode)
        assertEquals(MuteActionMode.BEEP, resolved[1].actionMode)
    }

    @Test
    fun `a segment whose rule was deleted falls back to its stored mode`() {
        val resolved = resolveMuteWindows(listOf(segment(9L, 1_000, "BEEP")), emptyMap(), MuteActionMode.HARD_SILENCE)
        assertEquals(MuteActionMode.BEEP, resolved.single().actionMode)
    }
}
