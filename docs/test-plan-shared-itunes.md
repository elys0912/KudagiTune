# KudagiTune テスト計画 shared と itunes

- 索引：test-plan.md
- 稿：第 3 稿（2026-11-06。review-13、review-14 を反映）。2026-11-07 に D15 に合わせ TS-158〜TS-160 を足した。2026-11-09 に review-15（実装との突き合わせ）を反映し、TS-161〜TS-177 を足して、TS-49・TS-96・TS-103・TS-128・TS-140・TS-142・TS-147 の観点を直した。同日の 2 周目で、再生できない形式は転送しない扱いに改め（X6-10）、TS-164・TS-165 の期待を直した
- 参照：detailed-design-x3.md の X3-6（束 1）、detailed-design-x2.md の X2-5（束 2。X2-1〜X2-3 の範囲）、detailed-design-x4.md の X4-5（束 3）、basic-design-b4b.md の B4-8

## 1 方針

- 対象の束は束 1（shared の同期、X3）、束 2（itunes、X2-1〜X2-3）、束 3（shared の認証、X4）。束の記号は API の P1〜P6 と区別する（review-11）。X2-4（読み直し、`WatchService`）は束 4 の範囲で、ここに含めない
- 束の中ではコンパイル（typecheck）だけ回す。テストは束の最後に、該当モジュールのテストだけ 1 回回す。全件は CI（GitHub Actions、ubuntu。`./gradlew build :android:assembleDebug` と依存の向きの検査）で回す。実装と全件テストの繰り返しはしない
- テストは振る舞いだけを確かめる。起こり得ない入力の検証、モックの戻り値の確認、実装の写経のテストを書かない。1 つのテストは 1 つの観点、名前は日本語で「何を・どうすると・どうなる」
- 試料はすべて合成。実在の曲名・パス・ID・IP・ドメインを使わない（IP は 192.0.2.0/24、名前は `example.invalid`）
- 時刻と乱数は引数で固定する。暗号の乱数は固定の偽の実体を渡し、乱数を得る順は試験ベクターに合わせて固定する（x の次に y）。nonce の件数の上限など、試験で小さくしたい値は引数で渡す
- 束 1〜3 は JVM だけで動き、実機も Windows の実環境も要らない。したがって TS-102（性能。`performanceTest` で別に回す）を除く全件が CI の対象
- 試験ベクターは公開されたもの（RFC 9382 の付録、HKDF と HMAC の RFC の例、SHA-256 の標準の例）を使い、値は実装時に原本から写す。本稿に値は書かない。実体（HMAC、HKDF、SHA-256、点演算）の既知の入力・既知の値は、本番の実体を持つ :desktop（TD-54）と :android（TA-120）のテストに集約する（X4-5。review-12）。:shared に置いていた手順（M1）と固定の暗号の実体の検算（TS-130）は、同じ公開の例を TD-54・TA-120 が確かめるため削った（review-15）

## 2 テストケースの一覧

- 件数：束 1 が 92 件（TS-01〜TS-14、TS-16〜TS-22、TS-25〜TS-33、TS-35〜TS-36、TS-38、TS-40〜TS-51、TS-53〜TS-54、TS-56〜TS-65、TS-67〜TS-78、TS-131〜TS-134、TS-143、TS-147、TS-149〜TS-150、TS-153、TS-157〜TS-166、TS-180〜TS-183）、束 2 が 28 件（TS-80〜TS-87、TS-91〜TS-93、TS-95〜TS-99、TS-101〜TS-102、TS-135〜TS-136、TS-167〜TS-172、TS-178〜TS-179）、束 3 が 40 件（TS-103〜TS-116、TS-118〜TS-128、TS-137〜TS-142、TS-152、TS-154〜TS-156、TS-173〜TS-177）。計 160 件。追加分（review-11、review-12、review-13、review-14、review-15）は既存の番号を保つため末尾に付けた
- 層の「薄い統合」は、複数の関数をつないで純粋なまま確かめるもの。「実機」は束 1〜3 に無い
- 表中の「V」は basic-design.md の B1-7 の値（V1 コードの桁数、V2 試行の上限、V3 コードの有効時間、V4 超過後の待ち、V7 規模の目標、V12 時刻の許容、V20 消す確認の閾値、V23 要素の上限バイト、V24 接尾辞の文字数、V42 偏り除外の上限回数）

