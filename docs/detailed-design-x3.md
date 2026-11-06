# playlist-sync-fox 詳細設計 X3 shared の同期（M1）

- 索引：detailed-design.md（X1）
- 稿：第 5 稿（2026-11-06。review-13 を反映）
- 参照：X2 は detailed-design-x2.md、X4 は detailed-design-x4.md、X5 は detailed-design-x5.md、X6 は detailed-design-x6.md、X7 は detailed-design-x7.md。引き受ける基本設計は B2-4・B2-5・B2-8 が basic-design-b2.md、B3-1・B3-3 が basic-design-b3.md、値は basic-design.md の B1-7

## X3 shared の同期

### X3 の前提

- 範囲は M1（shared）のうち、マニフェストの型、差分、保存先のパス、m3u8、E と結果の型。入出力は純粋な関数にそろえる（N5-1）。ファイル、通信、時計、乱数、DB に触れない
- 暗号は `CryptoProvider` を引数で受ける（X4-1）。この節で使うのは SHA-256 だけ
- 値は V の番号で参照する。単位は B1-7 のとおり
- Kotlin の型名と項目名は設計上の名前。綴りと文法は実装で決める

### X3-1 マニフェストの型

対応：B2-4、F3-1、F3-2、Q10。

直列化の規則（kotlinx.serialization の JSON）：

| 規則 | 内容 |
|---|---|
| 名前 | JSON のキーは B2-4 の snake_case。型の項目名は camelCase とし、`SerialName` で対応させる |
| 未知のキー | 読み捨てる。`content_hash` のような空の項目の追加で `format_version` を増やさないため（B2-4） |
| 空の項目 | 「空を許す」項目は、型では null を許し、JSON では書かない（読むときは無い＝null）。空文字列と 0 を空の意味に使わない |
| 日時 | `generated_at`、`xml_modified_at` は UTC の ISO 8601 文字列（ミリ秒まで、末尾 `Z`）。`modified_at` は B2-4 のとおり UTC のエポックミリ秒の整数 |
| 整数 | `size`、`modified_at` は 64 ビット。`track_number`、`disc_number`、`duration_ms` は 32 ビット |
| 識別子 | `persistent_id` は大文字の 16 桁の 16 進。境界（読み込み）で形を検査し、合わなければ「壊れたマニフェスト」にする（パスの材料に入るため） |

読み込みの手順（M1。入力は本文の文字列）：

1. 先に `format_version` だけを読む。読めなければ「壊れたマニフェスト」
2. M1 が知る版の範囲に入らなければ「版が合わない」を返す。E8 にしない（B2-4）。端末は更新の案内を表示する（B3-6）
3. 全体を読む。型が合わない、必須の項目が無い、`persistent_id` の形が違うものは「壊れたマニフェスト」
4. `tracks` に同じ `persistent_id` が 2 つ以上あるときは、最初の 1 つを残し、残りを落として件数を結果に持つ（同期は止めない）
5. 結果は「読めた（マニフェストと落とした件数）／版が合わない（受け取った版）／壊れた（理由の文字列）」の 3 種

最上位（`Manifest`）：

| 項目 | 型 | 必須 | 意味 |
|---|---|---|---|
| `format_version` | 整数 | 必須 | 版。初版の値は V38 |
| `generated_at` | 日時 | 必須 | 作成の時刻 |
| `library_id` | 文字列 | 必須 | B2-4 のとおり |
| `xml_modified_at` | 日時 | 必須 | 読み込んだ XML の更新日時 |
| `content_digest` | 文字列 | 必須 | 下の計算の結果。表記は V40 |
| `tracks` | `ManifestTrack` の並び | 必須 | 空の並びを許す |
| `playlists` | `ManifestPlaylist` の並び | 必須 | 空の並びを許す |
| `unresolved_playlists` | `UnresolvedPlaylist` の並び | 必須 | 空の並びを許す |
| `excluded` | `ExcludedTrack` の並び | 必須 | 空の並びを許す |

`ManifestTrack`：

| 項目 | 型 | 必須 | 意味 |
|---|---|---|---|
| `persistent_id` | 文字列 | 必須 | 曲の鍵 |
| `size` | 整数（64） | 必須 | 実ファイルのバイト数 |
| `modified_at` | 整数（64） | 必須 | 実ファイルの更新日時 |
| `file_name` | 文字列 | 必須 | 元のファイル名。保存先の規則では使わず、拡張子が空のときの診断にだけ使う |
| `extension` | 文字列 | 必須 | 小文字の拡張子（ドットなし）。拡張子が無いファイルは空文字列 |
| `name`、`artist`、`album`、`album_artist` | 文字列 | 任意 | メタデータ |
| `track_number`、`disc_number` | 整数（32） | 任意 | 保存先の規則では使わない（B3-1）。表示用 |
| `duration_ms` | 整数（32） | 任意 | 長さ。保存先の規則では使わない |
| `kind` | 文字列 | 任意 | `Kind` の値。実物の値の例は MPEG、Apple Lossless、AAC、購入した AAC、WAV（B2 の実物の確認）。`unsupported_format` の判定（X3-5）には使わない |
| `protected` | 真偽 | 必須 | 無ければ偽として PC が書く |
| `content_hash` | 文字列 | 任意 | 将来用。最初の版では書かない（B2-4）。読むだけ読み捨てる |

