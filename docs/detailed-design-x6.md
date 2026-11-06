# playlist-sync-fox 詳細設計 X6 android の保存と同期（M4）

- 索引：[detailed-design.md](detailed-design.md)（X1）
- 稿：第 3 稿（2026-11-06。実物の確認（B2）と土台を反映）
- 参照：他の X は [x2](detailed-design-x2.md)、[x3](detailed-design-x3.md)、[x4](detailed-design-x4.md)、[x5](detailed-design-x5.md)、[x7](detailed-design-x7.md)。基本設計は [basic-design.md](basic-design.md)（B1-1、B1-3、B1-7）、[basic-design-b2.md](basic-design-b2.md)（B2-6、B2-7）、[basic-design-b3.md](basic-design-b3.md)（B3-2〜B3-5）、[basic-design-b4a.md](basic-design-b4a.md)（B4-3 の K3・K4、B4-4 の端末側）

## X6 android の保存と同期

### X6 の書く項目

- X6-1 Room の定義 T1〜T7
- X6-2 MediaStore の書き込み
- X6-3 同期の実行（ワーカーの手順）
- X6-4 WorkManager の登録
- X6-5 HTTP クライアント
- X6-6 鍵の保管 K3・K4
- X6-7 テストの観点

### X6 の読み方

- M4 の中の置き場は X1-3 のパッケージの構成に従う。この節は M4 の「保存と同期」の部分（データベース、ストレージ、ワーカー、通信、鍵）だけを書く
- 判断（差分、パスの計算、m3u8 の生成、署名対象の組み立て）は M1。この節は M1 の関数を呼ぶ順と、OS の API を呼ぶ手順だけを書く（B1-3）。M1 の関数の名前は X3、X4 に従う
- 読み替え：B2-6 の「一時ファイル」は、端末では MediaStore の `IS_PENDING`＝1 の行を指す（B3-2）。「本来の場所へ移す」は `IS_PENDING`＝0 への更新を指す
- 値は V の番号で参照する。この節で新しく要る値は「X6 の置いた値」に置く

### X6-1 Room の定義 T1〜T7

#### 全体の約束

| 項目 | 決め方 |
|---|---|
| データベース | 1 つ。M4 の内部領域。ファイル名と版は実装で決める（版の初期値は 1） |
| 型の対応 | text は文字列、integer は 64 ビット整数、真偽は使わない（text か integer で持つ）。日時は UTC のエポックのミリ秒（B3-4） |
| NULL | 表の「必須」が「いいえ」の列だけ NULL を許す。それ以外は NOT NULL |
| 列挙の text | `state`、`result`、`trigger`、`kind` は B3-4 の語を文字列のまま持つ。未知の語を読んだら、その行を「不明」として扱い、書き換えない（新しい版で足した語を古い版が壊さないため） |
| 外部キー | T6 の `run_id` から T3 へ。T3 の行を消すとき T6 も消える（V26 の件数を超えた分の削除で、同じ取引の中で消す）。T1、T2 は T4 へ張らない（ペアリングの解除後も残す。B3-4） |
| 取引 | 1 曲の完了（T1 の追加と T5 の削除）は 1 つの取引にする。T2 の更新も同じ |
| スキーマの書き出し | Room のスキーマの JSON を repo に置く（移行の試験の入力にする） |

#### 移行の方針

1. 版を上げるときは、手書きの移行（`Migration`）を必ず書く。破壊的な再作成（表を消して作り直す）の指定は使わない。根拠：T1 が消えると「自分が置いた」の記録が失われ、D14 と同じ状態になる
2. 足すものは列の追加を第一にする。既存の列の型の変更と削除は、新しい表を作って写す手順にする
3. 移行の試験は M4 の薄い統合（B1-3）。旧版のスキーマの JSON から新版へ移し、T1 と T5 の行が残ることを確かめる
4. `allowBackup` は無効（B3-2）。データベースはアプリと一緒に消える前提で、バックアップからの復元の移行は考えない

#### 表ごとの定義

T1 `placed_tracks`：

| 列 | 型 | 必須 | 意味 |
|---|---|---|---|
| `device_id` | text | はい | 主キーの 1 つ |
| `persistent_id` | text | はい | 主キーの 1 つ |
| `relative_path` | text | はい | 読み戻した値（`Music/<アプリ名>/` を含まない。V22）。（`device_id`、`relative_path`）が一意。M1 の `SyncRecord` の `placedPath`（X3-2）に対応する |
| `media_uri` | text | はい | content URI |
| `source_size` | integer | はい | 置いた時点のマニフェストの `size` |
| `source_modified` | integer | はい | 置いた時点の `modified_at`（ミリ秒に直した値。形は X3） |
| `local_size` | integer | はい | 読み戻した `SIZE` |
| `state` | text | はい | `placed`、`replacing` |
| `placed_at` | integer | はい | 置いた日時 |

T2 `playlists`：

