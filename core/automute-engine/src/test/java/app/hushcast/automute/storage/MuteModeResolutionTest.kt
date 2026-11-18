package app.hushcast.automute.storage
import app.hushcast.automute.dsp.MuteActionMode
import app.hushcast.automute.matcher.MuteRule
import app.hushcast.automute.engine.Word
import app.hushcast.automute.matcher.WordMatcher
import org.junit.Assert.assertEquals
import org.junit.Test

class MuteModeResolutionTest {

    @Test
    fun `changing defaultActionMode updates default-rule windows only and preserves custom rule mode`() {
        val rules = listOf(
            MuteRule(id = 1L, pattern = "uh", actionMode = MuteActionMode.HARD_SILENCE, useDefaultMode = true),
            MuteRule(id = 2L, pattern = "beep", actionMode = MuteActionMode.BEEP, useDefaultMode = false)
        )
        val words = listOf(
            Word(1.0, 1.3, " uh"),
            Word(2.0, 2.4, " beep")
        )

        val windowsInitial = WordMatcher.matchWords(
            words = words,
            rules = rules,
            defaultActionMode = MuteActionMode.HARD_SILENCE
        )
        val entities = windowsInitial.map { w ->
            val rule = rules.firstOrNull { it.id == w.ruleId }
            MuteSegmentEntity(
                fileSha256 = "sha1",
                episodeId = "ep1",
                startMs = w.startMs,
                endMs = w.endMs,
                word = w.word,
                ruleId = w.ruleId,
                actionMode = if (rule?.useDefaultMode == true) "DEFAULT" else w.actionMode.name,
                enabled = w.enabled
            )
        }
        val rulesById: Map<Long, MuteRuleEntity> = rules.associate { r ->
            r.id to MuteRuleEntity(
                id = r.id,
                pattern = r.pattern,
                actionMode = if (r.useDefaultMode) "DEFAULT" else r.actionMode.name
            )
        }

        // Resolve with default mode changed to VOLUME_DUCK (simulating reload)
        val newDefault = MuteActionMode.VOLUME_DUCK
        val resolvedAfterReload = entities.map { seg ->
            val rule = rulesById[seg.ruleId]
            val useDefault = seg.actionMode.equals("DEFAULT", ignoreCase = true) ||
                (rule != null && rule.actionMode.equals("DEFAULT", ignoreCase = true))
            val mode = when {
                useDefault -> newDefault
                rule != null -> MuteActionMode.valueOf(rule.actionMode)
                else -> MuteActionMode.valueOf(seg.actionMode)
            }
            seg.toDomain(newDefault).copy(actionMode = mode)
        }

        assertEquals(MuteActionMode.VOLUME_DUCK, resolvedAfterReload[0].actionMode)
        assertEquals(MuteActionMode.BEEP, resolvedAfterReload[1].actionMode)
    }
}