- 拡張子が空でない場合に限り、`extension` は保存先のパスの材料になる。`file_name` の拡張子と `extension` が食い違うときは `extension` を使う
- B2-4 の材料のうち `track_number`、`disc_number` は B3-1 の規則（`<アーティスト>/<アルバム>/<曲名>.<拡張子>`）が使わない。保存先の材料の一覧は X3-3 のとおり

`ManifestPlaylist`：`persistent_id`（文字列、必須）、`name`（文字列、必須、空文字列を許す）、`track_ids`（`persistent_id` の並び、必須。重複を許す。`tracks` に無い ID を含まない。含むときは読み込みの手順 3 で壊れたとする）。

`UnresolvedPlaylist`：`persistent_id`（必須）、`last_known_name`（必須）。

`ExcludedTrack`：

| 項目 | 型 | 必須 | 意味 |
|---|---|---|---|
| `name`、`artist` | 文字列 | 任意 | 曲名、アーティスト。パスは載せない（N1-6） |
| `reason` | 文字列（列挙） | 必須 | `no_location`（場所なし）、`undecodable`（復号不能）、`not_found`（実在しない）、`unreadable`（読めない）、`no_id`（ID 無し）。保護は含めない（基本設計の直し 10） |

`content_digest` の計算（M1。`CryptoProvider` の SHA-256 を受け取る）：

1. 入力は `tracks`、`playlists`、`unresolved_playlists` の 3 つの一覧。`generated_at`、`xml_modified_at`、`library_id`、`excluded` は含めない（J21（a））
2. 値の符号化：文字列は「UTF-8 の長さ（4 バイト、ビッグエンディアン）＋ UTF-8 の列」、整数は 8 バイトのビッグエンディアンの符号付き、真偽は 1 バイト（0 か 1）、任意の項目は「無し＝0x00、有り＝0x01 ＋ 値」
3. 先頭に固定の文字列（V39）と `format_version` を置く
4. `tracks` は `persistent_id` の昇順（文字列の辞書順）に並べ、件数（4 バイト）の後に、各トラックの全項目を表の順（`content_hash` を含む）で符号化する
5. `playlists` は `persistent_id` の昇順に並べ、件数の後に、各プレイリストの `persistent_id`、`name`、`track_ids` の件数、`track_ids` を元の順のまま符号化する（順序は m3u8 に効く）
6. `unresolved_playlists` は `persistent_id` の昇順に並べ、件数の後に、`persistent_id`、`last_known_name` を符号化する
7. 上の全体の SHA-256 を、小文字の 16 進 64 桁の文字列にする
- 同じ内容なら、一覧の元の並びに関係なく同じ値になる。`tracks` と `playlists` の `persistent_id` 重複は読み込みの手順 4 で除いた後に計算する
- 端末は受け取った `content_digest` を検算せず、そのまま使う（境界の検査は形式だけ。PC と同じ関数を使うことはテストで保つ。X3-6）

### X3-2 差分の計算

対応：B2-5、F3-3、F3-5、N2-4、N2-5、D9、J9。

関数 `computeDiff`（M1。純粋）：

| 項目 | 内容 |
|---|---|
| 入力 1 | `ManifestTrack` の並び（マニフェストの `tracks`） |
| 入力 2 | `SyncRecord` の並び（端末が置いた曲）。項目は `persistent_id`、`size`、`modified_at`、`placedPath`。M1 の型として定義し、Room の行とは M4 が写す。T1 の列との対応は `size`＝`source_size`、`modified_at`＝`source_modified`、`placedPath`＝`relative_path`（`persistent_id` は同名）。値の変換はしない（マニフェストの値のまま。X6-1 に同じ対応を書く） |
| 入力 3 | `presentIds`：`SyncRecord` のうち、置いた場所にファイルが今ある曲の `persistent_id` の集合（M4 が調べて渡す）。置き換えの途中で止まり pending のまま残った曲（T1 の `state`＝`replacing`）も「ある」に数える（X6-3 の 4）。差分の入力に `state` は渡さない。置き換えの途中の曲は T1 の `size`・`modified_at` が古い値のままなので、判定 4 で「置き換える」になる |
| 入力 4 | `manifestLibraryId` と `recordedLibraryId`（後者は無い＝初回で null） |
| 入力 5 | 消す件数の確認の閾値（V20） |
| 入力 6 | `unresolvedPlaylists`：マニフェストの `unresolved_playlists`（選択した ID が XML に無いプレイリスト。J24） |
| 出力 | `Diff`（下の表） |

`Diff`：

| 項目 | 型 | 意味 |
|---|---|---|
| `toAdd` | `ManifestTrack` の並び | 足す |
| `toReplace` | `ManifestTrack` の並び | 置き換える。置き場所は `SyncRecord` の `placedPath` のまま |
| `toRemove` | `SyncRecord` の並び | 消す。`removalGate` が `Allowed` 以外でも計算して返す（J25） |
| `unchangedCount` | 整数 | 変更なしの件数 |
| `unplayable` | `ManifestTrack` の並び | 保護付き。転送しない（B2-8 の規則 1） |
| `removalGate` | 3 種 | 消すの扱い（下） |

判定の順（トラックごと。入力 1 を `persistent_id` の昇順に並べて 1 件ずつ）：

