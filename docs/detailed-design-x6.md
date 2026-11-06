# playlist-sync-fox 詳細設計 X6 android の保存と同期（M4）

- 索引：[detailed-design.md](detailed-design.md)（X1）
- 稿：第 4 稿（2026-11-06。review-11 を反映）
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
| `source_modified` | integer | はい | 置いた時点の `modified_at`（マニフェストの値のまま。UTC のミリ秒の整数で、変換は要らない。B2-4） |
| `local_size` | integer | はい | 読み戻した `SIZE` |
| `state` | text | はい | `placed`、`replacing` |
| `placed_at` | integer | はい | 置いた日時 |

- M1 の `SyncRecord`（X3-2）との対応：`persistent_id`＝`persistent_id`、`size`＝`source_size`、`modified_at`＝`source_modified`、`placedPath`＝`relative_path`

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
| `result` | text | はい | `success`、`partial`、`interrupted`、`failed`、`not_started`。実行中の行は `failed` で作り、終わりで更新する。M1 の区分との対応は X6-3 の 10 |
| `error_kind` | text | いいえ | E の番号 |
| `added`、`replaced`、`removed`、`failed` | integer | はい | 件数。初期値 0 |
| `bytes_transferred` | integer | はい | 初期値 0 |
| `reported` | integer | はい | P4 で報告済みなら 1、未報告は 0（初期値 0）。再送の対象の判定に使う（X6-3 の 11） |
| `library_id` | text | いいえ | マニフェストの `library_id`。差分の `recordedLibraryId` は、この列が NULL でない最新の行の値（無ければ初回で null）。書き方は X6-3 の 10（J22、J25） |
| `content_digest` | text | いいえ | P1 のマニフェストの `content_digest`。P1 を取れなかった行は NULL（B3-4）。自動の抑止（X6-3 の 2）の比較に使う（J22） |
| `result_json` | text | いいえ | `SyncResult`（X3-5）の全体を 1 列の JSON で持つ（`library_id`・`content_digest` は判定用に上の列にも持つ。J22）。予定と完了の件数、容量、接続の方式、続きあり、未解決のプレイリスト、自動が「消す」を飛ばした印（`removalSkipped`。X3-5）。S4 の表示と P4 の再送の元。実行中は NULL（J22）。強制終了の跡と、手動の E1 で閉じた行の書き方は X6-3 の 1、2 |

T4 `pairing`：列は B3-4 のとおり。足す列は `last_endpoint`（text。最後に応えた接続先の方式。語は B3-4 の接続の方式。J30）。必須は `endpoint_lan`、`endpoint_tailscale`、`endpoint_usb`、`last_endpoint` だけ「いいえ」。主キー `device_id`。

T5 `pending_transfers`：列は B3-4 のとおり。すべて必須。主キー `persistent_id`。

T6 `run_issues`（索引：`run_id`）：列は B3-4 のとおり。主キーは連番の `issue_id`（自動採番。J23）。必須は `persistent_id`（ID 無しの除外を入れるため）と `error_kind` だけ「いいえ」。足す列は `reason`（除外の理由の細目と、再生できない理由 `protected`・`unsupported_format`）、`artist`、`format`（拡張子）で、いずれも「いいえ」。同じ曲に `excluded` と `failed` など 2 種の行が入ってよい。

T7 `app_settings`：主キー `key`、`value` は必須。既知の `key` は `auto_sync_enabled`（`true`／`false`）、`log_enabled`（同）、`device_id`（初回の起動で作る。解除（T4 の削除）で消さない。T1 はこの値で引く。B3-4）。値が無いときの既定は実装で決める（`auto_sync_enabled` は `false`）。

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
| m3u8 を書く | 名前、本文（M1 の結果） | URI | `MediaStore.Files` に insert（下の「m3u8」）、または既存の URI を `"wt"`。名前が変わったときは古い m3u8 を消す |
| 以前の曲を一覧する | `Music/<アプリ名>/` の下を問い合わせる | T1 に無い曲と、T2 に無い m3u8（`MediaStore.Files`）の一覧（URI、相対パス、サイズ） | なし。読み取りの権限が要る（B3-2、J15、J32） |