| 列 | 型 | 必須 | 意味 |
|---|---|---|---|
| `device_id`、`playlist_id` | text | はい | 主キー。（`device_id`、`relative_path`）が一意 |
| `name` | text | はい | 元の名前 |
| `relative_path` | text | はい | m3u8 の相対パス |
| `media_uri` | text | はい | m3u8 の content URI |
| `track_count` | integer | はい | 書いた曲数 |
| `written_at` | integer | はい | 最後に書いた日時 |

T3 `sync_runs`（索引：`started_at`）：

| 列 | 型 | 必須 | 意味 |
|---|---|---|---|
| `run_id` | integer | はい | 主キー、自動採番 |
| `device_id` | text | はい | 端末の識別子 |
| `trigger` | text | はい | `manual`、`auto` |
| `started_at` | integer | はい | 開始 |
| `ended_at` | integer | いいえ | 実行中と強制終了の跡は NULL |
| `result` | text | はい | `success`、`partial`、`interrupted`、`failed`、`not_started`。実行中の行は `failed` で作り、終わりで更新する |
| `error_kind` | text | いいえ | E の番号 |
| `added`、`replaced`、`removed`、`failed` | integer | はい | 件数。初期値 0 |
| `bytes_transferred` | integer | はい | 初期値 0 |
| `reported` | integer | はい | P4 で報告済みなら 1、未報告は 0（初期値 0）。再送の対象の判定に使う（X6-3 の 11） |

T4 `pairing`：列は B3-4 のとおり。必須は `endpoint_lan`、`endpoint_tailscale`、`endpoint_usb` だけ「いいえ」。主キー `device_id`。

T5 `pending_transfers`：列は B3-4 のとおり。すべて必須。主キー `persistent_id`。

T6 `run_issues`（索引：`run_id`）：列は B3-4 のとおり。`error_kind` だけ「いいえ」。主キー（`run_id`、`persistent_id`）。

T7 `app_settings`：主キー `key`、`value` は必須。既知の `key` は `auto_sync_enabled`（`true`／`false`）、`log_enabled`（同）。値が無いときの既定は実装で決める（`auto_sync_enabled` は `false`）。

#### アクセスの単位

| 名前 | 入力 | 出力 | 副作用 |
|---|---|---|---|
| 置いた曲の取得 | `device_id` | T1 の全行 | なし。M1 の差分の入力（「同期状態」）に変換する |
| 曲の完了の記録 | T1 の行、`persistent_id` | なし | T1 を追加または更新し、T5 の同じ行を消す（1 取引） |
| 曲の削除の記録 | `persistent_id` | なし | T1 の行を消す |
| 取得の途中の保存 | T5 の行 | なし | T5 を追加または更新 |
| 期限切れの途中の回収 | 現在時刻、V25 | 回収した URI の一覧 | T5 の期限切れの行を消す。URI の削除は X6-2 が行う |
| 実行の開始、終了 | T3 の行 | `run_id` | T3 を追加、更新。終了時に V26 の超過分を T6 とともに消す |

### X6-2 MediaStore の書き込み

呼び出しは `ContentResolver` 経由。操作は 1 つのクラス（名前は `MediaWriter`）に集める。入口は次の表。

| 操作 | 入力 | 出力 | 副作用 |
|---|---|---|---|
| 曲を新規に書く | 相対パス（M1 の結果）、MIME、`size`、`modified_at`、取得元の入力 | 読み戻した値（`DISPLAY_NAME`、`RELATIVE_PATH`、`SIZE`、`DATE_MODIFIED`、URI）またはエラー（E3、E9） | pending 行の作成、T5 の更新、完了で T1 の更新 |
| 曲を置き換える | T1 の行、取得元の入力 | 同上 | T1 の `state` を `replacing`、同じ URI を `"wt"` で書き直す |
| 曲を消す | T1 の行 | 成功、または「既に無い」 | `ContentResolver.delete`。T1 の行を消す |
| m3u8 を書く | 名前、本文（M1 の結果） | URI | `MediaStore.Files` に insert（下の「m3u8」）、または既存の URI を `"wt"` |
| 以前の曲を一覧する | `Music/<アプリ名>/` の下を問い合わせる | T1 に無い曲の一覧（URI、相対パス、サイズ） | なし。読み取りの権限が要る（B3-2、J15） |

#### 曲を新規に書く手順