1. 保護付き（`protected` が真、または `extension` が `m4p`）なら `unplayable` に入れ、以降の判定をしない
2. `SyncRecord` に `persistent_id` が無ければ「足す」
3. `SyncRecord` にあり、`presentIds` に無ければ「足す」（ファイルが無い場合の補充。置き換えより先に判定する）
4. `size` か `modified_at` のどちらかが `SyncRecord` と違えば「置き換える」
5. それ以外は「変更なし」
6. 全トラックの後で、`SyncRecord` のうち `persistent_id` が入力 1 のどのトラックにも無いものを「消す」にする（保護付きで `unplayable` に入れたものも、入力 1 にあるので消さない）
- 出力の並びは `persistent_id` の昇順（決定的にするため。J21（a））
- 端末にあって `SyncRecord` に無いファイルは入力に現れず、判定に入らない（D9、F3-5）
- 入れ直しの後は `SyncRecord` が空なので、「消す」は空になり、「足す」が衝突を X3-3 で解く（D14）

`removalGate`（B2-5 の最後の 2 項目と J9）：

| 値 | 条件 | 端末の扱い（M4。X6-3） |
|---|---|---|
| `Allowed` | 下の 2 つに当たらない | 消す |
| `LibraryChanged` | `recordedLibraryId` が null でなく、`manifestLibraryId` と違い、`toRemove` が 1 件以上 | `toRemove` は計算して返す（J25）。手動は確認し、承認後に実行して、T3 の `library_id` を新しい値にする（記録の更新は M4）。自動は飛ばして通知 |
| `NeedsConfirmation` | `toRemove` が 1 件以上で、かつ次のいずれかに当たる：件数が V20 を超える、`SyncRecord` の全部が `toRemove` に当たる、`unresolvedPlaylists` が 1 件以上（J24） | `toRemove` は計算して返す。手動は確認、自動は飛ばして通知 |
- `LibraryChanged` と `NeedsConfirmation` の両方に当たるときは `LibraryChanged` を返す
- `recordedLibraryId` が違っても `toRemove` が 0 件なら `LibraryChanged` にも `NeedsConfirmation` にも当たらず、`Allowed` になる。M4 は T3 の `library_id` を確認なしで新しい値にする（B2-5。J25）
- 未解決のプレイリストの曲は、`SyncRecord` にプレイリストとの対応が無く、「消さない」を曲ごとには実装できない。未解決のプレイリストが 1 件でもあり、かつ `toRemove` が 1 件以上なら、消すを確認の扱いにする（手動は確認、自動は飛ばす。J24。2026-11-06）。`toRemove` が 0 件なら確認の対象が無いので `Allowed`
- 確認後の実行は M4 が行う。M4 は P1 を取り直して差分を計算し直し、`toRemove` の件数が承認した件数以内なら実行する（X6-3 の 6）。M1 は確認の結果を持たない

関数 `requiredBytes`：入力は `Diff`。出力は `toAdd` と `toReplace` の `size` の合計（置き換えは新しい `size`。消す分は引かない。B2-5）。空き容量との比較と V11 の余裕の加算は M4（X6-2）。合計は 64 ビットで足し、桁あふれは考えない（曲数 × 数百 MB で足りる）。

差分が空のとき：`toAdd`、`toReplace`、`toRemove` がすべて空。M1 は `Diff` が空かの判定 `isEmpty` を持つ。差分が空で、前回の完了した同期（`content_digest` が NULL でない最新の T3 の行）が `success` であり、かつ `content_digest` がその行と同じなら、m3u8 の書き出しと消す処理を省き、T3 だけ記録する（手動も同じ。N2-5、J37）。この判断は M4（X6-3）。

### X3-3 保存先のパスの関数

対応：B3-1、F4-1、J7。N5-1 の対象。

関数の表（M1。すべて純粋）：

| 関数 | 入力 | 出力 | 副作用 |
|---|---|---|---|
| `sanitizeElement` | 文字列、空のときの代わりの文字列 | 置き換え後の 1 要素 | なし |
| `truncateToBytes` | 文字列、上限のバイト数 | 文字の境界で切った文字列 | なし |
| `buildTrackCandidate` | `ManifestTrack` | `PathParts`（フォルダ 2 要素、ステム、拡張子）。衝突の前の候補 | なし |
| `assemblePath` | `PathParts`、接尾辞（空を許す） | `/` 区切りの相対パス（`Music/<アプリ名>/` を含まない） | なし |
| `resolveTrackPaths` | 置く対象の `ManifestTrack` の並び、使用中のパスの集合、V24、V23 | `persistent_id` から相対パスへの対応 | なし |
| `mimeTypeOf` | 小文字の拡張子 | MIME の文字列、または無し | なし |

`sanitizeElement` の手順（B3-1 の表の順。1 要素ごと）：

1. `/ \ : * ? " < > |` を `_` に置き換える
2. 制御文字（U+0000〜U+001F、U+007F）と、行の区切りになりうる U+0085、U+2028、U+2029 を削除する（基本設計の直し 11）
3. 先頭と末尾の空白（Unicode の White_Space）と、末尾の `.` を削除する。末尾に空白と `.` が混ざるときは、どちらも残らなくなるまで繰り返す
4. 結果が `.` だけ、`..` だけなら `_` にする（手順 3 で末尾の `.` は消えるので、実質は手順 3 の前に判定する。X3-6 の試料）
5. 結果が空なら、代わりの文字列にする
6. 結果の先頭が `#` なら、先頭の `#` を `_` に置き換える（m3u8 のコメント行を避ける。基本設計の直し 11）
- 日本語、記号、絵文字、結合文字は残す。正規化はしない（B3-1）
- 文字を足さない、区切りを足さない（B3-1）
- 手順 4 は手順 3 の前の値と後の値のどちらかが `.` か `..` に当たれば `_` にする