#### 曲を新規に書く手順

1. 保存先の相対パスは、X3-3 の関数（M1）が返した値を使う。T1 と `MediaStore.Audio.Media` の問い合わせの両方で、同じ相対パスの別 ID を確かめ、衝突があれば関数に「使用中のパスの集合」を渡して接尾辞を足させる（B3-1 の 4）
2. 空き容量は、同期の前に一括で確かめ済み（X6-3 の 5）。ここでは書き込み中の容量不足（`IOException` のうち空きの不足）を E3 として扱い、pending の行を削除し T5 を消して止める（B2-6）
3. `MediaStore.Audio.Media` に insert する。`DISPLAY_NAME`、`MIME_TYPE`（拡張子の表から決める。表は X3-3 が持つ）、`RELATIVE_PATH`（`Music/<V22>/<アーティスト>/<アルバム>/`）、`IS_PENDING`＝1。行が作れなければ E9
4. 返った URI と、`expected_size`、`source_modified`、`started_at`、`received_bytes`＝0 を T5 に保存する
5. 出力ストリームを開く。再開のときは追記の書き込みモード（`"wa"`）で開き、開けなければ「取り直し」（`"wt"` で `received_bytes`＝0 から）にする。`"wa"` が使えるかは、公式文書に記載が無く、コード上は拒否する箇所を見つけていない。実機で確かめる（未確認。C-12）
6. 入力を、V27 の長さの区切りで読んで書く。1 区切りごとにフラッシュし、`received_bytes` を T5 に更新する。区切りの間に、取り消しの要求（下の X6-3 の「中断と時間の上限」）と容量不足を確かめる
7. 書き終えたら、書いた量を `expected_size` と比べる。足りないとき、取得元の切断なら E6 とし、pending の行と T5 を残して再開に回す（B2-6）。それ以外の不一致は E9 とし、行を削除して T5 を消す
8. `IS_PENDING`＝0 に更新し、`DISPLAY_NAME`、`RELATIVE_PATH`、`SIZE`、`DATE_MODIFIED` を読み戻す
9. 読み戻した値で T1 を書き、T5 を消す（1 取引。「曲の完了の記録」）。T1 の `relative_path` は、読み戻した `RELATIVE_PATH` から `Music/<V22>/` を除いた後に `DISPLAY_NAME` を足した値

- 読み戻した `RELATIVE_PATH` が `Music/<V22>/` で始まらない（MediaStore が場所を変えた）ときは、その曲を E9 として扱い、行を削除する。根拠：`RELATIVE_PATH` はヒントにすぎない（B3-2）。m3u8 の基準が崩れるため

#### 再開と回収

1. 同期の始めの整理（X6-3 の 1）で、T5 の期限切れ（V25）の行を回収する。pending の行を削除し、T5 を消す
2. T5 に行があり、`source_modified` と `expected_size` が今のマニフェストの値と同じなら、その URI を開き直して続ける（手順 5 から）。どちらかが違えば、pending の行を削除して T5 を消し、新規に書く。同じ場合の再開の位置は pending のファイルの大きさを正とする（フラッシュと T5 の更新の間に落ちると T5 の `received_bytes` が遅れる。`received_bytes` が大きさと違えば大きさに合わせて T5 を直す）
3. T5 に行があるが、その URI が MediaStore に無い（利用者が消した、OS が掃除した）ときは、T5 を消して新規に書く
4. 置き換えの途中（T1 の `state`＝`replacing`）は、続きから再開せず、最初から書き直す。根拠：`"wt"` で切り詰めており、途中の位置が使えない。T5 には置き換えの行を作らない

#### 削除と整理

