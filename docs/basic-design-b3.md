# playlist-sync-fox 基本設計 B3 端末側

- 索引：[basic-design.md](basic-design.md)
- 稿：第 3 稿（2026-11-05。詳細設計 review-05 の差し戻しを反映）
- 参照：B1 は [basic-design.md](basic-design.md)、B2 は [basic-design-b2.md](basic-design-b2.md)、B4 は [basic-design-b4a.md](basic-design-b4a.md)（B4-1〜B4-4）と [basic-design-b4b.md](basic-design-b4b.md)（B4-5〜B4-8）。概要設計は [design-overview.md](design-overview.md)、[requirements.md](requirements.md)、[open-questions.md](open-questions.md)

## B3 端末側

### B3 の書く項目

- B3-1 保存先と命名：`Music/<アプリ名>/<アーティスト>/<アルバム>/<曲名>.<拡張子>` の決め方。Album Artist の優先、使えない文字の置き換え、長さの上限、衝突時の Persistent ID の足し方、相対パスの決め方
- B3-2 MediaStore 経由の書き込み：insert、IS_PENDING、権限が要らない範囲、自分のファイルの更新と削除、入れ直し後の所有の切れと削除の同意の画面、空き容量の確認
- B3-3 m3u8：形式、置き場、相対パスの基準、MediaStore の索引、作り直しと曲の外し方、プレイリスト名の使えない文字
- B3-4 同期状態の表 T1〜T7（Room）：列の表と、表の外に置くもの（ログ、鍵）
- B3-5 前面サービスと時間の上限：種別、宣言、6 時間の上限、WorkManager、中断と続き、通知
- B3-6 画面 S1〜S5（Jetpack Compose）：画面ごとの表示項目と操作
- B3-7 権限の一覧と、Android の最低版 11 の根拠

### B3 の対応する番号

| 区分 | 番号 |
|---|---|
| F | F3-3、F3-5、F3-6、F3-7、F3-8、F4-1〜F4-5、F6-1、F6-2、F6-3 |
| N | N1-4、N2-4、N2-6、N3-3、N3-4、N3-5、N5-2、N5-3 |
| D | D3、D7、D9、D11、D13、D14 |
| Q | Q4、Q7（結論は節末）。Q9 は T4 の列の持ち方だけ |

### B3-1 保存先と命名

対応：F4-1、Q7、D9。

前提：

- 決定（D3、Q7 の暫定採用）：曲は `Music/<アプリ名>/…` に MediaStore 経由で置く。`<アプリ名>` は仮名の playlist-sync-fox（Q1 が決まったら変わる。フォルダ名は V22 の 1 値に一本化する）
- 決定（未付番）：保存先の計算（名前の置き換え、長さの切り詰め、衝突の接尾辞）は shared の純粋な関数にする。N5-1 の対象とし、テストの中心に置く。根拠：m3u8 の生成（B3-3）が同じパスを使い、計算を端末の 1 か所に集めるため（J7）

相対パスの決め方：

1. マニフェストは材料（アーティスト、アルバム、曲名、拡張子、Persistent ID。B2-4）だけを持つ。端末が M1 の関数で相対パスを計算する（J7）
2. 候補の基準は `Music/<アプリ名>/` で、形は `<アーティスト>/<アルバム>/<曲名>.<拡張子>`。先頭の `Music/<アプリ名>/` は含めない
3. 端末はマニフェスト全体の候補を先に計算し、マニフェスト内の衝突も同じ関数で解く（下の「衝突」）
4. 置く直前に端末の既存との衝突も確認し、衝突したら同じ関数の規則で接尾辞を足す。足した結果を置いた相対パスとして T1 に保存し、以後はこの値を m3u8 に使う
5. 一度置いた曲は、メタデータの変更（曲名、アーティストの変更）で候補が変わっても動かさない。内容の置き換え（F3-3）でも同じパスで上書きする。暫定：名前の変更への追従は最初の版では行わない（J15）

アーティストの名前：

| 順 | 使う項目 | 理由 |
|---|---|---|
| 1 | Album Artist（空でないとき） | 合作や複数のアーティストが参加する曲を、アルバム単位で 1 つのフォルダに集める |
| 2 | Artist | Album Artist が空のとき |
| 3 | `Unknown Artist` | どちらも空のとき |

- アルバムが空のときは `Unknown Album`、曲名が空のときは `Untitled`。この 3 つの文字列は V ではなく本文で固定する（表示の値でなく、フォルダ名の約束のため）
- Album Artist は B2-1 で XML から取る（B2 への依頼 2 は済）

使えない文字の置き換え（ファイル名とフォルダ名の 1 要素ごとに適用）：

| 対象 | 置き換え | 理由 |
|---|---|---|
| `/` `\` `:` `*` `?` `"` `<` `>` `\|` | `_` | Android の共有ストレージが FAT 系の制約で拒否する、または MediaStore が書き換える（挙動は未確認。確実に通る文字だけを使う） |
| 制御文字（U+0000〜U+001F、U+007F、U+0085、U+2028、U+2029） | 削除 | 表示できず、パスを壊す（review-05 の基本設計の直し 11） |
| 先頭の `#` | `_` | m3u8 の本体でコメント行と読まれるのを避ける（review-05 の基本設計の直し 11） |
| 先頭と末尾の空白、末尾の `.` | 削除 | 隠しファイルや空の名前を避ける |
| `.` だけ、`..` だけ | `_` | ディレクトリの指定と区別がつかない |
| 置き換えた後に空 | `Untitled`（曲）、`Unknown Album`、`Unknown Artist` | 空の要素を作らない |

- 日本語、記号、絵文字はそのまま残す。Unicode の正規化（NFC など）はしない。根拠：XML の値を変えず、PC と端末で同じ文字列になる
- 置き換えは 1 要素ごとの文字列の変換だけで、パスの区切りを足さない（N1-7 と同じ考え方：外から入る文字列でパスの階層を作らない）

長さの上限：

- 1 要素（ファイル名またはフォルダ名）の上限は UTF-8 で V23 のバイト数。根拠：Linux のファイル名の上限は 1 要素 255 バイトで、日本語は 1 文字 3 バイトになる（未確認。端末のファイルシステムによる）
- 超えるときは、文字の境界で曲名（アーティスト、アルバム）の後ろを切る。拡張子と、衝突の接尾辞（次の「衝突」）の分は切らずに残す
- パス全体の上限は設けない。MediaStore の RELATIVE_PATH の長さ制限は未確認。実機で確かめ、必要なら J 候補にする

衝突（F4-1）：