`truncateToBytes`：UTF-8 のバイト数が上限以内ならそのまま。超えるときは、コードポイントの境界で、上限以内に収まる最長の前方の部分を返す。結合文字の途中や書記素の途中では切れうる（書記素の境界は扱わない。X3 の置いた値）。切った後に `sanitizeElement` の手順 3 の末尾の空白と `.` の削除をもう一度行い、空になれば代わりの文字列にする。

`buildTrackCandidate` の手順：

1. アーティスト：`album_artist` が空でなければそれ、空なら `artist`、それも空なら `Unknown Artist`。「空」は null か空文字列（空白だけの値は空でない。選んだ後に手順 2 で空になれば `Unknown Artist`）
2. アルバムは `album`、空なら `Unknown Album`。曲名は `name`、空なら `Untitled`
3. 3 つの要素を `sanitizeElement`（代わりの文字列は上の固定の文字列）にかけ、フォルダ 2 つは `truncateToBytes`（上限 V23）にかける
4. 拡張子は `sanitizeElement`（代わりは空）。結果が空でなければ「`.` ＋ 拡張子」を末尾の部分とする
5. 曲名のステムの上限は V23 から、末尾の部分のバイト数と、接尾辞を足す場合の接尾辞のバイト数を引いた値。接尾辞なしの候補は接尾辞を 0 バイトとして計算する
6. 末尾の部分だけで V23 を超える（異常な拡張子）ときは、拡張子を V23 から 1 を引いた値まで切る（`truncateToBytes`）。起こり得ないが、無限の繰り返しと負の上限を避ける

`assemblePath`：`<アーティスト>/<アルバム>/<ステム><接尾辞>.<拡張子>`。接尾辞は ` [` ＋ `persistent_id` の先頭の n 文字 ＋ `]`。ステムは、接尾辞があるときは接尾辞の分のバイト数を引いた上限で、置換前の曲名から作り直す（接尾辞の有無でステムが同じ値から切られるようにする）。

`resolveTrackPaths` の手順（J7。端末の計算が 1 か所になる）：

1. 入力の「使用中のパスの集合」は、`SyncRecord` の `placedPath` のうち今回の対象に含まれない曲のもの、および M4 が MediaStore の問い合わせで知った既存のパス。パスの比較は大文字小文字を区別しないで行い、使用中の集合と接尾辞なしの候補は小文字にそろえて比べる（FAT 系に備える。基本設計の直し 12）。返すパスは元の綴りのまま
2. 置く対象のうち、すでに `SyncRecord` を持つ曲（置き換え）は、パスを再計算せず `placedPath` をそのまま返す。使用中の集合にも入れる（B3-1 の「動かさない」）
3. 残り（足す）を `persistent_id` の昇順に処理する
4. 各曲の接尾辞なしの候補を作る。使用中の集合に無ければ、そのパスで確定し、集合に足す
5. 使用中なら、接尾辞を V24 文字で作り直す。無ければ確定
6. それでも使用中なら、接尾辞の文字数を `persistent_id` の全文（16 文字）にして再計算し、そのパスをそのまま返す（「解けない」の戻りは持たない。J42）。使用中の集合に MediaStore の既存のパスが入るため、入れ直しを重ねると全文でも衝突しうる。その場合は MediaStore の別名付け（X6-2 の読み戻し）に任せる
- 処理の順が決まっているので、同じ入力から必ず同じ結果になる。先に置く側が接尾辞なしを取る（B3-1 の「後から置く側」）
- 後からの実行で `persistent_id` の小さい新しい曲が現れても、すでに置いた曲は `placedPath` が固定されるので動かない（手順 2）
- M4 は置いた後に `DISPLAY_NAME` と `RELATIVE_PATH` を読み戻して T1 に保存する（B3-1。X6-2）。M1 は読み戻しを知らない

`mimeTypeOf` の表（M4 が MediaStore の `MIME_TYPE` に使う。X6-2。置いた規則で、値は実機で未確認）：

| 拡張子 | MIME |
|---|---|
| `mp3` | `audio/mpeg` |
| `m4a` | `audio/mp4` |
| `aac` | `audio/aac` |
| `wav` | `audio/x-wav` |
| `aif`、`aiff` | `audio/x-aiff` |
| `flac` | `audio/flac` |
| `m3u8` | `audio/x-mpegurl`（固定。J18） |

- 表に無い拡張子は「無し」を返す。端末の再生能力の判定（B3）で外れる形式は転送の前に除かれるため、「無し」は判定の漏れに当たる。M4 は E9（MediaStore への登録）として扱う

### X3-4 m3u8 の生成

対応：B3-3、F4-2、F4-4、N3-4。

関数（M1。純粋）：

| 関数 | 入力 | 出力 | 副作用 |
|---|---|---|---|
| `buildM3u8` | `ManifestPlaylist`、`placedPaths`（`persistent_id` から `placedPath` への対応） | m3u8 の本文（文字列） | なし |
| `buildPlaylistFileName` | プレイリストの並び（`persistent_id`、`name`）、V24、V23 | `persistent_id` からファイル名（拡張子 `.m3u8` を含む）への対応 | なし |