- 自分が作った行の削除に同意の画面が要らないことは、文書に明記が無い。他のアプリが所有するファイルの削除で `RecoverableSecurityException` を捕まえる必要がある、という書き方からの読み取りである（確かめた事実の 4）。実機で確かめる
- 消す対象は T1 の曲だけ。`ContentResolver.delete` を呼び、成功か、行が無い（既に消えている）ときに T1 の行を消す。権限の例外（`SecurityException`）は、その曲だけ E9 にして T1 を残す
- 空のフォルダの後始末はしない（B3-2）
- 入れ直し後の整理は、「以前の曲を一覧する」の結果（T1 に無い曲と、T2 に無い m3u8。入れ直し後の古い m3u8 を含む。J32）を X7 が表示し、選ばれたものを Activity が `createDeleteRequest` で消す。ワーカーからは呼ばない（B3-2）。ワーカーは整理の対象を作らない
- 入れ直し後の初回の同期で、MediaStore が名前を変えた（`(1)` など）場合も、読み戻した値を T1 に保存する（手順 8）ので整合する

#### m3u8

1. 本文は X3-4 の関数が返す。入力は、プレイリストの順の `persistent_id` と、T1 から引いた `relative_path`（T1 に無い曲は除く）。UTF-8（BOM なし）のバイト列で書く
2. m3u8 は `MediaStore.Files` に書く（J18）。`MediaStore.Audio.Media` は playlist の MIME を insert できず `IllegalArgumentException` になるため使わない。`Files` では MIME が playlist 種別のとき `Music/` が許可される
3. 新規は `MediaStore.Files` に insert する。値は次のとおり。行が作れなければ、そのプレイリストを E9 にして続ける
   - `DISPLAY_NAME`＝`<名前>.m3u8`（拡張子は m3u8 のまま）
   - `MIME_TYPE`＝`audio/x-mpegurl`
   - `RELATIVE_PATH`＝`Music/<V22>/`
   - `IS_PENDING`＝1 で書き、書き終えて 0 に更新する
4. 既存の確認：T2 に同じ `playlist_id` の行があり、その `relative_path` の名前が X3-4 の `buildPlaylistFileName` の結果と同じなら、T2 の URI を開いて `"wt"` で書く。同名の insert は上書きにならず `名前 (1).m3u8` の形の別名になる（確かめた事実の 3-5）。そのため新規の insert の前に T2 で既存を確かめ、あれば既存を書き直す。読み戻した `DISPLAY_NAME` と `RELATIVE_PATH` を T2 に保存する
   - 読み戻した名前が期待した名前（`buildPlaylistFileName` の結果）に「 (n)」を足しただけのときは、改名とみなさない（入れ直し後の初回の別名が、同期のたびに消して作り直されるのを避ける）。T2 の URI を開いて `"wt"` で書く
   - 名前が違う（上の場合を除く。プレイリストの改名）なら、T2 の URI の古い m3u8 を削除してから、新しい名前で新規に insert する（手順 3）。古い m3u8 の削除に失敗しても、新しい書き込みは続ける（残った古い m3u8 は「以前の曲を一覧する」の対象になる）
5. 書き終えたら索引の更新を促す（`IS_PENDING` の切り替え、または `MediaScannerConnection.scanFile`。どちらで足りるかは未確認。C-2 の「`Files` への insert と索引の実機確認」で確かめる）
6. T2 の `track_count`、`written_at` を更新する
7. `playlists` と `unresolved_playlists` のどちらにも無い T2 の行は、m3u8 を削除して T2 の行を消す。未解決のプレイリストの T2 の行と m3u8 は消さない（J24）

### X6-3 同期の実行

#### 実行の入口

| 項目 | 内容 |
|---|---|
| 担い手 | `SyncWorker`（`CoroutineWorker`）。1 つの実装を手動と自動で使う |
| 入力データ | `trigger`（`manual`／`auto`）、`delete_approved`（真偽。手動で確認を済ませたとき真）、`approved_delete_count`（承認した「消す」の件数） |
| 前面への昇格 | 到達確認（手順 2 の P3）が通った後に `setForeground`（種別 `dataSync`）を呼ぶ。届かない自動で前面の通知を出さないため（B2-7）。通知の内容は B3-5 の表。更新は V28 の間隔以上 |
| 結果 | `Result.success`（中断、一部失敗を含む。結果は T3 に持つ）。再試行は WorkManager に求めない（B3-5 の 6 は到達の失敗だけ） |

#### 手順