- 相対パスの比較は、大文字小文字を区別しないで行う。FAT 系のストレージに備える安全側の規則（review-05 の基本設計の直し 12）
- 同じ相対パスに、Persistent ID が違う別トラックが来たとき、後から置く側のファイル名の拡張子の前に ` [` ＋ Persistent ID の先頭の V24 文字 ＋ `]` を足す。例：`夜に駆ける [1A2B3C4D].m4a`（例の名は架空）
- 「後から置く側」は、マニフェストの中の衝突も端末が同じ関数で決め（J7）、端末の既存との衝突（T1 に同じパスの別 ID がある、または MediaStore の問い合わせで既存と分かる）は端末が決める
- 接尾辞を足しても衝突するとき（Persistent ID の先頭の一致）は、V24 を伸ばして Persistent ID の全文（16 文字）まで足す。全文でも衝突しないことは Persistent ID の一意性が保つ
- 自分が置いていないファイルは問い合わせに現れない（B3-2）。同じ名前を insert すると MediaStore が `名前 (1).拡張子` の形に変える（確かめた事実。facts-android 3-5）。したがって insert 後に実際の `DISPLAY_NAME` と `RELATIVE_PATH` を読み戻し、T1 には読み戻した値を保存する

### B3-2 MediaStore 経由の書き込み

対応：F4-1、F4-5、F3-5、N2-4、D9、D14、Q7。

権限が要らない範囲（確かめた事実。2026-11-05 取得）：

- Android 10 以降は、自分が作ったメディアファイルの読み取りと変更に、ストレージ関連の権限は要らない
- 自分のファイルは、MediaStore がアプリを持ち主として記録する。持ち主は 1 ファイル 1 アプリ
- 他のアプリが作ったファイルの読み取りには `READ_MEDIA_AUDIO`（Android 13 以降）か `READ_EXTERNAL_STORAGE`（Android 12 以下）が要る。playlist-sync-fox は他のファイルを読まないので求めない（F4-5、B3-7）
- 帰結：衝突の確認の問い合わせ（`MediaStore.Audio.Media` への query）は、自分のファイルだけを返す。利用者が別に入れた曲は見えない。それでよい（見えないものには触れないので、F4-5 を保てる）

書き込みの手順（1 曲）：

| 順 | 操作 | 失敗時 |
|---|---|---|
| 1 | `MediaStore.Audio.Media` に insert。`DISPLAY_NAME`、`MIME_TYPE`（拡張子から決める）、`RELATIVE_PATH`（`Music/<アプリ名>/<アーティスト>/<アルバム>/`）、`IS_PENDING`＝1 を設定 | 行を作れなければ、その曲を失敗として次へ |
| 2 | 返った URI を T5（進行中の取得）に保存し、出力ストリームへ書く | 中断時は T5 の行と pending の行を残す（再開の項） |
| 3 | 書き終えたらサイズをマニフェストの値と比べる | 違えば行を削除して失敗 |
| 4 | `IS_PENDING`＝0 に更新。読み戻した `DISPLAY_NAME`、`RELATIVE_PATH`、`SIZE`、`DATE_MODIFIED` を得る | — |
| 5 | T1 に行を書き、T5 の行を消す | — |

- 空き容量の確認は曲ごとの手順に含めず、同期の前の一括で行う（下の「空き容量」。review-05 の基本設計の直し 7）

- `IS_PENDING`＝1 の間は、自分のアプリだけがそのファイルを見られる（確かめた事実）。完了前のファイルがプレイヤーに現れない。これを使う根拠
- `RELATIVE_PATH` は保存先を示すヒントで、仕様上は必ずその場所になるとは限らない（確かめた事実の表現は「hint」）。読み戻した値を T1 に保存する理由
- 再開（F3-4、B2 の転送の手順）：中断した曲は、T5 の URI の pending の行を開き直して続きから書く。追記の書き込みモード（`"wa"`）が MediaStore の URI で使えるかは未確認（公式文書は `"w"` の例のみ。MediaProvider のコードに `"wa"` を拒否する箇所は見つからなかった。facts-android 3-4）。使えなければ、その曲だけ最初から取り直す。この確認は N5-3 の実機の表と B4b の C-12 に入れる（J15）
- 完了前の行は、T5 の期限（V25）を過ぎたら削除する。pending の行の長期の残りを避けるため

内容の置き換え（F3-3 の「置き換える」）：

- 自分のファイルを `IS_PENDING`＝1 にして `"wt"`（切り詰め）で書き直し、完了で 0 に戻す。同じ URI と同じ相対パスを保つので、m3u8 の変更は不要
- 書き直しの途中で失敗したら、T1 の `state` を `replacing` にしておき、次回の同期で最初から書き直す（途中からの続きはしない。review-05 の基本設計の直し 7）。端末のその曲は壊れうるが、完了しない行は pending なのでプレイヤーには見えない（確かめた事実の範囲。更新時の pending の見え方は未確認）

削除（F3-3 の「消す」、D9）：

- 消すのは T1 にある曲だけ。自分のファイルの削除（`ContentResolver.delete`）は同意の画面なしで行う（自分のファイルの変更は権限が要らないことから。「自分のファイルの削除に同意が不要」とは明記が無く、他のアプリが所有するファイルの削除に `RecoverableSecurityException` の処理が要るという記述からの読み取り。facts-android 4。実機で確かめる）
- 削除の後、T1 の行を消す。フォルダが空になったときの後始末はしない（MediaStore はフォルダを個別に持たないため。空のフォルダが残るかは未確認）
- T1 にあっても MediaStore に無い曲（利用者が端末で消した）は、削除済みとして T1 の行だけ消す。マニフェストに残っていれば、次の差分で「足す」になる

入れ直しの後（D14）：

- 事実（確かめた事実）：入れ直すと、前に置いたファイルは前の版のアプリのものとみなされる。アクセスには読み取りの権限が要る。変更と削除には利用者の同意の画面（`createDeleteRequest` などの要求）が要る（review-01 の確認した事実）
- 決定（D14）：入れ直した後の端末は T1 が空なので、前の曲は「自分が置いた」とみなさない。同期では触れない
- 決定（未付番）：Room のデータベースは入れ直しでアプリと一緒に消える前提にする。Android の自動バックアップで T1 が戻ると、持ち主でないファイルを持ち主のものとみなすので、`allowBackup` は無効にする。根拠：D14 と T1 の整合
- 帰結：入れ直し後の初回の同期は、同じパスに insert するので、MediaStore が `名前 (1).拡張子` の名前に変える（facts-android 3-5）。重複した曲が残る。これを整理する手段が下の J15
- 整理の手段（J15。決定）：S5 に「以前の曲の整理」を置く。押したときだけ読み取りの権限を求め、`Music/<アプリ名>/` の下で T1 に無い曲を一覧し、利用者が選んだものを `createDeleteRequest` で消す。同意の画面は 1 回で、選んだ曲をまとめて出す。権限は消すためだけに求め、普段の同期では求めない
- 同意の画面は Activity から起動する。前面サービスと WorkManager からは出せないので、整理は S5 の操作でだけ行う（同意を要する削除を自動の同期へ入れない）