`buildM3u8` の手順：

1. 1 行目は `#EXTM3U`、行末は LF
2. `track_ids` を元の順に見て、`placedPaths` に無い ID は飛ばす（置けていない曲は載せない。B3-3）
3. あるものは `placedPath` をそのまま 1 行に書き、行末は LF。同じ曲が重複していればその回数だけ書く
4. 文字コードの変換は M4（UTF-8、BOM なし）。M1 は行末が LF の文字列を返す
5. 曲が 0 でも `#EXTM3U` の 1 行（行末の LF を含む）を返す
- `#EXTINF` は書かない（J14）。区切りは `/`。`..` は使わない（J14）
- パスの文字を変換しない（パーセントエンコードしない）。プレイヤーでの扱いは実機で確かめる（C の表。未確認）

`buildPlaylistFileName` の手順（B3-3 の「プレイリスト名の使えない文字」）：

1. `name` を `sanitizeElement`（代わりは `Untitled`）にかけ、ステムを作る。ステムの上限は V23 から `.m3u8` の 5 バイトを引いた値
2. 手順 1 の結果が同じ（大文字小文字を区別しない比較。X3-3 の手順 1 と同じ）になるプレイリストが 2 つ以上ある、または元の `name` が空（手順 1 で代わりの文字列になった）なら、そのプレイリストのすべてに ` [` ＋ プレイリストの `persistent_id` の先頭の V24 文字 ＋ `]` を足す。足すときのステムは、接尾辞の分を引いた上限で作り直す
3. 接尾辞を足した後でも同じファイル名（同じく大文字小文字を区別しない）が残るときは、X3-3 の手順 6 と同じく接尾辞を全文にする
4. m3u8 を書くと決めたとき（省略は X6-3、J37）、選んだ全プレイリストを入力にする（F4-4）。名前の変更で m3u8 のファイル名が変わったときの古いファイルの削除は M4（X6）
- 同じ名前の一方が選択から外れると、残る側の接尾辞が消えてファイル名が変わる。B3-3 の文言のとおりで、利用者の見える害は m3u8 が 1 回作り直されるだけ（J21（a））

### X3-5 エラーの型 E1〜E9 と結果の型

対応：B2-6、B2-8、F6-2、F3-7、N3-5。

エラー（`SyncError`。M1 の封印した型）。E の番号、名、範囲は B2-6 の表のとおりで、ここでは持つ項目だけを書く：

| E | 型の名 | 持つ項目 | 範囲 |
|---|---|---|---|
| E1 | `ConnectionFailed` | `cause`（名前の解決の失敗、接続の拒否、時間切れ） | 同期全体 |
| E2 | `KeyMismatch` | `cause`（署名の不一致、証明書の固定の不一致、ペアリングの解除） | 同期全体 |
| E3 | `InsufficientStorage` | `shortfallBytes`、`phase`（事前、書き込み中） | 同期全体 |
| E4 | `XmlUnavailable` | なし | 同期全体 |
| E5 | `FileMissing` | `persistentId`、`name`、`reason`（`no_location`、`undecodable`、`not_found`、`unreadable`。X3-1 の `reason` から `no_id` を除く 4 値） | 曲単位 |
| E6 | `TransferInterrupted` | `persistentId`（任意。P1 の受信中の切断では無し）、`attempts` | 同期全体 |
| E7 | `TimeLimit` | なし | 同期全体 |
| E8 | `ManifestStale` | `persistentId`、`name`、`retried`（やり直しでも E8 だったか） | 曲単位 |
| E9 | `WriteFailed` | `persistentId`、`name`、`step`（一時ファイルの書き込み、移動、MediaStore への登録） | 曲単位 |

- 範囲（同期全体か曲単位か）は型が持つ関数 `scope` で返す。値は E ごとに固定
- 曲名は結果の表示（B2-8 の失敗の内訳）に使うので、曲単位の E は `name` を持つ。パスは持たない（N1-6）
- 「版が合わない」と「壊れたマニフェスト」（X3-1）は E に含めない（J21（a））
- E1 は到達確認で接続先の全部がだめなときだけ。P1 の受信中の切断（曲の指定が無いので E6 の `persistentId` は無し）と、P3 の後の接続の確立の失敗は E6 とし、V10 の回数で再試行する（B2-6、X6-5）

結果（`SyncResult`。M4 が作って保存し、B2-8 の項目に対応する）：