1. 始めの整理（手動・自動とも。端末内の処理だけを、次の抑止より前に行う）：
   1. T5 の期限切れ（V25）の行を回収する（X6-2 の「再開と回収」）
   2. `ended_at` が NULL の古い T3 の行を `interrupted` で閉じる（`error_kind` は空）。強制終了の跡なので `reported`＝1 にして再送しない
2. 到達確認と自動の抑止：
   1. 自動のとき、次のどれかなら終える。診断のログに 1 行書き、T3 に残さない（B2-7、B3-4、J10）。(a) 最新の手動の行の `ended_at` から V19 以内、(b) `ended_at` が T4 の `paired_at` 以後の T3 の行に E2 がある、(c) 最新の E3 の行より後に、手動の `success` か `partial` の行が無い（E3 の行が無ければ当たらない）。行の種類で引くので、間に手動の E1 や中断の行が入っても外れない
   2. 保存した接続先を P3 で試す（B2-7、V14、V29）。順は `last_endpoint` を先頭にし、残りは LAN、Tailscale、USB。LAN は保存したサービス名を NSD で引き直してから試し、結果が違えば `endpoint_lan` を更新する（V14）。応えた方式を `last_endpoint` に保存する（J30）。通ったら `setForeground` を呼ぶ
   3. 届かなければ、手動は T3 に行を追加して E1（`failed`）で閉じて終える。書く列は `result_json` に分かる範囲（予定の件数など。無ければ空の件数）で、`reported`＝0 のまま次回の再送の対象にする。自動は診断のログに 1 行書いて終える（T3 に残さない）
   4. P3 が E2 を返したら、手動・自動とも T3 に行を追加して E2（`failed`）で閉じて終える。この自動の E2 の行は (b) の抑止の印なので、「抑止で終えた自動は残さない」規則とは別の扱いとする（`reported`＝0。再送の対象）
   5. P3 が通った後に、`reported`＝0 の T3 の行を P4 で再送する。回数は 1 回の同期で 1 度。成功するまで次の同期で試す（B4-4 の 5）。失敗しても結果に影響しない
   6. 自動のとき、P3 の `content_digest` が T3 の最新の完了した行（`content_digest` が NULL でない最新の行）と同じで、その行が `success` なら終える（T3 に残さない）
3. T3 に行を追加する（`ended_at` は NULL）。上の抑止で終える自動は追加しない。手順 2 の 3（手動）と 4（手動・自動）で終えるときは、そこで追加している（J10）
4. P1 でマニフェストを取り、M1 の差分を計算する（足す、置き換える、消す）。入力は T1 の全行、`presentIds`（T1 の曲のうち MediaStore に行が今もある曲の `persistent_id`。無い曲は X3-2 の判定で「足す」になる）、記録の `library_id`（X6-1 の T3）、V20
5. 容量の確認：「足す」「置き換える」の `size` の合計に V11 を足し、共有ストレージのボリュームの UUID で `StorageManager.getAllocatableBytes` を worker スレッドで呼んで比べる（数秒かかることがある）。呼べなければ `StatFs` の空きで比べる。足りなければ E3、`not_started` で終える。不足分を T3 と通知へ渡す。通知と S3 には `ACTION_MANAGE_STORAGE` で利用者に空けてもらう案内を出す（X7 への依頼）
6. 「消す」の扱い（J9、J21（a）、J24〜J26）。判定は M1 の `removalGate`（X3-2。未解決のプレイリストの有無も入力に含まれる。J24）：
   1. 手動では、画面側（前面。X7-1-2）が、ワーカーを起動する前に P1 と差分の計算までを行う。`Allowed` でなければ「消す」の件数を確認の画面に出す。`Allowed` なら確認なしで起動する。画面側の P1 が失敗したとき（届かない、E2、E4）は、確認を飛ばしてワーカーを起動し、ワーカーが手順 2 から E を記録する
   2. 承認されたら、`delete_approved`＝真と `approved_delete_count` を付けてワーカーを起動する。確認が取り消されたら、`delete_approved`＝偽で起動し、「消す」だけ飛ばして同期する（J26）
   3. ワーカーは手順 4 で P1 を取り直して差分を再計算し、`removalGate` で決める。自動は `Allowed` なら実行し、ほかは飛ばす。手動は `Allowed` なら実行し、ほかは `delete_approved`＝真で「消す」の件数が `approved_delete_count` 以内のときだけ実行して、それ以外は飛ばす
   4. `LibraryChanged` でも `toRemove` は計算されて返る（J25）。承認されて実行したとき、記録の `library_id` を新しい値にする（手順 10）。`toRemove` が 0 件なら `removalGate` は `Allowed` を返し（X3-2）、確認なしで記録の `library_id` を新しい値にする（B2-5）
   5. 自動が「消す」を飛ばしたときは、その印を T3 の `result_json` に書く（手順 10。X7 の依頼）
