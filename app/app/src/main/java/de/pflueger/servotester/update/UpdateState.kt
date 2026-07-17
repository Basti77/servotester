package de.pflueger.servotester.update

/** UI state of the online-update flow (settings drawer). */
sealed interface UpdateState {
    /** Nothing checked yet. */
    data object Idle : UpdateState

    /** Querying GitHub for the latest release. */
    data object Checking : UpdateState

    /** Check or download failed — [message] is user-facing. */
    data class Error(val message: String) : UpdateState

    /** Latest release fetched; [release] is null when the repo has none yet. */
    data class Latest(val release: ReleaseInfo?) : UpdateState

    /** Downloading an asset ("Firmware"/"App"), 0..100 %. */
    data class Downloading(val label: String, val percent: Int) : UpdateState

    /** Informational note (e.g. installer launched). */
    data class Notice(val message: String) : UpdateState
}