空き容量の確認（N2-4）：

- 同期の前に、差分の「足す」と「置き換える」のサイズの合計を求め、端末の空き容量と比べる。「置き換える」は新しいサイズ全体を数える（安全側）
- 「消す」で空く容量は数えない（安全側。消す側が先に実行されるとは限らないため）
- 確認は同期の前の一括で 1 回行い、曲ごとには行わない（review-05 の基本設計の直し 7）。足りないときは同期を始めず、E3 で終える
- 空きの取得は、共有ストレージのボリュームの UUID で `StorageManager.getAllocatableBytes` を worker スレッドで呼ぶ。事前確認に推奨され、`getUsableSpace` より大きいことが多い（システムがキャッシュを消して空けうるため。数秒かかることがある）。失敗したら `StatFs.getAvailableBytes`（パスのファイルシステムの空き。対象のパスは共有ストレージを明記する）に替える。実際に書ける量との一致は未確認（N5-3 の実機の表に入れる）。足りないときは `ACTION_MANAGE_STORAGE` で利用者に空けてもらう案内を S3 に足す（J21。facts-android 6-1、6-2）
- 余裕として V11 のバイトを加えて比べる。足りないときは同期を始めず、不足分を S3 と通知に表示する（F6-2 の「容量不足」）

### B3-3 m3u8

対応：F4-2、F4-3、F4-4、N3-4、D3、Q4。

形式：

| 項目 | 決め方 |
|---|---|
| 先頭行 | `#EXTM3U` |
| 本体 | 1 行 1 パス。マニフェストのプレイリストの順 |
| 文字コード | UTF-8、BOM なし |
| 改行 | LF |
| 区切り | `/` |
| パス | 相対パス。T1 の `relative_path`（B3-1 で決めた値）。`#EXTINF` は書かない（最初の版。曲名の表示はプレイヤーがタグから読む。J14） |

- 空のプレイリスト（曲が 0）は、`#EXTM3U` の 1 行だけのファイルを書く。ファイルを作らない案は、プレイヤー側にプレイリストが現れず利用者が混乱するため採らない（暫定）
- T1 に無い曲（失敗した曲、まだ置けていない曲）は、m3u8 に書かない。m3u8 は常に「端末に実在する曲だけ」を指す。F3-8 の「完了した曲の分だけ」と同じ考え方

置き場と相対パスの基準：

| 案 | 置き場 | 曲のパス | 利点 | 難点 |
|---|---|---|---|---|
| a | `Music/<アプリ名>/<名前>.m3u8` | `<アーティスト>/<アルバム>/<曲名>.<拡張子>` | 親ディレクトリへ戻る `..` が要らない | アーティストのフォルダと m3u8 が同じ階層に並ぶ |
| b | `Music/<アプリ名>/Playlists/<名前>.m3u8` | `../<アーティスト>/<アルバム>/<曲名>.<拡張子>` | プレイリストが 1 か所に集まる | `..` を含む相対パスの扱いがプレイヤーで違う可能性（未確認） |
| c | 曲と同じ階層 | — | — | 1 つの m3u8 が複数のフォルダの曲を持つので成り立たない |

- 決定（J14）：a を採る。基準は m3u8 を置くフォルダ（`Music/<アプリ名>/`）。根拠：`..` を使わない最も単純な形で、相対パスの解釈が実機で未確認の今、失敗の要因を減らす
- a では、プレイリストの名前とアーティストの名前が同じフォルダに並ぶ。`.m3u8` の拡張子が付くので、フォルダとは衝突しない。アーティスト名が `Playlists` の曲があっても a では問題が起きない（b のときだけ衝突を考える必要があった）

MediaStore の索引（確かめた事実。review-01、ソースの確認。実機は未確認）：

- Android 11 以降の MediaProvider は、MIME が `audio/x-mpegurl` または `application/vnd.apple.mpegurl` のファイルをプレイリストとして索引し、M3U の読み手で中身を展開する
- 索引は MIME で決まる。拡張子 m3u は `audio/x-mpegurl`、m3u8 は `application/vnd.apple.mpegurl` が通常の組だが、本設計は拡張子 m3u8 に MIME `audio/x-mpegurl` を付ける（J18）
- 未確認：相対パスの基準を MediaProvider がどこに取るか（m3u8 のあるフォルダのはず）、各プレイヤー（Poweramp、Musicolet、標準のプレイヤー）での表示。N3-4 と N5-3 の実機の表で確かめる
- 確かめた事実（facts-android 3-1〜3-3。AOSP のソースの確認で、実機は未確認）：`MediaStore.Audio.Media` に playlist の MIME（`application/vnd.apple.mpegurl`、`audio/x-mpegurl`）は insert できない。MediaProvider が MIME から媒体種別を決め、playlist の MIME は `MEDIA_TYPE_PLAYLIST` になる。`Audio.Media` の種別は音声なので、種別が一致せず `IllegalArgumentException` になる。`MediaStore.Files` は、MIME が playlist 種別のときに `Music` と `Movies` が許可の最上位ディレクトリに加わる
- 決定（J18）：m3u8 は `MediaStore.Files` に、`RELATIVE_PATH`＝`Music/<V22>/`、MIME `audio/x-mpegurl`、拡張子 m3u8 のまま insert する。3 案を順に試す方式は採らない（review-05 の基本設計の直し 1）。拡張子 m3u8 での索引は N3-4 の実機の表で確かめる。直接書き込み（`MediaStore.Audio.Playlists` への insert）は使わない（F4-3、Q4）

書き方と同期のたびの作り直し（F4-4）：

1. 順序は「足す・置き換える → m3u8 → 消す」（B2-5）。m3u8 は足す・置き換えるの後に書く。根拠：索引が曲を展開するときに、曲が先に MediaStore にあるほうがよい（索引の順序依存は未確認だが、先に置くのは害がない）。消す対象はどの選んだプレイリストにも入らない曲なので、消す前に書いた m3u8 にも載らず、消すを最後にして曲を失わない
2. プレイリストごとに、新規なら `MediaStore.Files` に insert（`IS_PENDING`＝1 で書き、0 に戻す）、既存なら T2 の URI を開いて `"wt"` で書き直す
3. 書き終えたらメディアスキャンを起こして索引し直す（方法は `IS_PENDING` の切り替えか `MediaScannerConnection.scanFile`。どちらで足りるかは未確認。実機の表へ）
4. T2 の `track_count`、`written_at` を更新する
5. 選択から外れたプレイリスト（マニフェストに無い T2 の行）は、自分の m3u8 を削除し、T2 の行を消す。消した曲は m3u8 の作り直しで外れる（F4-4）

プレイリスト名の使えない文字：