7. 「足す」と「置き換える」を 1 曲ずつ直列に行う（B2-6）。1 曲の手順：
   1. 取得の要求を P2 で送る。新規も再開も、保存した `size`、`modified_at` を付ける。再開は加えて `Range: bytes=<pending のファイルの大きさ>-` を付ける（位置は大きさを正とし、T5 の `received_bytes` と違えば T5 を直す。X6-2 の「再開と回収」。X6-5）
   2. 応答の `X-Psfox-Size`、`X-Psfox-Modified-At` を検査する。新規と置き換えはマニフェストの `size`、`modified_at` と、再開は T5 の `expected_size`、`source_modified` と比べる。違えば E8
   3. X6-2 の書き込みに本文を渡す
   4. 失敗の扱い：E5、E8、E9 は曲単位。T6 に `failed` を書き、次の曲へ進む。E6（切断）は pending の行と T5 を残し、V10 の回数まで同じ曲を再開する。超えたら同期全体を止める（行と T5 は残す）。E3 は途中なら pending の行を削除し T5 を消して、同期全体を止める（B2-6）。再開の曲が E8 になったときは、pending の行と T5 を消す。E8 の曲は、終わりで 1 回だけマニフェストを取り直して差分を取り直す（B2-6）
8. m3u8：選択されたプレイリストごとに、X6-2 の「m3u8」を行う。中断で終わるときも書く（B2-6）。未解決のプレイリストの m3u8 は消さない（J24）
9. 「消す」：同期全体の失敗（E1、E2、E3、E4、E6、E7）がなく、手順 6 で許されているときだけ、T1 の曲を X6-2 で消す
10. T3 を更新する。`result` は M1 の区分から写す：完了＝`success`、一部＝`partial`、中断＝`interrupted`、失敗＝`failed`。ただし手順 5 の容量不足（E3 の事前）は `not_started`。書く列は、`ended_at`、`result`、`error_kind`、件数、`bytes_transferred`、`result_json`、`content_digest`（P1 のマニフェストの値。P1 を取れなかったときは NULL）、`library_id`。`library_id` は今回のマニフェストの値とする。ただし `LibraryChanged` で `toRemove` が 1 件以上あり、手順 9 の「消す」を実行しなかったときは、記録の値のまま（承認して実行したとき新しい値に替わる。0 件なら確認なしで新しい値。J25）。T6 に `excluded`（マニフェストの除外）、`unplayable`（保護付きの印と端末が判定したもの）、`failed` を書く
11. P4 で要約を報告する（`SyncReport`。X3-5）。失敗しても結果に影響しない。報告できたら T3 の `reported` を 1 にする。報告できなかった実行は、次回の手順 2 の 5 で再送する。再送の本文は T3 の列と `result_json` から作る
12. 終了の通知を出す（結果と件数。タップで S4。自動は静かな通知）

#### 進行の状態（SyncProgress）

- X6 が公開する読み取りの状態。型は X7-1-4 の表のとおり（`stage`、`done_count`、`total_count`、`done_bytes`、`total_bytes`、`current_title`、`shortage_bytes`、`near_limit`、`outcome`、`error_detail`）。ワーカーが更新し、画面（S3）と通知（X7-3）が購読する。DB には持たない（T3 は終了後の記録）
- `stage` は手順に対応する：`connecting`（手順 1 の始めの整理と、手順 2 の到達確認）、`manifest`（手順 4 の P1）、`diff`（差分の計算）、`capacity`（手順 5。足りないとき `shortage_bytes` を入れる）、`transfer`（手順 7）、`playlist`（手順 8）、`finished`（手順 10 の後。`outcome` に T3 の `result`。E2 のときは `error_detail` に E2 の `detail` を入れる。T3 には残らず、X7-1-3 の表示だけに使う）
- `near_limit` は、ワーカーが前面へ昇格してからの経過が V17 に達したときに真にする（時間の上限に当たる前の注意。V17 以外の値は置かない）。更新は V28 の間隔以上