| ID | 束 | 対象（関数・部品） | 観点 | 入力（境界の値） | 期待する結果 | 層 | CI |
|---|---|---|---|---|---|---|---|
| TS-01 | 1 | マニフェストの読み書き | 往復 | 全項目を持つ合成のマニフェスト | 書いて読んだ値が元と同じ | 単体 | ○ |
| TS-02 | 1 | マニフェストの読み | 未知のキーの読み捨て | 最上位とトラックに未知のキーと `content_hash` を足した本文 | 読めて、既知の項目の値が変わらない | 単体 | ○ |
| TS-03 | 1 | マニフェストの読み書き | 任意の項目の無しと値あり | `album_artist` などを無し／値ありにした 2 曲 | 無しは null で、書いた本文にキーが出ない。値ありは往復で同じ | 単体 | ○ |
| TS-04 | 1 | マニフェストの読み | `persistent_id` の形 | 15 桁、小文字、非 16 進（`G` を含む）の ID。トラックのほか、`playlists` の ID（小文字）と `unresolved_playlists` の ID（15 桁） | いずれも「壊れた」（理由の文字列つき） | 単体 | ○ |
| TS-05 | 1 | マニフェストの読み | 重複の落とし | `tracks` に同じ ID が 3 件 | 最初の 1 件が残り、`duplicatesDropped` は 2 | 単体 | ○ |
| TS-06 | 1 | マニフェストの読み | `format_version` の範囲外と欠落 | 知る版の範囲の外の値（上と下）、キーなし | 範囲外は「版が合わない」（受け取った版つき）。欠落は「壊れた」 | 単体 | ○ |
| TS-07 | 1 | マニフェストの読み | `track_ids` の参照切れ | `tracks` に無い ID を持つプレイリスト | 「壊れた」 | 単体 | ○ |
| TS-08 | 1 | `content_digest` | 固定の期待値 | 手で作った小さな試料と実の SHA-256 | 手で計算した 64 桁の小文字 16 進と一致（値は 1 つ固定） | 単体 | ○ |
| TS-09 | 1 | `content_digest` | 一覧の並びの入れ替え | `tracks`、`playlists`、`unresolved_playlists` の元の並びを入れ替え | 同じ値 | 単体 | ○ |
| TS-10 | 1 | `content_digest` | 対象外の項目 | `generated_at`、`library_id`、`xml_modified_at`、`excluded`、`content_digest` だけ変える | 同じ値 | 単体 | ○ |
| TS-11 | 1 | `content_digest` | `track_ids` の順 | 1 本のプレイリストの `track_ids` の順を入れ替え | 違う値 | 単体 | ○ |
| TS-12 | 1 | `content_digest` | 文字列の境界 | （「ab」「c」）と（「a」「bc」）の 2 つの項目の組 | 違う値 | 単体 | ○ |
| TS-13 | 1 | `computeDiff` | 足す | `SyncRecord` に無い曲。`SyncRecord` が空で曲が 3 件の場合も | `toAdd` に入る。空の場合は 3 件とも `toAdd` に入り、`toRemove` は空 | 単体 | ○ |
| TS-14 | 1 | `computeDiff` | ファイル無しの補充 | `SyncRecord` にあり `presentIds` に無い曲（`size` も違う） | `toAdd` に入り、`toReplace` に入らない | 単体 | ○ |
| TS-16 | 1 | `computeDiff` | 変更なし | `SyncRecord` と同じ値で `presentIds` にある曲 | `unchangedCount` に数える | 単体 | ○ |
| TS-17 | 1 | `computeDiff` | 消す | `SyncRecord` にあり、入力の曲に無い記録（ファイルあり） | `toRemove` に入る | 単体 | ○ |
| TS-18 | 1 | `computeDiff` | `size` だけ違う | `modified_at` は同じで `size` が違う曲 | `toReplace` に入る | 単体 | ○ |
| TS-19 | 1 | `computeDiff` | `modified_at` だけ違う | `size` は同じで `modified_at` が違う曲 | `toReplace` に入る | 単体 | ○ |
| TS-20 | 1 | `computeDiff` | 保護付き（`protected` 真） | `SyncRecord` が無い曲 | `unplayable` に入り、`toAdd` に入らない | 単体 | ○ |
| TS-21 | 1 | `computeDiff` | 保護付き（`m4p`） | `protected` は偽で `extension` が `m4p` の曲 | `unplayable` に入る | 単体 | ○ |
| TS-22 | 1 | `computeDiff` | 保護付きで記録のある曲 | `SyncRecord` にあり `protected` 真の曲 | `unplayable` に入り、`toRemove` に入らない | 単体 | ○ |
| TS-25 | 1 | `computeDiff` | ファイルが無く、マニフェストにも無い記録 | `presentIds` に無く入力の曲にも無い記録 | `toRemove` に入る | 単体 | ○ |
| TS-26 | 1 | `computeDiff` | `recordedLibraryId` が null | null と任意の `manifestLibraryId` | `removalGate` は `Allowed` | 単体 | ○ |
| TS-27 | 1 | `computeDiff` | `recordedLibraryId` が違う | 記録と違う `manifestLibraryId`。消す対象あり／消す対象が 0 件 | `LibraryChanged`。`toRemove` は計算して返す。`toRemove` が 0 件なら `Allowed`（J25、B2-5。2026-11-06） | 単体 | ○ |
| TS-28 | 1 | `computeDiff` | 消す件数が V20 と同じ | `toRemove` が V20 件で、記録の一部 | `Allowed` | 単体 | ○ |
| TS-29 | 1 | `computeDiff` | 消す件数が V20 を超える | `toRemove` が V20 + 1 件で、記録の一部 | `NeedsConfirmation`。`toRemove` は返す | 単体 | ○ |
| TS-30 | 1 | `computeDiff` | 全部を消す | 記録が V20 以下の件数で、全部が `toRemove` | `NeedsConfirmation` | 単体 | ○ |
| TS-31 | 1 | `computeDiff` | 消す件数が 0 | `toRemove` が 0 件。記録は 0 件と 1 件以上の両方 | `Allowed`（0 件は「全部」に当たらない） | 単体 | ○ |
| TS-32 | 1 | `computeDiff` | 出力の並び | 入力の並びを入れ替えた同じ内容 | 各一覧が `persistent_id` の昇順で、結果は同じ | 単体 | ○ |
| TS-33 | 1 | `requiredBytes` | 足す・置き換えの合計 | `toAdd`、`toReplace`、`toRemove` に各数件。全部空の `Diff` | `toAdd` と `toReplace` の新しい `size` の合計。消すは引かない。全部空なら 0 | 単体 | ○ |
| TS-35 | 1 | `sanitizeElement` | 使えない文字 9 種 | `/ \ : * ? " < >` と縦棒（計 9 種）を含む文字列 | 9 種とも `_` になり、数は増減しない | 単体 | ○ |
| TS-36 | 1 | `sanitizeElement` | 制御文字 | U+0000、U+001F、U+007F を含む文字列 | 全部削除される | 単体 | ○ |
| TS-38 | 1 | `sanitizeElement` | 先頭末尾の空白 | 半角と全角（U+3000）の空白で挟んだ文字列 | 両端の空白が消える | 単体 | ○ |
| TS-40 | 1 | `sanitizeElement` | `.` と `..` | `.`、`..` | ともに `_` | 単体 | ○ |
| TS-41 | 1 | `sanitizeElement` | 末尾の `.` と空白 | `abc..`、`abc. . ` | どちらも `abc`（どちらも残らない） | 単体 | ○ |
| TS-42 | 1 | `sanitizeElement` | 空になる入力 | 空文字列、空白だけ、制御文字だけ | 代わりの文字列 | 単体 | ○ |
| TS-43 | 1 | `sanitizeElement` | 日本語と絵文字 | 日本語、絵文字を含む文字列 | そのまま残る | 単体 | ○ |
| TS-44 | 1 | `sanitizeElement` | 結合文字 | NFD の濁点つきの名前 | 正規化されず、同じ列のまま | 単体 | ○ |
| TS-45 | 1 | `truncateToBytes` | V23 ちょうど | UTF-8 で V23 バイトの文字列 | そのまま | 単体 | ○ |
| TS-46 | 1 | `truncateToBytes` | V23 + 1 | UTF-8 で V23 + 1 バイトの ASCII | V23 バイトに切れる | 単体 | ○ |
| TS-47 | 1 | `truncateToBytes` | 3 バイトの文字の境界 | 境界が 3 バイト文字の途中に当たる日本語 | 文字の手前で切れ、上限以内の最長 | 単体 | ○ |
| TS-48 | 1 | `truncateToBytes` | 4 バイトの文字の境界 | 境界がサロゲートペアの途中に当たる絵文字 | コードポイントの手前で切れる | 単体 | ○ |
| TS-49 | 1 | `buildTrackCandidate` | 切った後の末尾 | 切った結果が空白で終わる名前、`.` で終わる名前、空になる名前（入力は別々に置く。review-15 の X3-9） | 末尾が削られ、空なら代わりの文字列 | 単体 | ○ |
| TS-50 | 1 | `buildTrackCandidate` | 全体の長さ | 長い曲名、拡張子、接尾辞あり・なし | 拡張子と接尾辞を含めた曲名の要素が V23 以内 | 単体 | ○ |
| TS-51 | 1 | `buildTrackCandidate` | アーティストの選び方と空の代わり | `album_artist` あり・なし・空白のみ・`artist` のみ。`artist`、`album`、`name` がすべて空 | あり→それ。無し→`artist`。空白のみ→空でない扱い（選んだ後に空になれば `Unknown Artist`）。すべて空なら `Unknown Artist`、`Unknown Album`、`Untitled` | 単体 | ○ |
| TS-53 | 1 | `buildTrackCandidate` | 拡張子が空 | `extension` が空文字列 | 末尾に `.` が付かない | 単体 | ○ |
| TS-54 | 1 | `resolveTrackPaths` | マニフェスト内の衝突（2 件・3 件） | 同じ候補になる 2 曲、3 曲 | `persistent_id` の昇順で先頭が接尾辞なし。2 件の後は V24 文字の接尾辞つき。3 件の残り 2 件は互いに別のパス | 単体 | ○ |
| TS-56 | 1 | `resolveTrackPaths` | 使用中の集合との衝突 | 使用中の集合に候補と同じパスがある 1 曲 | 接尾辞つきのパス | 単体 | ○ |
| TS-57 | 1 | `resolveTrackPaths` | 接尾辞の後も衝突 | V24 文字の接尾辞つきのパスが使用中になる曲 | 全文（16 文字）の接尾辞つきのパスをそのまま返す（「解けない」の戻りは無い。使用中なら MediaStore の別名付けに任せる。J42） | 単体 | ○ |
| TS-58 | 1 | `resolveTrackPaths` | 置き換えの曲のパス | `SyncRecord` を持つ曲と、後から現れた `persistent_id` の小さい新しい曲 | 既存の曲は `placedPath` のまま動かない | 単体 | ○ |
| TS-59 | 1 | `resolveTrackPaths` | 入力の並びの入れ替え | 同じ曲の集合の並びを替えた 2 回 | 同じ結果 | 単体 | ○ |
| TS-60 | 1 | `resolveTrackPaths` | 大文字小文字だけの違い | `A.mp3` と `a.mp3` になる 2 曲 | 衝突として扱い、後の曲に接尾辞。返す綴りは元のまま | 単体 | ○ |
| TS-61 | 1 | `resolveTrackPaths` | NFC と NFD | 見た目が同じで正規化形が違う名前の 2 曲 | 衝突せず、接尾辞なしの別のパス | 単体 | ○ |
| TS-62 | 1 | `buildM3u8` | 書式 | 曲 3 件のプレイリスト | 全文一致：1 行目が `#EXTM3U`、各行は LF で終わり、`#EXTINF` なし、先頭に BOM が無く、CR を含まない | 単体 | ○ |
| TS-63 | 1 | `buildM3u8` | 空のプレイリスト | `track_ids` が空 | `#EXTM3U` と LF の 1 行 | 単体 | ○ |
| TS-64 | 1 | `buildM3u8` | 置けていない曲 | `placedPaths` に無い ID を含む | その曲を飛ばす | 単体 | ○ |
| TS-65 | 1 | `buildM3u8` | 同じ曲の重複 | 同じ ID が 2 回 | 同じパスが 2 行 | 単体 | ○ |
| TS-67 | 1 | `buildM3u8` | 日本語と記号のパス | 日本語、`%`、`[]`、空白を含む `placedPath` | 変換されずそのまま 1 行 | 単体 | ○ |
| TS-68 | 1 | `resolveTrackPaths` と `buildM3u8` | 行頭の `#` と行区切り文字 | 曲名・アーティスト名・アルバム名が `#` で始まる、U+0085、U+2028、U+2029 を含む曲 | m3u8 に `#` で始まる行も区切り文字も出ない（フォルダ名・ファイル名の先頭は `_`） | 薄い統合 | ○ |
| TS-69 | 1 | `buildPlaylistFileName` | 同名の 2 件（大文字小文字だけ違う場合も） | 名前が同じ 2 本。`A` と `a` の名前の 2 本 | 2 本とも接尾辞つきで別のファイル名 | 単体 | ○ |
| TS-70 | 1 | `buildPlaylistFileName` | 空の名前 | 名前が空文字列 | `Untitled` ＋ 接尾辞 | 単体 | ○ |
| TS-71 | 1 | `buildPlaylistFileName` | 接尾辞の後の再衝突 | 接尾辞を足しても同じ名前が残る 2 本 | 全文の接尾辞になり別のファイル名 | 単体 | ○ |
| TS-72 | 1 | `buildPlaylistFileName` | 長い名前 | V23 を超える名前 | `.m3u8` を含めて V23 以内 | 単体 | ○ |
| TS-73 | 1 | `classifyOutcome` | 完了 | `errors` が空 | 完了 | 単体 | ○ |
| TS-74 | 1 | `classifyOutcome` | 曲単位だけ | E5、E8、E9 のみ。`transferStarted` は真と偽 | ともに一部 | 単体 | ○ |
| TS-75 | 1 | `classifyOutcome` | 全体のエラーで未転送 | E1、E2、E3、E4、E6（`persistentId` なし。P1 の受信中の切断）をそれぞれ単独で。`transferStarted` 偽 | いずれも失敗 | 単体 | ○ |
| TS-76 | 1 | `classifyOutcome` | 全体のエラーで転送後 | E6 と E5。`transferStarted` 真 | 中断 | 単体 | ○ |
| TS-77 | 1 | `classifyOutcome` | E7 | E7 のみ。`transferStarted` 偽 | 中断 | 単体 | ○ |
| TS-78 | 1 | `mayRemove` | 区分ごとの可否 | 完了、一部、中断、失敗 | 完了と一部だけ真 | 単体 | ○ |
| TS-80 | 2 | plist の読み手 | 標準 | 標準の合成 XML（曲数曲、通常 1 本、Master 1 本、全キー） | B2-1 の表どおりの項目が取れる。`Size`、`Date Modified` は持たない | 単体 | ○ |
| TS-81 | 2 | plist の読み手 | 欠損 | `Persistent ID`、`Location`、`Playlist Items`、`Name` を欠く XML | 空で持つ（補わない）。ID 無しは `unidentifiedTracks` へ、`Location` 無しは「場所なし」 | 単体 | ○ |
| TS-82 | 2 | plist の読み手 | 未知のキー | トラックとプレイリストに入れ子の辞書・配列の未知キーを足した XML | 標準と同じ結果 | 単体 | ○ |
| TS-83 | 2 | plist の読み手 | スマート | `Smart Info`、`Smart Criteria`、展開済みの `Playlist Items` | 条件を読まず、並びだけ使う | 単体 | ○ |
| TS-84 | 2 | plist の読み手 | 参照の欠け | `Tracks` に無い `Track ID`、重複した `Persistent ID`、同じ曲を 2 回含む並び | 落とした件数が `droppedTrackRefs`、`duplicatePersistentIds` と一致。同じ曲の 2 回は残る | 単体 | ○ |
| TS-85 | 2 | plist の読み手 | 文書の順序 | `Playlists` が `Tracks` より前の XML | 標準と同じ結果 | 単体 | ○ |
| TS-86 | 2 | plist の読み手 | 実物の形 | iTunes 12.13 の形を写した合成（BOM なし、`DOCTYPE`、CRLF、`&#38;`、組み込みの一覧、NFD のパス、`Protected` なし、`Location` なし、空の普通の一覧） | 普通の一覧だけが除外の印なしで残る。`libraryPersistentId` が入る。NFD のパスを変えずに返す。名前の文字参照が戻る。診断に `Application Version` と `Date` が入る（観点ごとに 6 つのテストに分けた。review-15） | 薄い統合 | ○ |
| TS-87 | 2 | `Persistent ID` | 正規化と形の検査 | 小文字 16 桁、15 桁、17 桁、16 桁で `G` を含む | 小文字は大文字になる。ほかの 3 つは ID 無し | 単体 | ○ |
| TS-91 | 2 | `integer` | 64 ビット超 | 64 ビットを超える値 | その項目は空 | 単体 | ○ |
| TS-92 | 2 | `date` | 不正な書式 | 日付でない文字列 | その項目は空 | 単体 | ○ |
| TS-93 | 2 | 構文 | 構文の不正 | 終了タグの無い XML、0 バイト | どちらも構文の失敗 | 単体 | ○ |
| TS-95 | 2 | 構文 | BOM 付き | 標準の XML の先頭に BOM | 成功。標準と同じ結果 | 単体 | ○ |
| TS-96 | 2 | DTD | 外部の DTD | `DOCTYPE` が `example.invalid` の URL を指す XML | 取りに行かず成功（取りに行けば `example.invalid` の名前解決に失敗するので、成功することで通信が起きないことを確かめる。解決器は差し替えない。review-15 の X2-10） | 薄い統合 | ○ |
| TS-97 | 2 | 文字参照 | `&amp;`、`&#38;`、`&lt;` | 名前と `Location` に含む XML | 戻した後の文字列で復号。名前も戻る | 単体 | ○ |
| TS-98 | 2 | 深い入れ子 | 取らないキーの深さ 50 | 未知のキーの値に深さ 50 の入れ子、後ろに取るキー | 読み捨て、後続のキーを読める | 単体 | ○ |
| TS-99 | 2 | `decodeFileUrl` | 境界の例の全件 | X2-2 の境界の例の表（全 10 件） | 表の結果と一致（1 件ずつ検査する表形式の 1 テスト） | 単体 | ○ |
| TS-101 | 2 | `decodeFileUrl` | `%00` | `%00` を含むパス | 復号不能：制御文字 | 単体 | ○ |
| TS-102 | 2 | 読み取り全体 | 規模 | 合成で数万トラック、プレイリスト数百本 | V7 の目標の時間内に完了。CI の差に余裕を持つ。JUnit のタグ `performance` を付けて通常の `test` から外し、:itunes の `performanceTest` で回す（手元用。CI の `build` には含めない。review-15） | 薄い統合 | × |
| TS-103 | 3 | SPAKE2 | 一致 | 固定の x、y、w（偽の乱数） | 両側が同じ K と TT を得る（SPAKE2 に Z、V は無い。review-15 の X4-2） | 単体 | ○ |
| TS-104 | 3 | SPAKE2 | コードの不一致 | w を 1 桁違いにした側 | MAC_d が合わず K に至らない | 単体 | ○ |
| TS-105 | 3 | SPAKE2 | AAD の不一致 | PC の SPKI のハッシュを 1 バイト変える | 印が合わない | 単体 | ○ |
| TS-106 | 3 | 点の復号 | 曲線上にない点 | 曲線上にない 65 バイト | 復号の失敗 | 単体 | ○ |
| TS-107 | 3 | 点の検査 | 無限遠点 | 無限遠点の X | 拒否する | 単体 | ○ |
| TS-108 | 3 | 試行の数え方 | P5 の失敗 | P5 の失敗を繰り返す | 試行は増えない | 単体 | ○ |
| TS-109 | 3 | 試行の数え方 | P6 の不一致 | P6 の MAC_d 不一致を V2 − 1 回、V2 回 | 1 回ずつ数える。V2 − 1 回ではコードは有効。V2 回に達したら無効（H0。review-11） | 単体 | ○ |
| TS-110 | 3 | コードの有効時間 | V3 の直前 | H1 に入った時刻から V3 − 1 秒の P5（時刻は引数） | 通る | 単体 | ○ |
| TS-111 | 3 | コードの有効時間 | V3 の直後 | H1 に入った時刻から V3 ちょうど、V3 + 1 秒の P5 | どちらも 403（`pairing_closed`）。V3 ちょうどは無効（review-11） | 単体 | ○ |
| TS-112 | 3 | コードの生成 | 偏り除外の直前 | 閾値の直前の乱数 | 採用され V1 桁の文字列になる | 単体 | ○ |
| TS-113 | 3 | コードの生成 | 偏り除外の閾値以上 | 閾値以上の乱数の次に採用できる乱数 | 最初を捨て、次を採用する | 単体 | ○ |
| TS-114 | 3 | コードの生成 | 先頭の 0 の埋め | 余りが V1 桁に満たない乱数 | 先頭を 0 で埋めた V1 桁 | 単体 | ○ |
| TS-115 | 3 | 署名対象 | 7 行の文字列 | 固定のメソッド、パス、時刻、nonce、device_id、本文 | 全文一致：`PSFOX1` から本文のハッシュまでの 7 行。行は LF で区切り、末尾に LF が無い | 単体 | ○ |
| TS-116 | 3 | 署名対象 | 本文が空 | 長さ 0 の本文 | 7 行目が空の列の SHA-256 の小文字 16 進 | 単体 | ○ |
| TS-118 | 3 | 署名 | 既知の値 | HMAC の試験ベクターの形で、既知の鍵と文字列 | 既知の値（パディングなしの base64url） | 単体 | ○ |
| TS-119 | 3 | 検証の時刻 | 差が V12 ちょうど | 現在時刻との差が V12 ちょうど | 通る | 単体 | ○ |
| TS-120 | 3 | 検証の時刻 | 差が V12 を 1 秒超過 | 差が V12 + 1 秒 | 401（`clock_skew`） | 単体 | ○ |
| TS-121 | 3 | 検証の時刻 | PC の起動時刻より前 | 起動時刻の 1 秒前のタイムスタンプ（現在時刻との差は V12 以内） | 401（`clock_skew`）。再起動の前に作った要求は再送できない（J29。2026-11-06） | 単体 | ○ |
| TS-122 | 3 | 検証の時刻 | PC の起動時刻ちょうど | 起動時刻ちょうどのタイムスタンプ | 通る | 単体 | ○ |
| TS-123 | 3 | 再送の防止 | 同じ（device_id、nonce） | 署名が合う同じ要求を 2 回 | 2 回目が 401（`replay`） | 単体 | ○ |
| TS-124 | 3 | 再送の防止 | 署名の失敗 | 署名が合わない要求の後に、同じ nonce の正しい要求 | 後の要求が通る（失敗で記録に残らない） | 単体 | ○ |
| TS-125 | 3 | 未登録の端末 | 失敗の応答 | 未登録の device_id と、署名の誤りの要求。未登録で時刻が許容（V12）の外の要求 | 同じ失敗の応答（`detail` の差が無い）。時刻が外の未登録は 401（`clock_skew`。登録済みと同じ） | 単体 | ○ |
| TS-126 | 3 | nonce の掃除 | 保持の期間 | V12 の 2 倍を過ぎた記録と、過ぎていない記録 | 過ぎたものだけ消える | 単体 | ○ |
| TS-127 | 3 | nonce の掃除 | 件数の上限 | 上限（引数。試験用に小さくした値を渡す）を超える追加 | 古い順に消える | 単体 | ○ |
| TS-128 | 3 | 確認コードの表示 | 形式 | 既知の SPKI の DER（`formatFingerprint`） | 24 桁の小文字 16 進を 4 桁ずつ空白で区切る。先頭 12 バイトだけ | 単体 | ○ |
| TS-131 | 1 | `computeDiff` | 未解決のプレイリストがあるとき | 未解決が 1 件以上で `toRemove` が V20 以下の 1 件以上（記録の一部）。未解決が 1 件以上で `toRemove` が 0 件。未解決が 0 件 | 1 件目は `NeedsConfirmation`。`toRemove` は返す。2 件目と 3 件目は `Allowed`（J24。review-13） | 単体 | ○ |
| TS-132 | 1 | `computeDiff` | `LibraryChanged` と `NeedsConfirmation` の両方 | 記録と違う `manifestLibraryId` かつ `toRemove` が V20 + 1 件 | `LibraryChanged`（X3-2）。`toRemove` は返す | 単体 | ○ |
| TS-133 | 1 | `hasMore` | 未完了の有無 | 中断で未完了あり／なし、中断以外で未完了あり | 中断で未完了ありのときだけ真 | 単体 | ○ |
| TS-134 | 1 | `classifyOutcome` | 利用者の中断 | 利用者の中断が真、`errors` は空／曲単位だけ／E1。`transferStarted` は偽と真 | すべて中断（review-11） | 単体 | ○ |
| TS-135 | 2 | plist の読み手 | 必須の構造の欠け | `Tracks` が無い、`Playlists` が無い、最上位が `dict` でない XML | いずれも失敗（X2-1 の J21（b）） | 単体 | ○ |
| TS-136 | 2 | plist の読み手 | 識別なしの参照の位置 | 並びの途中に `Persistent ID` の無い曲を含むプレイリスト | `TrackRef` の識別なしの参照が元の並びの位置を保つ | 単体 | ○ |
| TS-137 | 3 | コードの生成 | V4 の待ち | V2 回の失敗の直後、V4 の直前、V4 の後（時刻は引数） | 直前まではコードを作れない。V4 の後は作り直せる | 単体 | ○ |
| TS-138 | 3 | コードの生成 | 偏り除外が最大回数を超える | 閾値以上の乱数が V42 回続く（X4-2-5） | V42 + 1 回目を引かず、エラーを返す | 単体 | ○ |
| TS-139 | 3 | P5 | `device_exists` | `devices` が 1 件ある／0 件。P5 の端末は別の `device_id` | 1 件でもあれば 403（`device_exists`）。0 件は進む（D11、D14。review-11） | 単体 | ○ |
| TS-140 | 3 | P6 | K1 の保存失敗（X4-2-3、X5-1） | MAC_d が合い、鍵の保存が失敗する偽の実体 | 500。端末は登録されず、成功の応答を返さない。H1 に戻る（コードは有効のまま。試行は増えない。review-15 の X4-6） | 単体 | ○ |
| TS-141 | 3 | P5 | 後の P5 が先の途中状態を破棄 | P5 を 2 回続けて受ける（P6 の前） | 先の途中状態は使われず、後の P5 から進む | 単体 | ○ |
| TS-142 | 3 | SPAKE2 | w の導出 | 手で作った試料のコード（HKDF 1 段。塩なし。X4-2-4） | 手で計算した w と一致。RFC 9382 のベクターは w を直接与える範囲で使い、導出の検算に使わない | 単体 | ○ |
| TS-143 | 1 | `mimeTypeOf` | 固定値・別名・表に無い拡張子 | `m3u8`、`aif` と `aiff`、表に無い小文字の拡張子 | `m3u8` は `audio/x-mpegurl`（J18）。`aif` と `aiff` は同じ値（2 対 1）。表に無いものは無し | 単体 | ○ |
| TS-147 | 1 | `breakdown` | E ごとの内訳 | E5 と E8 が混在する `errors`（`breakdown` の入力は `errors`）。E なしの停止だけで止まった実行の `errors`（空） | E の番号の昇順で、E ごとの件数と最初の曲名。`errors` が空なら内訳は空（E なしの停止は `errors` に入らないので載らない。`stoppedWithoutE` は `SyncResult` の項目でなく `classifyOutcome` の入力で、TS-157 が確かめる。review-15 の X3-7） | 単体 | ○ |
| TS-149 | 1 | `toReport` | 報告の項目 | 曲名を含む `SyncResult`（`outcome` は 4 値のそれぞれ） | 曲名を含まない 8 項目。`excludedCount` は除外の件数。`outcome` は `SyncResult` の 4 値をそのまま写す（`not_started` の畳みは M4。TA-069。review-14） | 単体 | ○ |
| TS-150 | 1 | `isEmpty` | 空の差分 | 全部空、3 つの一覧のうち 1 つだけ 1 件 | 全部空のときだけ真 | 単体 | ○ |
| TS-152 | 3 | P5・P6 | V4 の間の `pairing_locked` | V2 回の不一致の後、V4 の間と V4 の後の P5・P6（時刻は引数） | V4 の間は P5・P6 とも 403（`pairing_locked`）。V4 の後は 403（`pairing_closed`） | 単体 | ○ |
| TS-153 | 1 | マニフェストの読み | 必須項目の欠けと型違い（X3-1 の読み込みの手順 3） | `size` が無いトラック、`modified_at` が文字列のトラック | どちらも「壊れた」（理由の文字列つき） | 単体 | ○ |
| TS-154 | 3 | P6 | コードの期限切れの後 | H2 で V3 ちょうどの P6（MAC_d は合う。時刻は引数） | 403（`pairing_closed`）。端末は登録されない | 単体 | ○ |
| TS-155 | 3 | SPAKE2 | 端末側の MAC_p の不一致 | PC が返す MAC_p を 1 バイト変える | 端末は K に至らず失敗する（偽の PC を拒否する） | 単体 | ○ |
| TS-156 | 3 | P5・P6 | 使用済みのコード | P6 の成功の後（H0）で V3 の内の P5、P6（時刻は引数） | どちらも 403（`pairing_closed`）。2 台目は登録されない | 単体 | ○ |
| TS-157 | 1 | `classifyOutcome` | E なしの停止 | `stoppedWithoutE` が真で、`errors` が空（`transferStarted` は偽と真）／曲単位だけ（`transferStarted` は真） | 偽は失敗、真は中断（E なしの停止は `errors` に入らない。X3-5） | 単体 | ○ |
| TS-158 | 1 | `parsePlaylistIds`（X3-1b） | 要求の本文の検査 | 正しい配列、空の配列、重複、JSON でない、キーが無い、配列でない、要素が小文字・15 桁・17 桁・文字列でない、個数が上限ちょうどと 1 つ超 | 正しい配列と空の配列と上限ちょうどは `Ids`（重複は 1 つに、入力の順）。ほかは `Invalid` | 単体 | ○ |
| TS-159 | 1 | `PlaylistNode` の往復（X3-1b） | 木の書き出しと読み取り | フォルダの中のフォルダ、葉、曲数、日本語名。ID の書式が違う、形が違う、JSON でない本文 | 往復で同じ木。壊れた本文は null | 単体 | ○ |
| TS-160 | 1 | 選択の操作（X3-1b） | フォルダの一括と葉の操作（旧 W2 の規則） | フォルダのチェック（配下の葉が一部選択済み）、フォルダを外す、葉を外す、既に選んだ葉を再び選ぶ、一覧に無い ID を外す | 配下の葉だけが選ばれ（重複なし）、フォルダは含まれない。外すと配下の葉だけ除かれる。他の選択は保たれる | 単体 | ○ |
| TS-161 | 1 | マニフェストの読み | `playlists` と `unresolved_playlists` の重複の落とし（X3-1。review-15） | 同じ `persistent_id` の `playlists` が 2 件（`track_ids` が違う）。同じ `persistent_id` の `unresolved_playlists` が 2 件 | それぞれ最初の 1 件だけが残る。`duplicatesDropped` は変わらない（曲だけ数える） | 単体 | ○ |
| TS-162 | 1 | `content_digest` | `content_hash` を含める（X3-6） | `content_hash` だけが違う 2 つのマニフェスト（ほかは同じ） | 違う値 | 単体 | ○ |
| TS-163 | 1 | `buildTrackCandidate` と `assemblePath` | 異常に長い拡張子（X3-2。X3-3 の手順 6） | 拡張子が V23 − 8 − 19 − 1 バイト（8 は `Untitled`、19 は全文の接尾辞。V23 が 240 なら 212）ちょうどと、1 バイト長いもの。曲名は空 | 前者はそのまま、後者は切れる。接尾辞なしと全文の接尾辞つきの要素が、どちらも V23 以内 | 単体 | ○ |
| TS-164 | 1 | `computeDiff` | 再生できない形式（X3-2。X6-10） | `isPlayable` が偽の保護付きでない曲（記録なし）。`protected` が真で `isPlayable` が偽の曲。`isPlayable` を渡さない呼び出し | 前者は `unsupported` だけに入り、`toAdd` に入らない（転送しない）。後者は `unplayable` だけに入り、`unsupported` にも `toAdd` にも入らない（転送しない）。渡さなければ `unsupported` は空 | 単体 | ○ |
| TS-165 | 1 | `computeDiff` | 再生できない形式の曲の記録（X3-2。X6-10） | `SyncRecord` を持ち、`size` が違い、`isPlayable` が偽の曲。同じ値で `presentIds` にあり、`isPlayable` が偽の曲 | どちらも `unsupported` だけに入り、`toReplace` に入らず、`unchangedCount` にも数えない（転送しない）。どちらも `toRemove` に入らない（端末にある曲は消さない） | 単体 | ○ |
| TS-166 | 1 | `toUnplayable` | 理由の写し（X3-11） | 保護付きの曲だけ（第 2 引数は空の一覧）。保護付きの曲と、再生できない形式の曲（第 2 引数） | 保護付きは `protected`、再生できない形式は `unsupported_format`（保護付きが先）。曲名・`kind`・拡張子を写す。第 2 引数に既定値は無い（review-15） | 単体 | ○ |
| TS-167 | 2 | plist の読み手 | `Track Type` が `File` でない（X2-12） | `Track Type` が `URL` で `Location` を持つトラック | 場所なし（`Location` を復号しない） | 単体 | ○ |
| TS-168 | 2 | `decodeFileUrl` | 共有名が空（X2-12） | `file://nas.example.invalid/`、`file://nas.example.invalid//a.mp3` | どちらも復号不能：ホストを直せない | 単体 | ○ |
| TS-169 | 2 | plist の読み手 | プレイリストの ID の検査（X2-9。X2-12） | `Playlist Persistent ID` が無い、15 桁、17 桁、16 進以外、小文字 16 桁のプレイリスト | 小文字は大文字になる。ほかは落とし、`malformedEntries` に 1 件ずつ数える | 単体 | ○ |
| TS-170 | 2 | plist の読み手 | 重複で捨てたトラックへの参照（X2-8） | 同じ `Persistent ID` の 2 トラック（`Track ID` が 1 と 2）と、`Track ID` 2 を含む並び | 並びの項目が残り、先に残したトラックを指す。`droppedTrackRefs` に数えず、`duplicatePersistentIds` は 1 | 単体 | ○ |
| TS-171 | 2 | plist の読み手 | 診断の数え方（X2-6） | プレイリスト項目の `Track ID` が整数でない（文字列） | `malformedEntries` は 1（1 つの項目を 1 回だけ数える） | 単体 | ○ |
| TS-172 | 2 | plist の読み手 | 診断の件数（X2-13） | 重複で捨てたトラック、ID 無しのトラック、ID の無いプレイリストを含む文書 | `trackCount`、`playlistCount` は残した件数（捨てた分を含まない） | 単体 | ○ |
| TS-173 | 3 | 再送の防止（`NonceStore`） | 並行の呼び出し（X4-1。review-15） | 同じ（device_id、nonce）を複数のスレッドから同時に記録する | 新規として通る（真）のは 1 回だけ | 単体 | ○ |
| TS-174 | 3 | 再送の防止（`NonceStore`） | 並行の追加と掃除（X4-1） | 異なる nonce を複数のスレッドから大量に追加する（上限は引数で小さくする。期間を過ぎた記録を含む） | 例外が出ず、全て追加できる。上限を超えた古い記録と期間を過ぎた記録は、再び追加すると新規になる（`recordIfNew` が真）。残るべき記録は欠けない。件数は読まず、公開の振る舞いで確かめる（review-15） | 単体 | ○ |
| TS-175 | 3 | P6 | 書式の誤り（X4-6） | P5 を受けていない状態の P6。`device_id` が P5 と違う P6 | 400（書式の誤り）。状態を変えず、試行に数えない | 単体 | ○ |
| TS-176 | 3 | SPAKE2 | 端末側の Y の検査（X4-7） | PC が返す Y が曲線上にない点、または無限遠点 | 端末は MAC_d を作らず失敗にする | 単体 | ○ |
| TS-177 | 3 | 確認コードの表示 | SHA-256 の値からの表示（X4-5） | 既知の SHA-256 の値（32 バイト）と、同じ値になる SPKI の DER | `formatFingerprintOfHash` は先頭 12 バイトの 24 桁を 4 桁ずつ空白で区切る。`formatFingerprint` は同じ結果 | 単体 | ○ |
| TS-178 | 2 | plist の読み手 | `Folder` の除外の印（X2-1） | `Folder` が真のプレイリスト | `PlaylistExclusion.Folder` の除外の印が付く（review-15） | 単体 | ○ |
| TS-179 | 2 | plist の読み手 | 除外の条件が重なるとき（X2-1） | `Master`・`Distinguished Kind`・`Folder` をすべて持つプレイリスト。`Distinguished Kind` と `Folder` を持つプレイリスト | 印は `Master`、`Distinguished Kind`、`Folder` の順に優先して 1 つだけ付く（前者は `Master`、後者は `Distinguished Kind`。review-15） | 単体 | ○ |
| TS-180 | 1 | マニフェストの読み | 本文の形 | 空文字、JSON でない文字列、最上位が配列の本文 | いずれも「壊れた」（review-15） | 単体 | ○ |
| TS-181 | 1 | マニフェストの読み | 数が型の範囲を超える | `size` が `Long` の範囲を超える曲。`track_number` が `Int` の範囲を超える曲 | 例外を投げず「壊れた」（review-15） | 単体 | ○ |
| TS-182 | 1 | P8 の本文の読み（`ProgressReport`） | 正しい本文 | `stage` と 4 つの数（`done_tracks`、`total_tracks`、`done_bytes`、`total_bytes`）が正しい本文 | 値が読める（TD-82 は desktop の検査と保持。review-15） | 単体 | ○ |
| TS-183 | 1 | P8 の本文の読み（`ProgressReport`） | 数が型の範囲を超える | `done_tracks`・`total_tracks` が `Int` の範囲外、`done_bytes`・`total_bytes` が `Long` の範囲外 | 例外を投げず null（review-15） | 単体 | ○ |