| 項目 | 型 | 意味 |
|---|---|---|
| `startedAt`、`finishedAt` | 日時 | 端末の時計 |
| `trigger` | 手動、自動 | 起動の種別 |
| `transport` | LAN、Tailscale、USB テザリング | 接続の方式 |
| `outcome` | 完了、一部、中断、失敗 | 下の関数で決める |
| `hasMore` | 真偽 | 続きあり |
| `counts` | `Counts`（足す、置き換える、消すの予定と完了、変更なしの件数（1 つ）。項目は下の表） | B2-5 |
| `capacity` | `Capacity`（差分の合計バイト、転送したバイト、前後の空き容量。項目は下の表） | N2-4 |
| `errors` | `SyncError` の並び | 失敗の内訳の元 |
| `excluded` | `ExcludedTrack` の並び | マニフェストの写し |
| `unplayable` | `UnplayableTrack` の並び | 曲名、`kind`、拡張子、理由（`protected`、`unsupported_format`） |
| `libraryId`、`contentDigest` | 文字列 | 次回の判定（B2-5、B2-7） |
| `unresolvedPlaylists` | 名前の並び | マニフェストの写し |
| `duplicatesDropped` | 整数 | X3-1 の読み込みの手順 4 で落とした件数 |
| `removalSkipped` | 真偽 | 「消す」を飛ばしたとき真。自動が確認の扱いで飛ばしたとき（J9）と、手動で承認した件数を再計算の件数が超えて飛ばしたとき、確認の取り消しで飛ばしたとき（J26）に立てる。S4 の案内の条件。T3 の `result_json` に入る |
| `keyMismatch` | 真偽、任意 | `errors` に E2 を含むときだけ持つ（無ければ空）。`detail` なしの 401 と証明書の固定の不一致は真。`clock_skew`・`replay`・429 の E2 は偽（J35）。E2 の `cause` からは導かず、M4 が HTTP の応答から決める（X6 の表のとおり）。`cause` は 3 値のまま変えない。自動の抑止（X6-3 の 2 の 1 の (b)）の判別用。T3 の `result_json` に入り、キーは `key_mismatch`（X6-1）。P4 には載せない |

- `SyncResult` を T3 の `result_json` にするときの JSON のキーは、項目名の `snake_case`（`removalSkipped`＝`removal_skipped`、`keyMismatch`＝`key_mismatch`。X3-1 の規則）

`Counts`（すべて整数（32）。マニフェストを取れなかった実行は全項目 0。X6-3 の 2 の 3）：

| 項目 | JSON のキー | 意味 |
|---|---|---|
| `toAdd`、`toReplace`、`toRemove` | `to_add`、`to_replace`、`to_remove` | 予定。`Diff` の同名の並び（X3-2）の件数 |
| `unchanged` | `unchanged` | 変更なし。`Diff` の `unchangedCount`。作業が無いので予定と完了を分けず 1 つだけ持つ |
| `added`、`replaced`、`removed` | 同じ | 完了。足し終えた、置き換え終えた、消し終えた曲の件数。T3 の同名の列（X6-1） |
| `failed` | `failed` | 曲単位の失敗（E5、E8、E9）の曲数。T3 の `failed` の列。内訳は `errors` から作る |

`Capacity`：

| 項目 | JSON のキー | 型 | 意味 |
|---|---|---|---|
| `diffBytes` | `diff_bytes` | 整数（64） | 差分の合計バイト。`requiredBytes` の値（V11 の余裕は含めない）。差分を作れなかった実行は 0 |
| `bytesTransferred` | `bytes_transferred` | 整数（64） | 転送したバイト。T3 の同名の列（X6-1） |
| `freeBytesBefore`、`freeBytesAfter` | `free_bytes_before`、`free_bytes_after` | 整数（64）、任意 | 同期の前後の空き容量。測れなかったときは無し（0 を空の意味に使わない。X3-1） |

- `reach_failed` は `SyncResult` の項目ではない。手動の同期で、画面側の到達確認で全部の接続先が応えず、証明書の不一致が 1 つも無かったこと（不一致が 1 つでもあれば `reach_failed` を付けず、E2 になる。J36）を `SyncWorker` に渡す入力データ（X6-3 の入口の表、手順 6 の 1）で、T3 にも P4 にも載せない
- 失敗の内訳（E の番号ごとの件数と最初の曲名）は `errors` から作る関数 `breakdown` で出す。結果に別の項目として持たない（二重の持ち方を避ける）
- 画面に出す件数の上限（V21）の適用は M4（B3-6）
- `UnplayableTrack` の `unsupported_format`（規則 2）は端末の再生能力の判定（B3）で付ける。M1 は型だけを持つ。`protected` は X3-2 の `unplayable` から作る関数 `toUnplayable` で写す

区分の判定（関数 `classifyOutcome`。M1。純粋）：

| 入力 | 内容 |
|---|---|
| `errors` | 同期の間に起きた `SyncError` |
| `transferStarted` | 1 曲でも取得の要求を送ったか |
| `userInterrupted` | 利用者が中断したか（通知の「中断」、S3 の操作） |

0. `userInterrupted` が真なら、`errors` と `transferStarted` に関わらず「中断」
1. `errors` が空なら「完了」
2. `errors` がすべて曲単位（E5、E8、E9）なら「一部」
3. 同期全体の E（E1、E2、E3、E4、E6、E7）を 1 つでも含む場合：`transferStarted` が偽なら「失敗」、真なら「中断」
4. ただし E7 を含むときは `transferStarted` に関わらず「中断」
- 結果の値の対応表。区分（M1 の `outcome`）を、T3 に保存するとき（X6-3）と P4 で報告するとき（X5-1）に写す。T3 の `result` は 5 値、P4 の `outcome` は 4 値で、`not_started` は失敗に畳む。T3 の `result` への写しは M4（X6-3）が行い、P4 の `outcome` への畳みは `toReport`（M1）が行う。画面の語は、S1・S4 が 5 値、W3 が 4 値（W3 は未開始を失敗として出す）：

