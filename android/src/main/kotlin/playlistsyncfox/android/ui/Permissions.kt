package playlistsyncfox.android.ui

// 実行時の権限の要求の共通の手順（X7-4-2）。

enum class PermissionStep {
    /** 許可済み。要求しない。 */
    Granted,

    /** 要求する。 */
    Request,

    /** 二度拒否で OS が要求を出さない状態。設定アプリへの案内を出す。 */
    GuideToSettings,

    /** 同じ起動の間に拒否した。再び求めない。 */
    SkipDenied,
}

/**
 * @param deniedThisLaunch 拒否の結果を端末内に覚えたもの（同じ起動の間は再び求めない）
 * @param blockedByOs 以前に要求したのに、OS が根拠の表示を勧めない状態（二度拒否）
 */
fun permissionStep(granted: Boolean, deniedThisLaunch: Boolean, blockedByOs: Boolean): PermissionStep = when {
    granted -> PermissionStep.Granted
    deniedThisLaunch -> PermissionStep.SkipDenied
    blockedByOs -> PermissionStep.GuideToSettings
    else -> PermissionStep.Request
}