- ファイル名は B3-1 の置き換えの表と同じ規則（`<名前>` の部分に適用）。曲より長い名前が多くはないが、長さの上限も同じ（V23）
- 名前が空、または置き換え後に同じ名前になる 2 つのプレイリストは、` [` ＋ プレイリストの Persistent ID の先頭 V24 文字 ＋ `]` を足す。プレイリストの Persistent ID は B2 がマニフェストに持たせる（B2 への依頼 3 は済）
- iTunes の既定のプレイリスト（ライブラリ、ミュージックなど）の扱いは B2（どれを選択の候補にするか）に任せる

### B3-4 同期状態の表 T1〜T7（Room）

対応：F3-3、F3-5、F3-8、F6-1、F6-3、D9、D11、D14、N1-4。Room のデータベースはアプリの内部領域に置く（F6-3）。表は T1〜T7 で、番号は B3 が採番する。

前提：

- 決定（D11）：端末は 1 台だが、同期状態の表に端末の識別子の列（`device_id`）を持たせる。値は初回の起動で作る乱数 16 バイト（UUID の形で表す）。PC は端末の識別子をペアリングで知る（B4 への依頼 1）
- 暫定、Q9：PC の識別子の列は T1 と T2 には持たない。PC は 1 台（Q9 の未決の初期値）で、T4 の 1 行が PC を指す。PC を複数にするときは T1、T2 に `pc_id` を足す移行が要る。最初の版は足さない（J15）。
- 日時は UTC のエポックのミリ秒（整数）で持つ。表示するときに端末の時刻帯へ直す
- 型は概略（Room の実際の型は実装で決める）。空を許す列に `?` を付ける

| # | 表 | 主キー | 一意制約、索引 |
|---|---|---|---|
| T1 | `placed_tracks`（置いた曲） | （`device_id`、`persistent_id`） | 一意（`device_id`、`relative_path`） |
| T2 | `playlists`（書いたプレイリスト） | （`device_id`、`playlist_id`） | 一意（`device_id`、`relative_path`） |
| T3 | `sync_runs`（同期の実行の記録） | `run_id` | 索引 `started_at` |
| T4 | `pairing`（ペアリング） | `device_id` | なし |
| T5 | `pending_transfers`（取得の途中） | `persistent_id` | なし |
| T6 | `run_issues`（実行ごとの除外と再生不可と失敗） | （`run_id`、`persistent_id`） | 索引 `run_id` |
| T7 | `app_settings`（設定） | `key` | なし |

T1 `placed_tracks`（F3-3、F3-5、D9。この表の行が「自分が置いた」の定義）：

| 列 | 型 | 意味 |
|---|---|---|
| `device_id` | text | 端末の識別子（D11） |
| `persistent_id` | text | iTunes の Persistent ID。曲の同一の判定（F3-2） |
| `relative_path` | text | 置いた相対パス（B3-1 の読み戻した値。`Music/<アプリ名>/` を含まない） |
| `media_uri` | text | MediaStore の content URI |
| `source_size` | integer | 置いた時点のマニフェストのサイズ。変更の検知（F3-2、Q10）に使う |
| `source_modified` | integer | 置いた時点のマニフェストの更新日時。同上 |
| `local_size` | integer | 読み戻した `SIZE`。端末側の実在の確認に使う |
| `state` | text | `placed`、`replacing`（置き換えの途中で止まった） |
| `placed_at` | integer | 置いた日時 |

T2 `playlists`（F4-2、F4-4）：

| 列 | 型 | 意味 |
|---|---|---|
| `device_id` | text | 端末の識別子 |
| `playlist_id` | text | プレイリストの Persistent ID |
| `name` | text | 表示名（元の名前） |
| `relative_path` | text | 書いた m3u8 の相対パス（`Music/<アプリ名>/` を含まない） |
| `media_uri` | text | m3u8 の content URI |
| `track_count` | integer | 書いた曲数 |
| `written_at` | integer | 最後に書いた日時 |

T3 `sync_runs`（S1 の最後の同期、S4 の結果、F6-2）：

| 列 | 型 | 意味 |
|---|---|---|
| `run_id` | integer | 連番 |
| `device_id` | text | 端末の識別子 |
| `trigger` | text | `manual`、`auto`（時間の上限の後の続きは専用の値を持たず、次の同期として扱う。B3-5。review-05 の基本設計の直し 5） |
| `started_at` | integer | 開始 |
| `ended_at` | integer? | 終了。実行中と強制終了の跡は空 |
| `result` | text | `success`、`partial`（一部失敗）、`interrupted`（時間の上限か利用者の中断）、`failed`、`not_started`（容量不足など、始める前に止まった。E3） |
| `error_kind` | text? | B2 の E の番号（F6-2 の原因の種類）。主な 1 つ |
| `added`、`replaced`、`removed`、`failed` | integer | 件数 |
| `bytes_transferred` | integer | 転送したバイト数 |
| `reported` | integer | P4 で PC へ報告済みなら 1、未報告は 0（初期値 0）。再送の対象の判定に使う（review-05 の基本設計の直し 5） |

- 自動で PC に届かず何もせず終わった実行（D13）は T3 に残さず、診断のログに 1 行だけ書く（J10。B2-7）。T3 は古い順に V26 件を超えたら削除する

T4 `pairing`（F2-5、F2-7、N1-1、N1-4、Q9）：

| 列 | 型 | 意味 |
|---|---|---|
| `device_id` | text | 端末の識別子。主キー |
| `pc_id` | text | PC の識別子（ペアリングで PC が渡す。B4 への依頼 1） |
| `pc_name` | text | S1 と S2 に出す PC の名前 |
| `endpoint_lan` | text? | LAN で最後に成功した宛先（mDNS のサービス名） |
| `endpoint_tailscale` | text? | Tailscale で最後に成功した宛先（MagicDNS の名前とポート） |
| `endpoint_usb` | text? | USB テザリングで最後に成功した宛先（`host:port`） |
| `spki_hash` | text | PC の公開鍵（SPKI）のハッシュ。固定（N1-1）の基準。画面では「指紋」と呼び、S2 と S5 に表示する |
| `key_alias` | text | Android Keystore の別名。鍵の本体は表に持たない（N1-4） |
| `paired_at` | integer | ペアリングの日時 |

- B2-7 の「方式ごとの最後の宛先 3 つ」は `endpoint_*` の 3 列を指す。
- 鍵の本体は Android Keystore に置く。鍵の取り込み方（コードから導いた共有の鍵を Keystore の鍵として保存できるか）は B4 の鍵 K の項で決める（B4 への依頼 2）。Room には別名だけ置く
- ペアリングの解除（S5）は、Keystore の鍵の削除と T4 の行の削除を 1 組で行う。T1、T2 は残す（置いた曲は自分のものとして残り、再ペアリング後も差分の基準になる）。別の PC とペアリングし直したとき T1 をどうするかは Q9 の範囲で、暫定：T1 は残し、新しい PC の曲との差分で消えうる（J15）

