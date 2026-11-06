package playlistsyncfox.desktop.settings

/** W5 の入力の検査（X5-5）。サーバーのポートは 1024〜65535。 */
fun isValidPort(port: Int): Boolean = port in 1024..65535

/** 古いと警告する日数は 0 以上。0 は警告しない。 */
fun isValidStaleDays(days: Int): Boolean = days >= 0
