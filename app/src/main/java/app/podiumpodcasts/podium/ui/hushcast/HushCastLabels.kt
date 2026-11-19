package app.podiumpodcasts.podium.ui.hushcast

import app.hushcast.automute.dsp.MuteActionMode

/**
 * One name per mute mode, used by Settings, the player's HushCast sheet and episode rows, so the
 * same mode never has three different names.  BEEP and CUSTOM_SOUND are both shown as "Sound":
 * which sound plays is picked from one "Mute sound" list (built-in tones, then custom sounds).
 */
object HushCastLabels {

    /** The choices shown to users, in order. */
    val userModes: List<MuteActionMode> = listOf(
        MuteActionMode.HARD_SILENCE,
        MuteActionMode.VOLUME_DUCK,
        MuteActionMode.BEEP,
        MuteActionMode.SAMPLE_DROP_MICRO_SKIP
    )

    fun modeLabel(mode: MuteActionMode): String = when (mode) {
        MuteActionMode.HARD_SILENCE -> "Silence"
        MuteActionMode.VOLUME_DUCK -> "Duck"
        MuteActionMode.SAMPLE_DROP_MICRO_SKIP -> "Skip"
        MuteActionMode.BEEP, MuteActionMode.CUSTOM_SOUND -> "Sound"
    }

    fun modeDescription(mode: MuteActionMode): String = when (mode) {
        MuteActionMode.HARD_SILENCE -> "The word is replaced by silence"
        MuteActionMode.VOLUME_DUCK -> "The word plays at 10% volume"
        MuteActionMode.SAMPLE_DROP_MICRO_SKIP -> "The word is cut out of the audio"
        MuteActionMode.BEEP, MuteActionMode.CUSTOM_SOUND -> "The word is covered by your mute sound"
    }

    /** True when [selected] is shown as the same choice as [mode] ("Sound" covers both sound modes). */
    fun sameUserChoice(mode: MuteActionMode, selected: MuteActionMode): Boolean =
        mode == selected || (isSound(mode) && isSound(selected))

    fun isSound(mode: MuteActionMode): Boolean =
        mode == MuteActionMode.BEEP || mode == MuteActionMode.CUSTOM_SOUND

    /**
     * The mode to save when the user picks [mode] from [userModes].  "Sound" means the chosen
     * custom sound when one is selected ([customSoundUri] set), otherwise the built-in tone.
     * Picking a built-in tone clears [customSoundUri], so it is only set while a custom sound is
     * the selection.
     */
    fun resolveUserChoice(mode: MuteActionMode, customSoundUri: String?): MuteActionMode = when {
        !isSound(mode) -> mode
        !customSoundUri.isNullOrBlank() -> MuteActionMode.CUSTOM_SOUND
        else -> MuteActionMode.BEEP
    }
}