T5 `pending_transfers`（F3-4、F3-8、N2-2）：

| 列 | 型 | 意味 |
|---|---|---|
| `persistent_id` | text | 取得中の曲 |
| `media_uri` | text | pending の行の URI |
| `expected_size` | integer | マニフェストのサイズ |
| `received_bytes` | integer | 書き込み済みのバイト数。再開の位置（Range の開始） |
| `source_modified` | integer | 取得を始めたときのマニフェストの更新日時。変わっていたら取り直す |
| `started_at` | integer | 開始の日時。期限の判定（V25） |

T6 `run_issues`（F1-10、N3-5、F6-2、S4）：

| 列 | 型 | 意味 |
|---|---|---|
| `run_id` | integer | 実行 |
| `persistent_id` | text | 曲 |
| `title` | text | 画面に出す曲名（N1-6：曲名まで） |
| `kind` | text | `excluded`（PC の除外。F1-10）、`unplayable`（再生不可。N3-5。保護付きは PC の印で転送しない。他は端末が判定し転送する）、`failed`（取得か書き込みの失敗） |
| `error_kind` | text? | E の番号 |

- `excluded` は PC の除外の印、`unplayable` のうち保護付き（`protected`、`m4p`）は PC がマニフェストに付ける印を写し、転送しない。他の再生不可は端末が判定し、転送して一覧に出す（J8。B2-8）

T7 `app_settings`（F3-6、F6-3、S5）：

| 列 | 型 | 意味 |
|---|---|---|
| `key` | text | `auto_sync_enabled`、`log_enabled` など |
| `value` | text | 値の文字列 |

表の外に置くもの：

- ログ（F6-1）：内部領域のファイルに書き、外へ送らない。上限は V9 で、超えたら古い分から消す。内容は PC のログと同じ方針で、鍵とペアリングコードを書かない（N1-6）。曲のパスは端末のログにも書かない（暫定。端末の利用者のものだが、S5 の「ログ」の共有操作で他へ渡しうるため）
- Room の移行：表の変更は Room のスキーマの版で管理する。方針は B1 に任せる

### B3-5 前面サービスと時間の上限

対応：F3-6、F3-7、F3-8、N2-6、D13。

確かめた事実（2026-11-05 取得）：

- Android 14（API 34）以降は、前面サービスの種別をマニフェストで宣言し、その種別の権限（`FOREGROUND_SERVICE` に加えて）も宣言する
- 種別 `dataSync` の権限は `FOREGROUND_SERVICE_DATA_SYNC`。実行時に求める権限は無い
- データのアップロードとダウンロード、取得（fetch）は `dataSync` の用途に挙がっている
- Android 15 を対象にしたアプリは、`BOOT_COMPLETED` の受信から `dataSync` の前面サービスを起動できない（B3 は起動しない。D13 は WorkManager の制約で起動する）
- Android 15 を対象にしたアプリの `dataSync` は、24 時間のうち合計 6 時間で `Service.onTimeout` が呼ばれ、数秒以内に止めないと例外になる。利用者がアプリを前面に出すと計時が戻る（review-01 の確認した事実）

宣言と起動：

| 項目 | 決め方 |
|---|---|
| 種別 | `dataSync` |
| マニフェスト | 前面サービスに `android:foregroundServiceType="dataSync"`。`FOREGROUND_SERVICE` と `FOREGROUND_SERVICE_DATA_SYNC` を宣言 |
| 起動の方法 | 同期の処理は WorkManager のワーカーで行い、ワーカーを前面で動かす（`setForeground`。長い処理の扱い）。専用の `Service` は作らず、前面の種別はワーカーに付ける |
| 前面への昇格 | 手動は S1 の操作から（アプリが前面にある）。自動は WorkManager が起動する |

- 確かめた事実（facts-android 1-4、1-5、1-6。公式の WorkManager の文書）：`ForegroundInfo` の 3 引数版（通知 ID、通知、種別）で種別を渡す。targetSdk 34 以上では、長い処理の Worker に前面サービスの種別が要り、`SystemForegroundService` の `service` 要素を `tools:node="merge"` で上書きして `foregroundServiceType` を書く。文書の例は location と microphone で、`dataSync` を書く例は無い（一部確認。実機で確かめる。B4b の C-10）
- 確かめた事実（facts-android 1-6）：Android 16 以降は、長い処理の Worker（前面サービス）がジョブの割り当て時間を使い切る場合がある。その場合は前面サービスの直接起動か、ユーザー起点のデータ転送ジョブを検討する。B4b の C-10 で確かめ、必要なら前面サービスの直接起動へ替える（review-05 の基本設計の直し 18）
- 確かめた事実（facts-android 2）：`setForeground` は、バックグラウンドからの起動が制限されると `ForegroundServiceStartNotAllowedException`（Android 12 以降。基底は `IllegalStateException`）を投げうる。WorkManager は例外の一覧に無い。try/catch で囲む
- 自動の起動から前面へ上がれないとき（上の例外）は、そのワーカーを何もせず終えて診断のログに 1 行だけ残し（T3 には残さない。J10）、次の自動の起動に任せる。手動の再実行は S1 でできる

時間の上限と中断（F3-8、N2-6）：

1. `onTimeout` に当たったら、現在の曲の書き込みを確実に止める。pending の行と T5 の行を残す（再開の位置が分かる）。止める時間は数秒以内（確かめた事実）なので、止める処理は書き込みの 1 区切りを V27 の長さ以下にして間に合わせる
2. T3 の `result` を `interrupted`、`error_kind` を E7（時間の上限）にする（B2 への依頼 5 は B2-1 と E7 で済）
3. 完了した曲は T1 に入っており、m3u8 は完了した曲だけで書いてある。途中で止まっても、端末の状態は一貫している
4. 続き：次の同期（手動または自動）が、マニフェストを取り直して差分を計算し、T5 の途中の曲から再開する。専用の「続き」の仕組みは持たない（差分の取り直しだけで続きになる。F3-8）
5. 利用者が S1 で手動の同期を押すと、アプリが前面にあるため計時が戻る（確かめた事実）。長い初回の同期は、前面に出して続けると上限を超えにくい。この案内は S3 の注記にする
6. 自動の起動が失敗したときの再試行の間隔は、WorkManager の既定に任せる（V に入れない）

自動のときの「消す」（J9）：

- 「消す件数の確認」（V20）と `library_id` が違うときの確認は画面が要り、自動起動（前面のワーカー）では出せない
- 手動は S3 の前に画面で確認する。自動は「消す」を実行せず飛ばし、足す・置き換える・m3u8 までを行う。終了の通知で S4 へ誘導し、利用者が手動で同期して確認する

通知（F3-7）：