#### 中断と時間の上限

| きっかけ | 検出 | 動作 |
|---|---|---|
| 時間の上限（E7） | OS の時間切れの通知（WorkManager の停止の理由で受ける。受け方は未確認） | 現在の曲の書き込みを止める。pending の行と T5 を残す。T3 を `interrupted`、`error_kind`＝E7 にする。数秒以内に戻る（V27 の区切りで確かめる） |
| 利用者の中断 | 通知の「中断」、S3 の操作。ワーカーの取り消し | 現在の曲を同じく止め、T3 を `interrupted`（`error_kind` は空） |
| プロセスの強制終了 | 検出できない | T3 の行は `ended_at`＝NULL のまま。次の同期の始めの整理（手順 1 の 2）で、`ended_at` が NULL の古い行を `interrupted` で閉じる（`error_kind` は空。`reported`＝1 にして再送しない） |

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
- Android 15 以降（targetSdk 35 以上）の `dataSync` は、24 時間に合計 6 時間で止められる。アプリを前面に出すと計時が戻る（B3-5。review-01 の確認した事実）。C-6 は今後対応（B4-8）
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
| 再開 | `Range: bytes=<pending のファイルの大きさ>-`（先頭からの途中までに限る。最後の区間の指定を使わない。B2-6） |
| 206 | `Content-Range` の開始が、送った位置（pending のファイルの大きさ）と同じことを確かめる。違えば取り直し |
| 200（Range を付けたのに全体が来た） | `"wt"` で最初から書き直す |
| 416 | 最初から取り直す（B4-4 の 3） |
| 409 | E8 |
| 404 | E5 |
| 401（`detail=clock_skew`） | E2 とし、画面に「端末と PC の時計が違う」を出す |
| 401（その他）、署名の失敗 | E2 |
| 503 | E4（P1 のとき。P2 でも、PC の曲の表が空のときは同期全体の失敗の E4。次の同期は P1 から） |
| 切断、時間切れ | E6（V10 の再試行の対象）。接続を確立できないときは E1 |
| 証明書の不一致 | E2（J28。自動は再ペアリングまで止まる）。ただし鍵そのものが変わった場合の「証明書が変わった」の案内（B4-3 の 1）は X7 が表示する |

### X6-6 鍵の保管 K3・K4

#### K3（署名用の共有の鍵）

| 項目 | 内容 |
|---|---|
| 取り込み | P6 の成功で確定する（J27）。失敗や離脱では T4 と Keystore に書かない。X4 の手順が返した 32 バイトの鍵を Android Keystore に取り込む。鍵の種類は HMAC-SHA256、用途は署名のみ。Keystore への HMAC の取り込みが使えることは（未確認。実機で確かめる。C-12） |
| 別名 | `psfox-k3-<device_id>`。T4 の `key_alias` に保存。別名の接頭辞は Q1 で変わる |
| 取り出し | しない。署名は Keystore に依頼する（B4-3） |
| 取り込み後 | メモリ上の 32 バイトを上書きして捨てる（ガベージコレクタの都合で完全な保証はない） |
| 解除 | T4 の行を消す前に Keystore の別名を消す。順は「別名を削除 → T4 の行を削除」。途中で落ちたら、T4 の行が残る側に倒れる（再度の解除で完了する） |
| 再ペアリング | 別名は `device_id` で決まるので変わらない。同じ別名へ上書きで取り込み、成功したら T4 を更新する。失敗したら T4 は更新せず、PC の W3 で端末を解除してから再ペアリングをやり直す（P6 の後の Keystore・T4 の失敗と、D4 で落ちた場合も同じ。PC に登録が残るため、そのままでは P5 が `device_exists` になる。J27） |