| 区分（M1 の `outcome`） | T3 の `result`（5 値） | P4 の `outcome`（4 値） | S1・S4 の語（5 値） | W3 の語（4 値） |
|---|---|---|---|---|
| 完了 | `success` | `success` | 完了 | 完了 |
| 一部 | `partial` | `partial` | 一部失敗 | 一部失敗 |
| 中断 | `interrupted` | `interrupted` | 中断 | 中断 |
| 失敗（E3 の `phase` が事前以外） | `failed` | `failed` | 失敗 | 失敗 |
| 失敗（E3 の `phase` が事前） | `not_started` | `failed` | 未開始 | 失敗 |

- `hasMore`（関数 `hasMore`）：区分が「中断」で、`Diff` の足す・置き換えに未完了が残るとき真（入力は完了した件数と予定の件数。J21（a））
- 「消す」を実行するのは、区分が「完了」か「一部」のとき（B2-6）。判定の関数 `mayRemove`（入力は区分）を M1 に置く

PC への報告（P4 の本文の元。`SyncReport`）。項目は次の 8 つで確定する。曲名の一覧を含めない（B2-8）。`SyncResult` から作る関数 `toReport`（M1）を置く：

| 項目 | 型 | 意味 |
|---|---|---|
| `startedAt`、`finishedAt` | 日時 | `SyncResult` と同じ |
| `outcome` | 完了、一部、中断、失敗 | `SyncResult` と同じ。T3 の `not_started`（E3 の事前）は失敗に畳む |
| `hasMore` | 真偽 | `SyncResult` と同じ |
| `counts` | `Counts` | `SyncResult` と同じ |
| `capacity` | `Capacity` | `SyncResult` と同じ |
| `failureCounts` | E の番号ごとの件数 | `breakdown` の件数だけ。曲名を含めない |
| `excludedCount` | 整数 | `excluded` の件数 |

- X5 はこの全項目をそのまま保存する（X5-4 の `last_sync`）。P4 の本文の JSON のキーと検査は X5-1 が決める

### X3-6 テストの観点

対応：N5-1、B1-3 の M1 の列。実機なし、JVM で実行する。

| 対象 | 観点 |
|---|---|
| マニフェストの読み書き | 往復で同じ値になる。未知のキー（`content_hash` を含む）を読み捨てる。任意の項目の無しと値あり。`persistent_id` の形の違い（15 桁、小文字、非 16 進）。重複の落とし。`format_version` の範囲外と欠落。`track_ids` が `tracks` に無い ID を持つ。必須項目の欠け（`size` が無い）と型違い（`modified_at` が文字列）は「壊れた」 |
| `content_digest` | 固定の入力の期待値（手で作った試料の値を 1 つ固定する）。入力の並びを入れ替えても同じ。`generated_at`、`library_id`、`excluded` を変えても同じ。`track_ids` の順を変えると違う。文字列の境界（「ab」「c」と「a」「bc」）で違う。`CryptoProvider` は固定の SHA-256 の実体を使う |
| 差分 | 判定（足す、置き換える、変更なし、消す。ファイルが無い場合の補充は「足す」の一種。ほかに保護付き）の表。`size` だけ違う、`modified_at` だけ違う。保護付きと `m4p`。1 曲が複数のプレイリストに入る（集合は `tracks` なので 1 件）。`SyncRecord` が空（入れ直し）。ファイルが無く、かつマニフェストに無い記録（消す）。`recordedLibraryId` の null と違い。消す件数が V20 と同じ、V20 を超える、全部。`toRemove` が 0 件で全部の条件に当たらない（全部の条件は 1 件以上）。`toRemove` が 1 件以上で `unresolvedPlaylists` が 1 件なら `NeedsConfirmation`、`unresolvedPlaylists` が 0 件なら `Allowed`。`toRemove` が 0 件で `unresolvedPlaylists` が 1 件のときは `Allowed`。`LibraryChanged` でも `toRemove` を計算して返す。`LibraryChanged` と `NeedsConfirmation` の両方に当たるとき `LibraryChanged`。`isEmpty`（3 つの並びがすべて空のときだけ真） |
| 容量 | 足す、置き換えるだけを数え、消すを引かない。空の差分は 0 |
| `sanitizeElement` | 使えない文字 9 種、制御文字（U+0000〜U+001F、U+007F）、先頭末尾の空白（全角を含む）、末尾の `.`、`.` と `..`、空白と `.` が混ざる末尾、空になる入力、日本語、絵文字、結合文字（正規化されないこと） |
| 長さ | V23 ちょうど、V23 + 1、3 バイトの文字の境界、4 バイトの文字（サロゲートペア）の境界、切った後に末尾が空白か `.`、拡張子と接尾辞を含めた全体が V23 以内 |
| 衝突 | マニフェスト内の 2 件と 3 件、`persistent_id` の昇順で先が接尾辞なし。使用中の集合との衝突。接尾辞の後も衝突（V24 の文字数の一致）→ 全文。全文でも使用中なら「解けない」を返さず全文のパスをそのまま返す（J42）。置き換えの曲の `placedPath` が動かない。入力の並びを入れ替えても結果が同じ。大文字小文字だけの違いは同じパスとして衝突する（区別しない比較。基本設計の直し 12）。NFC と NFD の同じ見た目の名前は衝突せず別のパスになる（正規化しない。B3-1） |
| 候補の文字列 | `album_artist` あり・なし・空白のみ、`artist` のみ、すべて空（`Unknown Artist`、`Unknown Album`、`Untitled`）。拡張子が空 |
| m3u8 | `#EXTM3U` の行と LF。空のプレイリスト。置けていない曲を飛ばす。同じ曲の重複。BOM と CR を含まない。日本語と記号を含むパス。先頭が `#` のパスが出ない（`sanitizeElement` の手順 6。フォルダ名・ファイル名が `#` で始まる入力を含む）。U+0085、U+2028、U+2029 が削除される（`sanitizeElement` の手順 2。行の区切りがパスに残らないことを m3u8 の出力で確かめる） |
| プレイリストのファイル名 | 同名の 2 件に接尾辞、大文字小文字だけ違う 2 件に接尾辞、空の名前、接尾辞後の再衝突、長い名前の切り詰め（`.m3u8` を含めて V23 以内） |
| 区分 | `classifyOutcome` の 4 種と境界（E7 だけ、曲単位だけ、同期全体の E（E1〜E4、E6 の 5 通り。E6 は `persistentId` なしを含む）＋ `transferStarted` の真偽、`userInterrupted` が真で `errors` が空）。区分から T3 の `result` と P4 の `outcome` への対応（E3 の事前だけ `not_started`。P4 では `failed`）。`mayRemove` |
| 結果の関数 | `hasMore`（中断で未完了が残るときだけ真）。`breakdown`（E の番号ごとの件数と最初の曲名）。`toReport`（8 項目、曲名を含めない、`excludedCount`、`outcome` の畳み） |
| MIME | `m3u8` は `audio/x-mpegurl`（固定。J18）。`aif` と `aiff` は同じ値（2 対 1）。表に無い拡張子は無し。表の全件の値は検算しない（定数表の写しになるため。実在の値は実機で確かめる） |