削った ID：TS-15、TS-24、TS-34、TS-37、TS-39、TS-52、TS-55、TS-66、TS-79、TS-88、TS-89、TS-90、TS-94、TS-100、TS-117、TS-129、TS-144、TS-146、TS-148、TS-151（review-13）、TS-145（review-14。区分から T3 の `result` への写しは M4 の手順で、TA-077 で確かめる）、TS-23（TS-13 の 1 件目と入力も結果も同じ。`computeDiff` の入力は `tracks` だけで、プレイリストの数は結果に現れない）、TS-130（公開の例は TD-54・TA-120 が本番の実体で確かめ、`abc` の指紋は TS-177 と同じ）（review-15）

## 3 束の終わりに回すテストの範囲

| 束 | 回す範囲 | 時期 |
|---|---|---|
| 1 | :shared の単体テスト（同期の部分：manifest、diff、path、m3u8、result） | 束 1 の実装の終わりに 1 回 |
| 2 | :itunes の単体テスト（plist、location、model。性能の TS-102 は含めず、`performanceTest` で別に回す） | 束 2 の実装の終わりに 1 回 |
| 3 | :shared の単体テスト（認証の部分：crypto） | 束 3 の実装の終わりに 1 回 |

- 束の途中はコンパイルだけ。失敗したら直して、落ちたテストと同じモジュールのテストをもう 1 回回す（test-plan.md）。全件は回さない
- TS-102 を除く全件は CI が回す

