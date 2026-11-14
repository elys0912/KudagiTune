# ADR の索引

<!-- adr-index:begin -->
| 番号 | 件名 | 種類 | 重さ | 状態 | 決めた日 | 置き換え |
|---|---|---|---|---|---|---|
| [0001](0001-PC-をサーバー、端末を-pull-のクライアントにし、接続を-1-つのサーバーへの-TCP-に揃える.md) | PC をサーバー、端末を pull のクライアントにし、接続を 1 つのサーバーへの TCP に揃える | architecture | full | accepted | 2026-11-05 |  |
| [0002](0002-全体を-Kotlin-で書き、共有部分を-Kotlin-Multiplatform-の-1-モジュールに置く.md) | 全体を Kotlin で書き、共有部分を Kotlin Multiplatform の 1 モジュールに置く | architecture | full | accepted | 2026-11-05 |  |
| [0003](0003-入口を-iTunes-Music-Library.xml-にし、COM-は使わない.md) | 入口を iTunes Music Library.xml にし、COM は使わない | architecture | short | accepted | 2026-11-05 |  |
| [0004](0004-同期を-PC-から端末への一方向に絞る.md) | 同期を PC から端末への一方向に絞る | spec-change | short | accepted | 2026-11-05 |  |
| [0005](0005-ペアリングは-SPAKE2-で行い、通信は自己署名-TLS-の公開鍵を固定する.md) | ペアリングは SPAKE2 で行い、通信は自己署名 TLS の公開鍵を固定する | architecture | full | accepted | 2026-11-05 |  |
| [0006](0006-PC-の鍵を-DPAPI-で暗号化したファイルに保管する.md) | PC の鍵を DPAPI で暗号化したファイルに保管する | architecture | short | accepted | 2026-11-05 |  |
| [0007](0007-配布は-GitHub-Releases-の-APK-と-jpackage-の-zip-にし、アプリ内の更新確認はしない.md) | 配布は GitHub Releases の APK と jpackage の zip にし、アプリ内の更新確認はしない | architecture | full | superseded | 2026-11-05 | 0016 に置き換え |
| [0008](0008-自動の同期は従量制でないネットワークで周期に起動し、SSID-は見ない.md) | 自動の同期は従量制でないネットワークで周期に起動し、SSID は見ない | spec-change | short | accepted | 2026-11-05 |  |
| [0009](0009-端末の曲は-Music-配下に-MediaStore-経由で置き、m3u8-を-MediaStore.Files-に登録する.md) | 端末の曲は Music 配下に MediaStore 経由で置き、m3u8 を MediaStore.Files に登録する | architecture | short | accepted | 2026-11-05 |  |
| [0010](0010-端末から曲を消すのは自分が置いた曲だけにし、消す動きは利用者の設定と確認に任せる.md) | 端末から曲を消すのは自分が置いた曲だけにし、消す動きは利用者の設定と確認に任せる | design-fix | full | accepted | 2026-11-07 |  |
| [0011](0011-同期するプレイリストの選択を端末へ移し、PC-の画面を最小にする.md) | 同期するプレイリストの選択を端末へ移し、PC の画面を最小にする | spec-change | short | accepted | 2026-11-07 |  |
| [0012](0012-最初の版は端末-1-台と-PC-1-台で確定する.md) | 最初の版は端末 1 台と PC 1 台で確定する | spec-change | short | accepted | 2026-11-09 |  |
| [0013](0013-再生できるかの判定をやめ、保護付き以外は形式を問わず転送する.md) | 再生できるかの判定をやめ、保護付き以外は形式を問わず転送する | design-fix | short | accepted | 2026-11-09 |  |
| [0014](0014-Bluetooth-の経路を足す.md) | Bluetooth の経路を足す | feature | short | accepted | 2026-11-11 |  |
| [0015](0015-main-への-push-の-CI-は、PR-で検査済みのコミットでは流さない.md) | main への push の CI は、PR で検査済みのコミットでは流さない | cost | short | accepted | 2026-11-14 |  |
| [0016](0016-PC-版を全ユーザー向けの-MSI-で配り、zip-も添える.md) | PC 版を全ユーザー向けの MSI で配り、zip も添える | architecture | full | accepted | 2026-11-14 | 0007 を置き換え |
| [0017](0017-APK-はリリース用の鍵で手元で署名して-Release-に添付し、鍵のバックアップを-AWS-の-Parameter-Store-に置く.md) | APK はリリース用の鍵で手元で署名して Release に添付し、鍵のバックアップを AWS の Parameter Store に置く | architecture | full | accepted | 2026-11-14 |  |
<!-- adr-index:end -->