- 別名が T4 にあるのに Keystore に無い（端末の変更、OS の鍵の破棄）ときは、E2 とし、再ペアリングへ誘導する。署名の失敗の例外から判断する

#### K4（SPKI のハッシュと PC の識別子）

- T4 の `spki_hash`、`pc_id` に持つ。内部領域に限る。バックアップの対象外（`allowBackup` を無効。B3-2）
- 更新は P6 の成功で確定するペアリング完了時だけ。失敗や離脱では T4 に書かない（J27）。同期の途中で書き換えない（接続先が変わるのを避ける）

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
| 曲の書き込みの手順 | 試験用の差し替えを使う単体 | `MediaWriter` の入口を interface にして試験で差し替え、成功のときの結果の状態（T1 に読み戻した値の行、T5 の行が無い、pending でない）と、失敗の各点（行の作成、サイズ違い、読み戻した相対パスのずれ）の後の状態を確かめる |
| 再開 | 同上 | `source_modified` が同じ、違う、URI が無いの 3 通り。置き換えは常に最初から。再開の位置は pending のファイルの大きさ（T5 とずれる場合） |
| ワーカーの手順 | 単体（M1 と HTTP と `MediaWriter` を差し替え） | E1〜E9 の各動作（E6 は行と T5 を残す、E3 の途中は削除）。始めの整理。「消す」の扱い（自動の `Allowed`、確認なし、確認の取り消し、同期全体の失敗の後、`library_id` の違いと承認後の記録の更新）。自動の抑止（V19、E2、E3、同じ `content_digest`）と、抑止で終えたとき T3 に残らないこと。中断後の T3 の閉じ方。M1 の区分と `result` の対応 |
| HTTP クライアント | 単体（偽のサーバー） | SPKI が一致しないと切断。206 の `Content-Range` の不一致、200 への変化、409・416・401 の扱い。署名ヘッダの付与と、再開ごとの nonce の更新 |
| 鍵の保管 | 実機 | Keystore への HMAC の取り込みと署名。別名の削除。別名が無いときの動作（C-12 へ） |
| WorkManager の登録 | 薄い統合（WorkManager の試験用の部品） | 一意な名前の重複、解除、手動と自動の排他、V19 の抑止 |
| 実機の表へ送るもの | C-n | 最初の実機確認は Android 14 の端末（B4-8）。C-6・C-10 は今後対応。`"wa"`（C-12）、`Files` への insert と索引（C-2）、`setForeground` と `dataSync`・割り当て時間（C-10）、OkHttp での信頼の判定の差し込み（C-11）、時間切れの通知の受け方、`getAllocatableBytes` の精度、`RELATIVE_PATH` の改変 |

- 差し替えの導入は、M1 の純粋な関数（差分、パスの計算、m3u8）を試験の中心にする方針（B1-3）を崩さない範囲にとどめる。M4 の試験は、成功と失敗の後の状態に限る。呼び出しの順は確かめない（実装の写しになるため）

### X6 の決定（未付番）