1. 保存先の相対パスは、X3-3 の関数（M1）が返した値を使う。T1 と `MediaStore.Audio.Media` の問い合わせの両方で、同じ相対パスの別 ID を確かめ、衝突があれば関数に「衝突の集合」を渡して接尾辞を足させる（B3-1 の 4）
2. 空き容量は、同期の前に一括で確かめ済み（X6-3 の 5）。ここでは書き込み中の容量不足（`IOException` のうち空きの不足）を E3 として扱う
3. `MediaStore.Audio.Media` に insert する。`DISPLAY_NAME`、`MIME_TYPE`（拡張子の表から決める。表は X3 が持つ）、`RELATIVE_PATH`（`Music/<V22>/<アーティスト>/<アルバム>/`）、`IS_PENDING`＝1。行が作れなければ E9
4. 返った URI と、`expected_size`、`source_modified`、`started_at`、`received_bytes`＝0 を T5 に保存する
5. 出力ストリームを開く。再開のときは追記の書き込みモード（`"wa"`）で開き、開けなければ「取り直し」（`"wt"` で `received_bytes`＝0 から）にする。`"wa"` が使えるかは、公式文書に記載が無く、コード上は拒否する箇所を見つけていない。実機で確かめる（未確認。C-12）
6. 入力を、V27 の長さの区切りで読んで書く。1 区切りごとにフラッシュし、`received_bytes` を T5 に更新する。区切りの間に、取り消しの要求（X6-3 の 6）と容量不足を確かめる
7. 書き終えたら、書いた量を `expected_size` と比べる。違えば行を削除して T5 の行を消し、E9（取得元の切断で足りないときは E6）
8. `IS_PENDING`＝0 に更新し、`DISPLAY_NAME`、`RELATIVE_PATH`、`SIZE`、`DATE_MODIFIED` を読み戻す
9. 読み戻した値で T1 を書き、T5 を消す（1 取引。「曲の完了の記録」）。T1 の `relative_path` は、読み戻した `RELATIVE_PATH` から `Music/<V22>/` を除いた後に `DISPLAY_NAME` を足した値

- 読み戻した `RELATIVE_PATH` が `Music/<V22>/` で始まらない（MediaStore が場所を変えた）ときは、その曲を E9 として扱い、行を削除する。根拠：`RELATIVE_PATH` はヒントにすぎない（B3-2）。m3u8 の基準が崩れるため

#### 再開と回収

1. 同期の始めに、T5 の期限切れ（V25）の行を回収する。pending の行を削除し、T5 を消す
2. T5 に行があり、`source_modified` が今のマニフェストと同じなら、その URI を開き直して続ける（手順 5 から）。違えば、pending の行を削除して T5 を消し、新規に書く
3. T5 に行があるが、その URI が MediaStore に無い（利用者が消した、OS が掃除した）ときは、T5 を消して新規に書く
4. 置き換えの途中（T1 の `state`＝`replacing`）は、続きから再開せず、最初から書き直す。根拠：`"wt"` で切り詰めており、途中の位置が使えない。T5 には置き換えの行を作らない

#### 削除と整理

- 自分が作った行の削除に同意の画面が要らないことは、文書に明記が無い。他のアプリが所有するファイルの削除で `RecoverableSecurityException` を捕まえる必要がある、という書き方からの読み取りである（確かめた事実の 4）。実機で確かめる
- 消す対象は T1 の曲だけ。`ContentResolver.delete` を呼び、成功か、行が無い（既に消えている）ときに T1 の行を消す。権限の例外（`SecurityException`）は、その曲だけ E9 にして T1 を残す
- 空のフォルダの後始末はしない（B3-2）
- 入れ直し後の整理は、「以前の曲を一覧する」の結果を X7 が表示し、選ばれたものを Activity が `createDeleteRequest` で消す。ワーカーからは呼ばない（B3-2）。ワーカーは整理の対象を作らない
- 入れ直し後の初回の同期で、MediaStore が名前を変えた（`(1)` など）場合も、読み戻した値を T1 に保存する（手順 8）ので整合する

#### m3u8

1. 本文は X3-4 の関数が返す。入力は、プレイリストの順の `persistent_id` と、T1 から引いた `relative_path`（T1 に無い曲は除く）
2. m3u8 は `MediaStore.Files` に書く（J18）。`MediaStore.Audio.Media` は playlist の MIME を insert できず `IllegalArgumentException` になるため使わない。`Files` では MIME が playlist 種別のとき `Music/` が許可される
3. 新規は `MediaStore.Files` に insert する。値は次のとおり。行が作れなければ、そのプレイリストを E9 にして続ける
   - `DISPLAY_NAME`＝`<名前>.m3u8`（拡張子は m3u8 のまま）
   - `MIME_TYPE`＝`audio/x-mpegurl`
   - `RELATIVE_PATH`＝`Music/<V22>/`
   - `IS_PENDING`＝1 で書き、書き終えて 0 に更新する
4. 既存は T2 の URI を開いて `"wt"` で書く。同名の insert は上書きにならず `名前 (1).m3u8` の形の別名になる（確かめた事実の 3-5）。そのため新規の insert の前に T2 で既存を確かめ、あれば既存を書き直す。読み戻した `DISPLAY_NAME` と `RELATIVE_PATH` を T2 に保存する
5. 書き終えたら索引の更新を促す（`IS_PENDING` の切り替え、または `MediaScannerConnection.scanFile`。どちらで足りるかは未確認。C-2 の「`Files` への insert と索引の実機確認」で確かめる）
6. T2 の `track_count`、`written_at` を更新する
7. マニフェストに無い T2 の行は、m3u8 を削除して T2 の行を消す