| 項目 | 内容 |
|---|---|
| 持続の通知 | 同期の間、前面サービスの通知を出す。表示：件数（完了／全体）、容量（転送済み／全体）、現在の曲名（N1-6：曲名まで）、進行の棒 |
| 操作 | 「中断」（T3 の `result`＝`interrupted`、利用者の中断）。通知から S3 を開く |
| 終了の通知 | 結果（成功、一部失敗、中断、失敗）と件数。タップで S4 を開く。自動の同期の終了は静かな通知にする |
| 更新の頻度 | V28 の間隔以上は更新しない（通知の更新の過多を避ける） |
| 通知の権限 | Android 13 以降は `POST_NOTIFICATIONS`。拒否されても同期は動く（前面サービスの起動に `POST_NOTIFICATIONS` は不要。通知はドロワーに出ず、タスクマネージャーに現れる。確かめた事実。facts-android 5） |

### B3-6 画面 S1〜S5（Jetpack Compose）

対応：F3-6、F3-7、F6-1、F6-2、F2-5、F2-7、D13、N2-4。見た目は書かない。画面ごとに、表示する項目と操作だけを書く。

| # | 画面 | 役割 |
|---|---|---|
| S1 | 一覧 | 起点。最後の同期と、同期の操作 |
| S2 | ペアリング | 初回の接続と、PC の確認 |
| S3 | 同期の進み具合 | 同期中の状況 |
| S4 | 結果 | 最後の同期の結果と、除外と再生不可の一覧 |
| S5 | 設定 | 自動起動、保存先、解除、ログ |

S1 一覧：

- 表示：ペアリング済みの PC の名前と接続先（T4 の `pc_name`、`endpoint_lan`・`endpoint_tailscale`・`endpoint_usb` の 3 列のうち、最後に成功した 1 つ。3 列とも空なら「未接続」。review-05 の基本設計の直し 17）。最後の同期の日時と結果（T3 の最新の行）。同期するプレイリストの名前と曲数（T2）。ペアリング前は「ペアリングしてください」の表示だけ
- 操作：同期ボタン（押すと S3 へ。ペアリング前は S2 へ）。S4 を開く。S5 を開く
- 同期するプレイリストの選択は PC 側の操作（F5-2）。端末では選ばない

S2 ペアリング（F2-5、D8）：

- 表示：発見した PC の一覧（NSD。PC の名前）。名前の手入力欄（Tailscale の MagicDNS 名や、テザリングの IP。形式の検査は B4 の発見の項）。ペアリングコードの入力欄（6 桁）。接続した後の PC の証明書の指紋
- 操作：PC を選ぶ、名前を入力する、コードを入力して送る。指紋が PC の画面のものと同じか目で確かめ、「一致」を押して T4 に保存する。「違う」を押すと破棄する
- 段の遷移：探索中 → 宛先の確定 → コードの入力 → 指紋の確認 → 完了。「違う」と画面を離れたときは T4 に何も残さず、探索中へ戻る（review-05 の基本設計の直し 17）
- コードの試行の上限（N1-3）に当たったときのメッセージ（コードの作り直しを PC で行うよう案内）。上限の値は V2（B4a の B4-2）
- 端末を入れ直したときは、PC に以前のペアリングが残るため新規を受け付けない。「入れ直したときは PC の W3 で解除してから」の案内を出す（D14）
- 指紋を見比べさせる根拠：コードから導く鍵で中間者を防ぐ手順に、PC の証明書を固定する手順の確認（Q5）を重ねるため

S3 同期の進み具合（F3-7）：

- 表示：段階（マニフェストの取得、差分の計算、容量の確認、転送、m3u8 の書き出し）。件数（完了／全体）、容量（転送済み／全体）、現在の曲名。容量不足のときは不足分（N2-4）。時間の上限が近い場合の注記（B3-5 の 5）
- 操作：中断。中断した後は S4 へ

S4 結果（F6-2、F1-10、N3-5）：

- 表示：最後の同期の結果（T3）：結果の区分、件数、容量、原因の種類。除外の曲の一覧（T6 の `excluded`）。再生不可の曲の一覧（`unplayable`）。失敗した曲の一覧（`failed`）。各行は曲名と原因の種類だけ（N1-6）
- 操作：もう一度同期する（S3 へ）。過去の実行の一覧から別の実行を選ぶ（T3 の件数の範囲。V26）

S5 設定（F3-6、F6-1、F2-7）：

| 項目 | 表示 | 操作 |
|---|---|---|
| 自動起動 | 状態（有効、無効）。条件の説明（従量制でないネットワークにつながったとき。PC に届かなければ何もしない。D13） | 切り替え（T7 の `auto_sync_enabled`。WorkManager の登録と解除） |
| 保存先 | `Music/<アプリ名>/` の表示 | なし（変更できない。Q7） |
| ペアリング | PC の名前と指紋 | 解除（Keystore の鍵と T4 の行を消す。確認の画面を出す） |
| 以前の曲の整理 | 入れ直した後に T1 に無い曲の数 | 読み取りの権限を求め、一覧から選んで削除（B3-2 の J15） |
| ログ | 大きさ | 表示、共有、消去（F6-1） |
| ライセンス | OSS の一覧（N4-4） | 表示 |

### B3-7 権限の一覧と Android の最低版

対応：N1-4、N3-3、F4-5、D13。

| 権限 | 種類 | 目的 | 備考 |
|---|---|---|---|
| `INTERNET` | 通常（付与される） | PC への接続（LAN、Tailscale、テザリング） | — |
| `ACCESS_NETWORK_STATE` | 通常 | 従量制でないネットワークの判定、PC への到達の確認の前提（D13） | — |
| `POST_NOTIFICATIONS` | 実行時（Android 13 以降） | 進み具合と結果の通知（F3-7） | 拒否でも同期は動く（B3-5） |
| `FOREGROUND_SERVICE` | 通常 | 前面サービス | 確かめた事実 |
| `FOREGROUND_SERVICE_DATA_SYNC` | 通常（Android 14 以降の宣言） | 種別 `dataSync` | 確かめた事実。実行時の権限は無い |
| `CHANGE_WIFI_MULTICAST_STATE` | 通常 | 発見の間だけ `MulticastLock` を取るため。T 拡張 7 より前の端末は、mDNS を受けるのに `MulticastLock` が要る（facts-android 7-1。権限名は WifiManager の文書で、今回は未取得）。発見の停止で放す（review-05 の基本設計の直し 4） | — |
| `READ_MEDIA_AUDIO`（Android 13 以降）／`READ_EXTERNAL_STORAGE`（12 以下。`maxSdkVersion` 32） | 実行時 | 通常は求めない。S5 の「以前の曲の整理」を使うときだけ求める | B3-2 の J15。review-05 の基本設計の直し 17 |

求めない権限と、その理由：