| # | 内容 | 根拠 |
|---|---|---|
| 1 | Room の移行は手書きだけを使い、破壊的な再作成を使わない | T1 の消失が D14 と同じ状態を作る |
| 2 | 置き換えの途中の再開はせず、最初から書き直す。T5 に置き換えの行を作らない | `"wt"` で切り詰めており、位置が使えない |
| 3 | 読み戻した `RELATIVE_PATH` が `Music/<V22>/` の外なら、その曲を E9 にして行を削除する | m3u8 の相対パスの基準を守る |
| 3b | m3u8 は `MediaStore.Files` に insert する 1 案とする（J18） | `Audio.Media` は playlist の MIME を受け付けない |
| 4 | 自動の抑止の判定（E2、E3）は T3 の行の種類と T4 の `paired_at` から導く（X6-3 の 2.1、B2-7）。専用の列を足さない | 状態を二重に持たない |
| 5 | 強制終了で `ended_at` が NULL の行は、次の同期の始めに `interrupted` で閉じる | T3 の整合 |
| 6 | P5・P6 のための固定前のクライアントを、他の P のクライアントと別にする | 信頼の判定を混ぜない |
| 7 | 曲の取得（P2）では圧縮を要求しない | `Range` と `Content-Length` を保つ |
| 8 | T3 に `library_id`、`content_digest`、`result_json` を足す（J22）。T4 に `last_endpoint`（J30）、T6 は連番の主キーと `reason`・`artist`・`format`（J23） | S4 の表示、P4 の再送、自動の抑止、差分の入力を作るため |
| 9 | 自動の抑止で終えるとき T3 に残さない。E2・E3・V19 は P3 の前、`content_digest` は P3 の後で判定する | 抑止の判定に使う行を増やさない |
| 10 | 確認の取り消しは「消す」だけ飛ばして同期する（J26）。`LibraryChanged` は承認後に記録の `library_id` を更新する（J25）。未解決のプレイリストがあれば「消す」は確認の扱い（J24） | B2-5、X7-1-2 と合わせる |
| 11 | 「以前の曲を一覧する」に T2 に無い m3u8 を含める（J32）。証明書の不一致は E2（J28） | 入れ直し後の古い m3u8、B4-3 との一致 |

### X6 の置いた値

V47（T5 の `received_bytes` の更新間隔）、V48（手動の「消す」の確認の承認の有効範囲）は basic-design.md の B1-7 に反映済み。

### X6 の要判断

J18、J21（a）の結論は本文に反映済み（review-05、06）。J22〜J26、J28、J30、J32 も本文に反映した（review-11）。

### X6 の差し戻し候補

基本設計の直し 5〜7 は B2、B3 に反映済み（review-05）。

### X6 の他の節への依頼

X3 への依頼は反映済み（review-11）。未了だけを残す。

| 宛先 | 内容 |
|---|---|
| X7 | S3 に手動の「消す」の確認を置く。取り消しは「消す」だけ飛ばして同期（J26）。画面側の P1 が失敗したら確認を飛ばしてワーカーを起動する（X6-3 の 6）。容量が足りないとき（X6-3 の 5）の `ACTION_MANAGE_STORAGE` の案内。自動が「消す」を飛ばした印は T3 の `result_json` の `removalSkipped`。S3 は SyncProgress（X6-3）を表示する。S4 の入力は T3、T6。S5 の「以前の曲の整理」は X6-2 の「以前の曲を一覧する」の結果（T2 に無い m3u8 を含む。J32）を表示し、Activity から `createDeleteRequest` を呼ぶ。通知の更新の担当（V28）は X6-3 の `setForeground` の通知と X7-3 を 1 つにそろえる。「証明書が変わった」と「時計が違う」の案内。X7-2-1 の NSD の記述は、同期の到達確認での引き直し（X6-3 の 2）を含める形にする |

### X6 の確かめた事実

出典は `reviews/facts-android-2026-11-05.md`（取得日 2026-11-05）。実機では確かめていない。

- 1-1（Define work requests）：周期の最小間隔は 15 分。flex の窓は `repeatInterval - flexInterval` から間隔の終わりまで
- 1-2（Define work requests）：`NetworkType.UNMETERED` がある。複数の制約は全て満たされたときだけ実行され、定期実行は制約が満たされるまで遅れるか飛ばされる
- 1-3（Define work requests）：制約は 5 種で、接続の瞬間に起動する制約は無い
- 1-4（Support for long-running workers）：`CoroutineWorker` は suspend の `setForeground`、3 引数の `ForegroundInfo` で種別を渡す。`DATA_SYNC` の例は文書に無い
- 1-5（同上。一部）：targetSdk 34 以上は前面サービスの種別が要り、`SystemForegroundService` を `tools:node="merge"` で上書きする。例は `location|microphone` で `dataSync` の例は無い
- 1-6（同上）：前面サービスで 10 分を超えて動かせる。Android 16 以降は割り当て時間を使い切ることがある
- 1-7（Changes to foreground services。一部）：targetSdk 35 以上の `dataSync` に実行時間の制限がある。時間（24 時間に合計 6 時間）は review-01 の確認した事実（B3-5）
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