### X6-3 同期の実行

#### 実行の入口

| 項目 | 内容 |
|---|---|
| 担い手 | `SyncWorker`（`CoroutineWorker`）。1 つの実装を手動と自動で使う |
| 入力データ | `trigger`（`manual`／`auto`）、`delete_approved`（真偽。手動で確認を済ませたとき真）、`approved_delete_count`（承認した「消す」の件数） |
| 前面への昇格 | 実行の最初に `setForeground`（種別 `dataSync`）を呼ぶ。通知の内容は B3-5 の表。更新は V28 の間隔以上 |
| 結果 | `Result.success`（中断、一部失敗を含む。結果は T3 に持つ）。再試行は WorkManager に求めない（B3-5 の 6 は到達の失敗だけ） |

#### 手順

1. T3 に行を追加する（`ended_at` は NULL）。ただし自動で、手順 2 で何もせず終わる場合は追加しない（J10）。そのため、自動は手順 2 の後で追加する
2. 到達確認：保存した接続先を順に P3 で試す（B2-7、V14、V29）。届かなければ、手動は E1 で終える。自動は診断のログに 1 行書いて終える（T3 に残さない）。応えた接続先を先頭にして T4 の `endpoint_*` を更新する
3. 自動の抑止の確認（B2-7）：P3 の `content_digest` が前回の完了した同期と同じで、前回が `success` なら終える。T3 の最新の行が E2 で、その `ended_at` が T4 の `paired_at` 以後なら、自動は終える。最新が E3 なら、自動は終える（手動の成功で T3 が更新される）
4. P1 でマニフェストを取り、M1 の差分を計算する（足す、置き換える、消す。入力は T1 の全行）。`library_id` が違うときの扱いは M1 の結果に従う
5. 容量の確認：「足す」「置き換える」の `size` の合計に V11 を足し、共有ストレージのボリュームの UUID で `StorageManager.getAllocatableBytes` を worker スレッドで呼んで比べる（数秒かかることがある）。呼べなければ `StatFs` の空きで比べる。足りなければ E3、`not_started` で終える。不足分を T3 と通知へ渡す。通知と S3 には `ACTION_MANAGE_STORAGE` で利用者に空けてもらう案内を出す（X7 への依頼）
6. 「消す」の扱い（J9、J21（a））：自動は「消す」を実行せず飛ばす。手動は次の手順で進める
   1. 画面側（前面。X7-1-2）が、ワーカーを起動する前に P1 と差分の計算までを行い、「消す」の件数を確認の画面に出す
   2. 承認されたら、`delete_approved`＝真と `approved_delete_count` を付けてワーカーを起動する。承認がなければ、「消す」を飛ばす
   3. ワーカーは手順 4 で P1 を取り直して差分を再計算する。「消す」の件数が `approved_delete_count` を超えるなら、「消す」を飛ばす
   4. 件数が V20 を超える、または `library_id` が違うのに `delete_approved` が偽なら、「消す」を飛ばす
7. 「足す」と「置き換える」を 1 曲ずつ直列に行う（B2-6）。1 曲の手順：
   1. 取得の要求を P2 で送る。再開のときは `Range: bytes=<T5.received_bytes>-` と、保存した `size`、`modified_at` を付ける（X6-5）
   2. 応答の `X-Psfox-Size`、`X-Psfox-Modified-At` が T5 と同じことを確かめる。違えば E8
   3. X6-2 の書き込みに本文を渡す
   4. 失敗の扱い：E5、E8、E9 は曲単位。T6 に `failed` を書き、次の曲へ進む。E6（切断）は V10 の回数まで同じ曲を再開し、超えたら同期全体を止める。E3 は同期全体を止める。E8 の曲は、終わりで 1 回だけマニフェストを取り直して差分を取り直す（B2-6）
8. m3u8：選択されたプレイリストごとに、X6-2 の「m3u8」を行う。中断で終わるときも書く（B2-6）
9. 「消す」：同期全体の失敗（E1、E2、E3、E4、E6、E7）がなく、手順 6 で許されているときだけ、T1 の曲を X6-2 で消す
10. T3 を更新する（`ended_at`、`result`、`error_kind`、件数、`bytes_transferred`）。T6 に `excluded`（マニフェストの除外）、`unplayable`（保護付きの印と端末が判定したもの）、`failed` を書く
11. P4 で要約を報告する。失敗しても結果に影響しない。報告できたら T3 の `reported` を 1 にする。報告できなかった実行（`reported`＝0）は、次回の同期の最初に 1 度だけ再送する（B4-4 の 5）
12. 終了の通知を出す（結果と件数。タップで S4。自動は静かな通知）

#### 中断と時間の上限