## 4 CI の対象外のものと、その代わり

束 1〜3 のケースは、TS-102 を除いて CI（ubuntu）で動く。対象外は次のとおり。

| 対象外 | 理由 | 代わり |
|---|---|---|
| 実物の iTunes の XML の読み取り（Windows の実環境） | 実物の XML は個人の曲名とパスを含み、repo に置けない | TS-86 の合成（2026-11-06 の実物の確認を写した形）。実物の確認は B2 の「実物の確認」に記録済み |
| ネットワーク上のドライブの `Location` の形、各プレイヤーでのパスの解釈 | 実環境と実機が要る | 合成の期待値（TS-99）と、C-2・C-3（Android 14 の実機）の確認の表 |
| 端末が m3u8 を MediaStore へ書く処理、Keystore の HMAC（C-12） | 端末の実行環境が要る | 束 1 は行の内容までを確かめ、書き込みは android の担当分（TA-）と C の表で確かめる |
| 規模のテスト（TS-102）の時間の絶対値 | CI の機械差で揺れる | JUnit のタグ `performance` で通常の `test` から外し、:itunes の `performanceTest`（手元用）で回す。余裕を持たせた上限で測る。揺れたら絶対値でなく、件数に対する伸び方で見直す |
| 暗号の実体（Bouncy Castle、Android Keystore）の端末での挙動 | 端末の実行環境が要る | 束 3 は固定の実体と試験ベクターまで。Keystore は C-12 |