- 試料はすべて合成データ。実在の曲名、パスを使わない
- テストの数は上の観点を 1 つずつ満たす最小にする。同じ観点の重複を作らない

### X3 の決定（未付番）

1. 保護付き（`protected` が真または `m4p`）のトラックは `tracks` に残し、`excluded` には載せない。`ExcludedTrack.reason` に `protected` を含めない。根拠：B2-4 が `tracks` に `protected` を持たせ、B2-8 の規則 3 が「除外の一覧と別に」と書くため
2. 同じ `persistent_id` がマニフェストの `tracks` に複数あるときは、最初を残して落とし、件数を結果に残す。根拠：同期を止めない。PC の不具合の可能性があり、件数で気づけるようにする
3. 差分の出力、パスの解決、m3u8 の行はすべて `persistent_id` の昇順で決定的にする。根拠：同じ入力で同じ結果にし、テストを固定できる
4. 接尾辞は、1 回目に V24 文字、2 回目に全文（16 文字）とする。途中の長さは試さない。根拠：手順を 2 段で終わらせる

### X3 の置いた値

V38〜V40 に登録済み（B1-7）。固定の約束 2 件（切る境界はコードポイント、接尾辞の 2 回目は全文の 16 文字）は V の表に入れない（X1 の判断）。X3-3 の `mimeTypeOf` の表は V 未付番で、B1-7 への登録を要する。

### X3 の要判断

J21 の結論（すべて推奨どおり）は本文に反映済み（review-05、06）。

### X3 の差し戻し候補

基本設計の直し 10〜13 は B2、B3 に反映済み（review-05）。

### X3 の他の節への依頼

X1、X4 への依頼は反映済み。未了は次の表のみ。

| 宛先 | 依頼 |
|---|---|
| X2 | `ExcludedTrack` の理由（`no_location`、`undecodable`、`not_found`、`unreadable`、`no_id`）は、M2 の材料（場所の結果、ID 無しの一覧、`kind`）から M3 が導く。保護付きは除外せず `protected` の印で出す |
| X5 | P1 の応答の本文は `Manifest` の直列化の結果。`content_digest` は X3-1 の関数で PC が計算する。除外の理由の値は M2 の材料から写す |
| X6 | `SyncRecord`、`Diff`、`SyncResult`、`SyncError` を M4 が写す（T1 の列との対応は X3-2）。`presentIds` の作成、MediaStore の既存のパスの問い合わせ（使用中のパスの集合）、読み戻したパスの T1 への保存、`removalGate` の扱い（`LibraryChanged` の承認後は T3 の `library_id` を更新。`NeedsConfirmation` は手動が確認、自動は飛ばして通知）、区分から T3 の `result` への対応（X3-5）、`hasMore` の入力（完了件数）、プレイリストのファイル名の変更時の古い m3u8 の削除、UTF-8（BOM なし）への変換、MIME は X3-3 の表を使う |
| X7 | 結果の表示は `SyncResult` の項目を使う。除外の理由と再生できない形式の理由の表示の文は X7（S の表示）で決める。版が合わない・壊れたマニフェストの画面の文も同じ |

### X3 の確かめた事実

なし。この稿では文書を取得していない。次は未確認のまま進めた：

- 端末の共有ストレージのパスの比較が大文字小文字を区別するか（基本設計の直し 12 で区別しないと決定。実機の挙動は未確認）
- m3u8 の行頭の `#` と U+0085 などの扱い（各プレイヤー。基本設計の直し 11 で避ける。プレイヤーの挙動は未確認）
- パーセントや記号を含むパスのプレイヤーでの解釈（B3 の確認の表）
- kotlinx.serialization で `explicitNulls` を使った任意の項目の挙動、kotlinx-datetime の KMP での対応（公式文書は読んでいない。X1 の確認の結果の取り込み待ち）