| きっかけ | 検出 | 動作 |
|---|---|---|
| 時間の上限（E7） | OS の時間切れの通知（WorkManager の停止の理由で受ける。受け方は未確認） | 現在の曲の書き込みを止める。pending の行と T5 を残す。T3 を `interrupted`、`error_kind`＝E7 にする。数秒以内に戻る（V27 の区切りで確かめる） |
| 利用者の中断 | 通知の「中断」、S3 の操作。ワーカーの取り消し | 現在の曲を同じく止め、T3 を `interrupted`（`error_kind` は空） |
| プロセスの強制終了 | 検出できない | T3 の行は `ended_at`＝NULL のまま。次の同期の始めに、`ended_at` が NULL の古い行を `interrupted` で閉じる（`error_kind` は空） |

- 取り消しは協調的に受ける。書き込みの区切り（V27）ごとに、コルーチンの取り消しを確かめる。受けたら、その区切りの書き込みと T5 の更新を終えてから止める
- 続きは次の同期の差分の取り直しだけで進む。専用の「続き」を作らない（B3-5 の 4）。T3 の `trigger` に `continue` は持たない（基本設計の直し 5）
- 手動の同期は前面にアプリがあるので計時が戻る（B3-5 の 5）

#### 自動の前面への昇格の失敗

1. `setForeground` が起動の制限で拒否されたら、診断のログに 1 行書き、`Result.success` で終える。T3 には残さない（B3-5、J10）
2. 次の自動の起動は、周期（V15）に任せる

### X6-4 WorkManager の登録

| 項目 | 自動 | 手動 |
|---|---|---|
| 種類 | 周期の仕事（間隔は V15）。起動の瞬間は WorkManager に任せ、周期の中の条件が満たされた時に走る | 1 回の仕事（直ちに開始） |
| 一意な名前 | `auto-sync` | `manual-sync` |
| 制約 | 従量制でないネットワーク（`UNMETERED`）。SSID は見ない（D13） | なし（接続の可否は P3 の結果で決める。従量制でも手動は通す） |
| 登録の契機 | S5 で自動起動を有効にしたとき。無効にしたとき解除 | S1 の同期ボタン |
| 重複の規則 | 同じ名前は 1 つだけ。既にあれば残す（`KEEP` 相当） | 同じ名前が進行中なら新しく始めない |
| 再起動後 | WorkManager が登録を保つ（アプリ側の受信は要らない。未確認） | 該当しない |

- 周期の間隔の最小は WorkManager の下限（15 分。確認済み）より V15 が長いので、下限は問題にならない
- 最初の版の土台は targetSdk 36、compileSdk 37、minSdk 30（2026-11-06、#3）。下の条件はすべて当たる
- 前面サービスの種別：targetSdk 34 以上では、長い処理の Worker に前面サービスの種別が要る。`SystemForegroundService` の上書き（`dataSync`）は X7-4-1 に書く。`dataSync` を書く例は文書に無い（確かめた事実の 1-5）
- Android 16 以降の注：長い処理の Worker（前面サービス）がジョブの割り当て時間を使い切ることがある（確かめた事実の 1-6）。C-10 の表で確かめ、必要なら前面サービスの直接起動へ替える（基本設計の直し 18）。C-10 は今後対応。実装（前面サービスの直接起動へ替える余地）は最初の版に入れる（B4-8）
- Android 15 以降（targetSdk 35 以上）の `dataSync` には実行時間の制限がある。制限の時間は取得しておらず未確認（確かめた事実の 1-7）。C-6 は今後対応（B4-8）
- 手動と自動の排他（B2-7）：
  1. 手動の開始時に `auto-sync` が実行中なら、新しい仕事を作らず、実行中のものを画面に出す
  2. 自動の開始時に `manual-sync` が実行中か待機中なら、何もせず終える
  3. 手動の完了から V19 の間は、自動は何もせず終える。判定は T3 の最新の手動の行の `ended_at` による
- 実行中かどうかは、WorkManager の状態の問い合わせ（名前で引く）と、同じプロセス内の排他（1 つの実行だけが `SyncWorker` の本体に入る）の両方で守る
- 自動の起動が失敗したときの再試行の間隔は WorkManager の既定（V に入れない）
- S5 の切り替えは T7 の `auto_sync_enabled` の更新と登録・解除を 1 組で行い、順は「T7 を更新 → 登録または解除」。登録に失敗したら T7 を戻す

### X6-5 HTTP クライアント