| 権限 | 求めない理由 |
|---|---|
| 読み取り（`READ_MEDIA_AUDIO`、`READ_EXTERNAL_STORAGE`）を同期では求めない | 自分が置いたファイルの読み取りと変更に権限は不要（確かめた事実）。他のアプリが置いた曲に触れないこと（F4-5）の裏づけにもなる。入れ直し後の整理だけが例外（J15） |
| `WRITE_EXTERNAL_STORAGE` | Android 11 以降は不要（確かめた事実：公式文書は Android 10 以降は自分のファイルに権限が要らず、`WRITE_EXTERNAL_STORAGE` を `maxSdkVersion` 29 でしか宣言していない） |
| `MANAGE_EXTERNAL_STORAGE`（全ファイル） | MediaStore 経由で足りる。広すぎる権限を避ける |
| `MANAGE_MEDIA` | 同意の画面を省くための権限（Android 12 以降。確かめた事実）。D14 の「消すときだけ同意の画面を出す」と合わない。求めない |
| 位置情報（`ACCESS_FINE_LOCATION` など） | SSID を見ない（D13） |
| `ACCESS_WIFI_STATE` | SSID を見ない（D13）。ネットワークの種別だけを使う |
| `ACCESS_LOCAL_NETWORK` | Android 17（API 37）以降で targetSdk 37 以上が対象の実行時権限（NsdManager も対象）。最初の版の targetSdk は 36 以下（J21）で、`INTERNET` により暗黙に許可されるため宣言しない。Android 16 の opt-in（`NEARBY_WIFI_DEVICES`）は非推奨（facts-android 7-2。review-05 の基本設計の直し 4） |
| `RECEIVE_BOOT_COMPLETED` | WorkManager が再起動後の登録を自分で扱う。端末の再起動で自動起動を再登録するために、アプリ側の受信は要らない（WorkManager の挙動。未確認） |

Android の最低版 11 の根拠（N3-3）：

- Scoped storage が全アプリに強制される最初の版が 11。MediaStore 経由の書き込みと、自分のファイルの権限なしでの扱いが前提（確かめた事実：Android 10 以降は権限不要。10 では `requestLegacyExternalStorage` の逃げ道があり、条件が増えるので 11 から）
- m3u と m3u8 を MediaStore のプレイリストとして索引する MediaProvider の挙動は Android 11 以降（review-01 のソースの確認）
- `createDeleteRequest` などの要求の API は Android 11（API 30）で入った（D14 の整理で使う。API の版は未確認だが、公式文書が Android 11 を前提にした節の中で説明している）

### B3 の Q の結論

| Q | 結論 | 書いた項 |
|---|---|---|
| Q4 | 暫定：m3u8 を置いて索引に任せる。`MediaStore.Audio.Playlists` への直接書き込みは使わない。置き場は `Music/<アプリ名>/` の直下（J14）、相対パスは `..` なし、UTF-8、LF、`#EXTINF` なし。insert は `MediaStore.Files` に `Music/<V22>/`、MIME `audio/x-mpegurl`、拡張子 m3u8（J18。`Audio.Media` には insert できない）。拡張子 m3u8 での索引、相対パスの解釈、各プレイヤーの表示は実機で確かめる（N5-3） | B3-3 |
| Q7 | 暫定：`Music/<アプリ名>/<アーティスト>/<アルバム>/` に MediaStore 経由で置く。SAF と SD カードは最初の版では扱わない。入れ直した後は D14 に従い、T1 に無い曲は触れない。整理の手段は S5 の「以前の曲の整理」（J15） | B3-1、B3-2 |

### B3 の要判断

案と推奨は review-03 を正とする。J7〜J15 はすべて先生が推奨（a）で決定した（2026-11-05）。第 3 稿で J18（m3u8 の置き場。a：`MediaStore.Files`）と J21（残りの暫定の採用。a）を足す。J16、J17、J19、J20 は B3 に無関係。

| B3 の旧 J | review-03 | 結論 |
|---|---|---|
| J1（名前の変更への追従） | J15 | a：しない |
| J2（追記の書き込みが使えないとき） | J15 | a：その曲だけ取り直す |
| J3（入れ直し後の整理） | J15 | a：S5 に「以前の曲の整理」を置く |
| J4（消す分の容量） | J15 | a：数えない |
| J5（`#EXTINF`） | J14 | a：書かない |
| J6（m3u8 の置き場） | J14 | a：`Music/<アプリ名>/` の直下 |
| J7（m3u8 を insert できないとき） | J14 → J18 | 第 3 稿で置き換え。a：`MediaStore.Files` に insert（3 案の順は廃止） |
| J8（PC を複数にする備え） | J15 | a：`pc_id` を足さない |
| J9（「消す」の確認、自動の記録） | J10（記録）、J9（消す） | a：T3 に残さない、自動は消すを飛ばす |
| J10（`setForeground` の細部） | J15 | a：基本設計の後半で確かめる |

### B3 の他の節への依頼

B1 への依頼（使う V。値は B1-7 を正とする）：

| V | 内容 | 使う項 |
|---|---|---|
| V22 | 保存先のフォルダ名 | B3-1、B3-6 |
| V23 | ファイル名・フォルダ名の 1 要素の上限 | B3-1、B3-3 |
| V24 | 衝突の接尾辞の Persistent ID の文字数 | B3-1、B3-3 |
| V25 | 取得の途中（T5）の期限 | B3-2、B3-4 |
| V11 | 空き容量の余裕 | B3-2 |
| V26 | T3 に残す実行の件数 | B3-4、B3-6 |
| V9 | 端末のログの上限 | B3-4 |
| V27 | 書き込みの 1 区切りの上限 | B3-5 |
| V28 | 通知の更新の最小の間隔 | B3-5 |
| V20 | 消す件数の確認の閾値 | B3-5 |
| V2 | ペアリングの試行の上限 | B3-6 |

B2 への依頼：

- 依頼 2（Album Artist）、3（プレイリストの Persistent ID と名前）、5（E の種類）は B2-1 と E7 で満たされている（済）
- 依頼 1（相対パスの候補）は J7 で不要になった（マニフェストは材料だけ）
- 依頼 4：保護付き（`protected`、`m4p`）と除外の印、一覧用の曲名をマニフェストか結果に持たせる。保護付きは転送の対象に含めない（J8。B3-4）

B4 への依頼：

1. ペアリングで、端末の識別子（乱数 16 バイト。UUID の形で表す）と PC の識別子を交換する（T4 の `device_id`、`pc_id`）。PC は端末の識別子を設定に持つ（D11）
2. 共有の鍵を、Android Keystore の鍵（別名）として保存できる形にする。コードから導いた鍵をそのまま取り込む方式（Keystore への取り込みの可否）を K の項で決める。端末は T4 に別名だけ持つ
3. 端末の API の要求：マニフェストの取得（P1）、曲の取得（P2。Range。Persistent ID だけで指定。パスを受け取らない。N1-7）、ペアリング（P5、P6）。曲の取得は途中からの再開に、マニフェストの `source_modified` の変更の検知（T5）が使えるよう、曲の応答にサイズと更新日時を含める
4. 発見：NSD で見つける PC の広告の名前と、手入力の形式（S2）
5. B4b の C-2 を「`MediaStore.Files` への insert と索引の実機確認（m3u8 の拡張子で）」にする（B3-3、J18）。C-10、C-12 も B3-3、B3-5 の確認項目として参照する