## 5 食い違い・不足

- review-11 で解消：観点の追加（TS-131〜TS-142）、境界の期待値（V2・V43 は達したら、V3 ちょうどは無効、起動より前は拒否）、nonce の上限は引数、試験ベクターの置き場は :shared
- review-12 で追加：TS-143〜TS-152（review-13 で一部を削除。X3-6 の MIME、区分と `result`、結果の関数、V4 の間の `pairing_locked`）。TS-133・TS-134 は関数ごとに分けた。X4-5 の実体の既知の値は TD-54、TA-120
- 2 周目（2026-11-09）：X6-10 を、転送する扱いから転送しない扱いに改めた。TS-164・TS-165 の期待を直した（件数は変わらない）
- review-15 で追加：TS-161〜TS-177（X2-6・X2-8・X2-9・X2-12・X2-13、X3-1・X3-2・X3-6・X3-11、X4-1・X4-5・X4-6・X4-7、X6-10 の shared の分）。直したのは TS-49（X3-9）、TS-96（X2-10）、TS-103（X4-2）、TS-128、TS-140（X4-6）、TS-142（X4-8）、TS-147（X3-7）。desktop の分（X2-1・X2-2・X2-3）は test-plan-desktop.md
- review-15 のコードの整理（2026-11-09）：テストを消したため TS-23・TS-130 を削った。TS-102 は `performanceTest` で別に回す（CI の列は ×）。TS-166 の「第 2 引数を省いた呼び出し」を、既定値を外したため削った。TS-174 は件数を読まず公開の振る舞いで確かめる形にした。番号の無かったテストに TS-178〜TS-183 を足した（束 1 が 4 件、束 2 が 2 件。計 160 件）。実物の形のテストは観点ごとに分け、すべて TS-86 に当てる