| 項目 | 決め方 |
|---|---|
| ライブラリ | Ktor のクライアント（B1-4）。エンジンは OkHttp を第一案とする（Android で枯れている。選定は実装で確かめる。未確認） |
| 接続先 | T4 の接続先 3 つ。ポートは各接続先に含める（V16 は既定） |
| 信頼の判定 | 標準の証明書の検証を使わず、OkHttp エンジンの `TrustManager` と `HostnameVerifier` の差し込みで自前の判定にする（Ktor クライアントの Android での固定は公式文書で確かめられていない。未確認。C-11）。提示された葉の証明書の SPKI の SHA-256 を計算し、T4 の `spki_hash` と比べ、一致したときだけ信頼する。ホスト名は照合しない（B4-3 の 1） |
| P5・P6 の接続 | 証明書を検証せず、提示された SPKI のハッシュを記録して X4 の手順へ渡す（B4-3 の 1）。この接続は固定前の専用のクライアントを使い、他の P と共用しない |
| 待ち時間 | 接続先 1 件は V29、P3 は V14 |
| 圧縮 | P1 だけ `Accept-Encoding: gzip` を付ける。P2 では付けない（曲は圧縮済みで、`Range` と `Content-Length` を保つ） |
| 本文の上限 | 受信側では P1 の大きさに上限を置かない（数 MB。V31 は要求の本文の上限で、受信には当たらない） |

#### 署名ヘッダの付与

1. 要求ごとに、M1 の関数に、メソッド、パスと問い合わせ、本文の SHA-256、時刻、nonce（`CryptoProvider` の乱数）、`device_id` を渡す（X4-3）
2. M1 が署名対象の文字列を組み立て、署名を `CryptoProvider` の HMAC-SHA256 に依頼する。M4 の実体は Keystore に依頼する（X6-6）
3. M4 が `X-Psfox-Device`、`X-Psfox-Timestamp`、`X-Psfox-Nonce`、`X-Psfox-Signature` をヘッダに付ける
4. 再開のたびに、新しい nonce と時刻で署名し直す（B4-4 の 6）。`Range` は署名に含めない

#### Range と応答

| 場面 | 動作 |
|---|---|
| 新規 | `Range` なし。200 |
| 再開 | `Range: bytes=<T5.received_bytes>-`（先頭からの途中までに限る。最後の区間の指定を使わない。B2-6） |
| 206 | `Content-Range` の開始が `received_bytes` と同じことを確かめる。違えば取り直し |
| 200（Range を付けたのに全体が来た） | `"wt"` で最初から書き直す |
| 416 | 最初から取り直す（B4-4 の 3） |
| 409 | E8 |
| 404 | E5 |
| 401（`detail=clock_skew`） | E2 とし、画面に「端末と PC の時計が違う」を出す |
| 401（その他）、署名の失敗 | E2 |
| 503 | E4（P1 のとき） |
| 切断、時間切れ | E6（V10 の再試行の対象）。接続を確立できないときは E1 |
| 証明書の不一致 | E2。ただし鍵そのものが変わった場合の「証明書が変わった」の案内（B4-3 の 1）は X7 が表示する |

### X6-6 鍵の保管 K3・K4

#### K3（署名用の共有の鍵）

| 項目 | 内容 |
|---|---|
| 取り込み | ペアリングの完了時、X4 の手順が返した 32 バイトの鍵を Android Keystore に取り込む。鍵の種類は HMAC-SHA256、用途は署名のみ。Keystore への HMAC の取り込みが使えることは（未確認。実機で確かめる。C-12） |
| 別名 | `psfox-k3-<device_id>`。T4 の `key_alias` に保存。別名の接頭辞は Q1 で変わる |
| 取り出し | しない。署名は Keystore に依頼する（B4-3） |
| 取り込み後 | メモリ上の 32 バイトを上書きして捨てる（ガベージコレクタの都合で完全な保証はない） |
| 解除 | T4 の行を消す前に Keystore の別名を消す。順は「別名を削除 → T4 の行を削除」。途中で落ちたら、T4 の行が残る側に倒れる（再度の解除で完了する） |
| 再ペアリング | 新しい別名で取り込み、T4 を更新してから、古い別名を消す |

- 別名が T4 にあるのに Keystore に無い（端末の変更、OS の鍵の破棄）ときは、E2 とし、再ペアリングへ誘導する。署名の失敗の例外から判断する

#### K4（SPKI のハッシュと PC の識別子）

- T4 の `spki_hash`、`pc_id` に持つ。内部領域に限る。バックアップの対象外（`allowBackup` を無効。B3-2）
- 更新はペアリング完了時だけ。同期の途中で書き換えない（接続先が変わるのを避ける）

#### CryptoProvider の端末側の実体

| 部品 | 実体の置き場 | 備考 |
|---|---|---|
| HMAC-SHA256（署名） | Keystore の K3 | X4-1 の入出力に従う |
| 乱数 | 標準の安全な乱数 | M1 は直接呼ばず、引数で受ける |
| SHA-256、HKDF、点演算 | ペアリングのときだけ使う。実体は X4-1 の表に従い、M4 が渡す | 同期の実行では使わない |

### X6-7 テストの観点