### B3 の確かめた事実

- 前面サービスの種別：Android 14 以降は種別と、その種別の権限の宣言が要る。`dataSync` の権限は `FOREGROUND_SERVICE_DATA_SYNC`、実行時の前提は無い。Android 15 を対象にしたアプリは `BOOT_COMPLETED` の受信から `dataSync` の前面サービスを起動できない（2026-11-05 取得）。出典：Android の Foreground service types の文書（developer.android.com の「Foreground service types are required」）
- 共有ストレージのメディア：Android 10 以降は、自分が持ち主のメディアファイルの読み取りと変更にストレージ関連の権限が要らない。他のアプリのファイルには `READ_MEDIA_AUDIO`（13 以降）か `READ_EXTERNAL_STORAGE`。`WRITE_EXTERNAL_STORAGE` は `maxSdkVersion` 29 で宣言する例。入れ直した後は前のファイルが前のアプリのものとみなされ、読み取りの権限が要る。`IS_PENDING`＝1 の間は自分のアプリだけが見られる。`RELATIVE_PATH` は保存先のヒント。`update` で `RELATIVE_PATH` と `DISPLAY_NAME` を変えるとファイルを移せる。`MANAGE_MEDIA`（Android 12 以降）は、変更と削除の同意の画面を省ける特別な権限（2026-11-05 取得）。出典：Android の「Access media files from shared storage」の文書
- Android 15 を対象にしたアプリの `dataSync` は 24 時間で合計 6 時間。`Service.onTimeout` の後に数秒以内に止める。利用者がアプリを前面に出すと計時が戻る（review-01 の確認した事実。2026-11-05 取得）。出典：Android 15 の動作の変更
- MediaStore 経由で作ったファイルは、入れ直すと別のアプリの扱いになり、変更と削除に `createDeleteRequest` などの同意の画面が要る（review-01。2026-11-05 取得）
- m3u と m3u8 の MediaProvider の索引：MIME `audio/x-mpegurl` と `application/vnd.apple.mpegurl` をプレイリストとして索引し、M3U の読み手で展開する（ソースで確認。review-01 の実物の確認 2。実機は未確認）
- WorkManager の `Constraints` に SSID は無い（review-01。2026-11-05 取得）
- 以下は `reviews/facts-android-2026-11-05.md`（developer.android.com の公式文書と AOSP の MediaProvider の android16-release ブランチ。取得日 2026-11-05。実機は未確認）から写す
  - 1-1 定期実行：最小の繰り返し間隔は 15 分。flex の窓は `repeatInterval - flexInterval` から間隔の終わりまで（出典：Define work requests）
  - 1-2 `NetworkType.UNMETERED`：制約は全て満たされたときだけ実行される。定期実行は制約が満たされるまで、その回が遅れるか飛ばされる場合がある（同上）
  - 1-3 `Constraints` は NetworkType、BatteryNotLow、RequiresCharging、DeviceIdle、StorageNotLow の 5 つ。「ネットワークにつながった瞬間に起動する」制約は無い（同上）
  - 1-4 〜 1-6 長い処理の Worker：B3-5 に記した（出典：Support for long-running workers）。10 分を超えて動かせる。通知から Worker を取り消す `PendingIntent` を作れる
  - 1-7 Android 15 以降の `dataSync` の時間制限（一部）：targetSdk 35 以上で実行時間の制限がある、という記述までを確認。時間そのもの（24 時間に 6 時間）は review-01 の確認した事実（出典：Changes to foreground services）
  - 2 バックグラウンドからの前面サービスの起動：B3-5 に記した（出典：Restrictions on starting a foreground service from the background、Define work requests）
  - 3-1〜3-3 `Audio.Media` に playlist の MIME は insert できない。`Files` は playlist 種別の MIME のとき `Music` に置ける。`Audio.Media` の許可ディレクトリは Alarms、Audiobooks、Music、Notifications、Podcasts、Recordings、Ringtones（出典：MediaProvider.java の `ensureFileColumns`、MimeUtils.java の `isPlaylistMimeType`）
  - 3-4 IS_PENDING の行を `"wa"` で開けるか（一部）：公式文書は `"w"` の例のみ。pending の行は所有者だけが開ける。`"wa"` を拒否するコードは見つからなかった。実機は未確認（出典：Access media files from shared storage、MediaProvider.java）
  - 3-5 同名の insert：`FileUtils.buildUniqueFile` により `名前 (1).拡張子` の形になる（出典：MediaProvider.java、FileUtils.java）
  - 4 自分のメディアの削除に同意が不要か（一部）：明記は無く、他のアプリ所有分の記述からの読み取り（出典：Access media files from shared storage）
  - 5 `POST_NOTIFICATIONS` 拒否時：前面サービスの通知はドロワーに出ず、タスクマネージャーに出る。起動に権限は不要で、通知の提示は必須（出典：Notification runtime permission）
  - 6-1 `StatFs.getAvailableBytes`（一部）：statvfs の `f_bavail` に対応する。共有ストレージを指すかは渡すパスで決まる（出典：StatFs の reference）
  - 6-2 `getAllocatableBytes` は事前確認に最適。足りないときは `ACTION_MANAGE_STORAGE`。worker スレッドで呼ぶ（出典：StorageManager の reference）
  - 7-1 NSD（一部）：T 拡張 7 より前は mDNS の受信に `MulticastLock` が要る。権限名 `CHANGE_WIFI_MULTICAST_STATE` は WifiManager の文書にあり、今回は未取得（出典：NsdManager の reference、Use network service discovery）
  - 7-2 `ACCESS_LOCAL_NETWORK`：Android 17 以降、targetSdk 37 以上で必須。NsdManager も対象。targetSdk 36 以下は `INTERNET` で暗黙に許可（出典：Local network permission）
- 未確認のまま残るもの（実機または公式文書での確認が要る。N5-3 の実機の表に入れる）：拡張子 m3u8 での索引、相対パスの基準の解釈とプレイヤーの表示、追記の書き込みモード（`"wa"`）、フォルダ名の長さの上限、`SystemForegroundService` の上書きに `dataSync` を書く記述の実機での動作、`getAllocatableBytes` が実際に書ける量と一致するか、`MediaScannerConnection` の要否、自分のファイルの削除に同意が不要なこと、`CHANGE_WIFI_MULTICAST_STATE` の権限名（WifiManager の文書で未取得）