| 対象 | 種類 | 観点 |
|---|---|---|
| Room の移行 | 薄い統合（端末の実行環境。CI の対象外にしてよい。B1-3） | 旧版のスキーマから新版へ。T1、T5 の行が残る。破壊的再作成を使っていない |
| Room の取引 | 同上 | T1 の追加と T5 の削除が 1 取引。T3 の超過分の削除で T6 も消える |
| 曲の書き込みの手順 | 試験用の差し替えを使う単体 | `MediaWriter` の入口を interface にして試験で差し替え、手順（pending → 書く → 読み戻し → T1）の順と、失敗の各点（行の作成、サイズ違い、読み戻した相対パスのずれ）の後の状態を確かめる |
| 再開 | 同上 | `source_modified` が同じ、違う、URI が無いの 3 通り。置き換えは常に最初から |
| ワーカーの手順 | 単体（M1 と HTTP と `MediaWriter` を差し替え） | E1〜E9 の各動作。「消す」の抑止（自動、確認なし、同期全体の失敗の後）。自動の抑止（同じ `content_digest`、E2、E3）。中断後の T3 の閉じ方 |
| HTTP クライアント | 単体（偽のサーバー） | SPKI が一致しないと切断。206 の `Content-Range` の不一致、200 への変化、409・416・401 の扱い。署名ヘッダの付与と、再開ごとの nonce の更新 |
| 鍵の保管 | 実機 | Keystore への HMAC の取り込みと署名。別名の削除。別名が無いときの動作（C-12 へ） |
| WorkManager の登録 | 薄い統合（WorkManager の試験用の部品） | 一意な名前の重複、解除、手動と自動の排他、V19 の抑止 |
| 実機の表へ送るもの | C-n | 最初の実機確認は Android 14 の端末（B4-8）。C-6・C-10 は今後対応。`"wa"`（C-12）、`Files` への insert と索引（C-2）、`setForeground` と `dataSync`・割り当て時間（C-10）、OkHttp での信頼の判定の差し込み（C-11）、時間切れの通知の受け方、`getAllocatableBytes` の精度、`RELATIVE_PATH` の改変 |

- 差し替えの導入は、M1 の純粋な関数（差分、パスの計算、m3u8）を試験の中心にする方針（B1-3）を崩さない範囲にとどめる。M4 の試験は、手順の順序と失敗後の状態に限る

### X6 の決定（未付番）

| # | 内容 | 根拠 |
|---|---|---|
| 1 | Room の移行は手書きだけを使い、破壊的な再作成を使わない | T1 の消失が D14 と同じ状態を作る |
| 2 | 置き換えの途中の再開はせず、最初から書き直す。T5 に置き換えの行を作らない | `"wt"` で切り詰めており、位置が使えない |
| 3 | 読み戻した `RELATIVE_PATH` が `Music/<V22>/` の外なら、その曲を E9 にして行を削除する | m3u8 の相対パスの基準を守る |
| 3b | m3u8 は `MediaStore.Files` に insert する 1 案とする（J18） | `Audio.Media` は playlist の MIME を受け付けない |
| 4 | 自動の抑止の判定（E2、E3）は T3 の最新の行と T4 の `paired_at` から導く。専用の列を足さない | 状態を二重に持たない |
| 5 | 強制終了で `ended_at` が NULL の行は、次の同期の始めに `interrupted` で閉じる | T3 の整合 |
| 6 | P5・P6 のための固定前のクライアントを、他の P のクライアントと別にする | 信頼の判定を混ぜない |
| 7 | 曲の取得（P2）では圧縮を要求しない | `Range` と `Content-Length` を保つ |

### X6 の置いた値

B1-7 への移行の候補（承認後。X1-4 に集まる）：

| 候補 | 項目 | 値 | 使う場所 |
|---|---|---|---|
| X6-b | T5 の `received_bytes` の更新間隔 | V27 の区切りごと（新しい値ではなく V27 の流用） | X6-2 |
| X6-c | 手動の同期の「消す」の確認を経た承認の有効範囲 | 承認した実行 1 回だけ | X6-3 |

### X6 の要判断

| J | 結論 |
|---|---|
| J21（a） | 手動の「消す」の確認は、画面側が P1 と差分の計算までを行って確認を出し、承認後にワーカーが取り直して承認した件数を超えれば飛ばす（X6-3 の 6） |
| J21（a） | S5 に T3 を消す操作を設けない（自動の抑止の判定を守る） |
| J18 | m3u8 は `MediaStore.Files` に insert する（X6-2 の「m3u8」） |

### X6 の差し戻し候補

| 参照 | 内容 |
|---|---|
| 基本設計の直し 5 | T3 の `trigger` から `continue` を消し、`reported` 列を足す |
| 基本設計の直し 6 | T7 に `m3u8_insert_mode` は足さない |
| 基本設計の直し 7 | 空き容量の確認を同期の前の一括にし、`getAllocatableBytes` を使う。置き換えの途中は最初から書き直す |

### X6 の他の節への依頼

| 宛先 | 内容 |
|---|---|
| X1 | M4 のパッケージに `storage`（`MediaWriter`、m3u8 の書き込み）、`db`（Room、T1〜T7）、`sync`（`SyncWorker`、登録）、`net`（Ktor のクライアント、信頼の判定、署名ヘッダ）、`keys`（Keystore、`CryptoProvider` の端末側）の責務を割り当てる（X1-3 は `keystore` の名で確定。X1 を正とする）。X6 の置いた値を X1-4 に集める |
| X3 | 保存先のパスの関数に「衝突の集合」（T1 の既存と MediaStore の問い合わせの結果）を引数で渡す形にする（B3-1 の 4）。MIME の表（拡張子から決める）を M1 に置く。差分の入力の「同期状態」を T1 の全行から作れる型にする。結果の型に、手動の「消す」の確認が要るかの判定を含める（J21（a））。`modified_at` の型（ミリ秒への変換）を示す |
| X4 | 署名の組み立ての関数は、本文の SHA-256 と時刻と nonce を引数で受け、HMAC は `CryptoProvider` に依頼する形にする（M4 の実体は Keystore）。ペアリングの完了時に返す鍵の 32 バイトの受け渡し（X6-6）と、SPKI のハッシュの計算（X4-4）が M4 の信頼の判定から呼べる形にする |
| X5 | P2 の応答のヘッダ（`X-Psfox-Size`、`X-Psfox-Modified-At`）が、200 と 206 の両方に付くこと。409、416、404、401（`detail` つき）の本文の形。P3 が軽い（マニフェストを作らない）こと |
| X7 | S3 に手動の「消す」の確認（J21（a））の出し方を置く。容量が足りないとき（X6-3 の 5）、通知と S3 に `ACTION_MANAGE_STORAGE` の案内を出す（指摘 4）。S4 の入力は T3、T6。S5 の「以前の曲の整理」は X6-2 の「以前の曲を一覧する」の結果を表示し、Activity から `createDeleteRequest` を呼ぶ。通知の更新の担当（V28）は X6-3 の `setForeground` の通知と X7-3 を 1 つにそろえる。「証明書が変わった」と「時計が違う」の案内を表示する |

### X6 の確かめた事実

出典は `reviews/facts-android-2026-11-05.md`（取得日 2026-11-05）。実機では確かめていない。

- 1-1（Define work requests）：周期の最小間隔は 15 分。flex の窓は `repeatInterval - flexInterval` から間隔の終わりまで
- 1-2（Define work requests）：`NetworkType.UNMETERED` がある。複数の制約は全て満たされたときだけ実行され、定期実行は制約が満たされるまで遅れるか飛ばされる
- 1-3（Define work requests）：制約は 5 種で、接続の瞬間に起動する制約は無い
- 1-4（Support for long-running workers）：`CoroutineWorker` は suspend の `setForeground`、3 引数の `ForegroundInfo` で種別を渡す。`DATA_SYNC` の例は文書に無い
- 1-5（同上。一部）：targetSdk 34 以上は前面サービスの種別が要り、`SystemForegroundService` を `tools:node="merge"` で上書きする。例は `location|microphone` で `dataSync` の例は無い
- 1-6（同上）：前面サービスで 10 分を超えて動かせる。Android 16 以降は割り当て時間を使い切ることがある
- 1-7（Changes to foreground services。一部）：targetSdk 35 以上の `dataSync` に実行時間の制限がある。時間は未取得
- 2（Restrictions on starting a foreground service from the background / Define work requests。一部）：`setForeground` は起動の制限で例外を投げうる（`ForegroundServiceStartNotAllowedException`）。`try/catch` が勧められている
- 3-1（MediaProvider.java、MimeUtils.java）：`Audio.Media` に playlist の MIME は insert できない（`IllegalArgumentException`）
- 3-2（MediaProvider.java）：`Files` は MIME が playlist 種別のとき `Music` と `Movies` が許可に加わる
- 3-3（MediaProvider.java）：`Audio.Media` で `Music/` に置けるのは `audio/*` の音声種別
- 3-4（Access media files from shared storage / MediaProvider.java。一部）：`"wa"` の記載は文書に無く、コード上は拒否する箇所を見つけていない。実機は未確認
- 3-5（MediaProvider.java、FileUtils.java）：同名の insert は `名前 (1).拡張子` の別名になる
- 4（Access media files from shared storage。一部）：自分のファイルの削除に同意が不要とは明記が無く、他のアプリ所有分の記述からの読み取り
- 6-1（StatFs。一部）：`getAvailableBytes` は渡した path のファイルシステムの空きを返す
- 6-2（StorageManager）：`getAllocatableBytes` は事前確認に最適で、足りないときは `ACTION_MANAGE_STORAGE`。worker スレッドで呼ぶ
- 次は未確認のまま残る：時間切れの通知の WorkManager での受け方、OkHttp エンジンでの自前の信頼の判定の差し込み（facts-jvm 7c）、Keystore への HMAC 鍵の取り込み、周期の仕事の `KEEP` 相当の指定の名前
- 基本設計の B2-6、B2-7、B3-2〜B3-5、B4-3、B4-4 を読み、手順と表を合わせた（2026-11-05）
