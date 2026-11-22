# KudagiTune 開発者向け説明書

対象の版：設計の第 5 稿（2026-11-06）に review-15（2026-11-09）と、同日の 2 周目の決定（再生できない形式は転送しない、ライセンスの NOTICE と Java 実行環境の `legal/`）を反映したもの。土台は 2026-11-06

設計の版が進んだら、ここも合わせて直す。

## 1. repo の構成

1 つの repo を Gradle のマルチプロジェクトで構成する。モジュールは 4 つで、増やさない。

| モジュール | 種類 | 依存 | 役割 |
|---|---|---|---|
| `shared` | Kotlin Multiplatform（JVM と Android） | なし | マニフェストと API の型、差分の計算、m3u8 の生成、保存先のパス、ペアリングと署名の手順、エラーの種類 |
| `itunes` | JVM | shared | iTunes の XML（plist）の読み取り、`file://` の復号、プレイリストの展開 |
| `desktop` | JVM（Compose Multiplatform） | shared、itunes | HTTP サーバー、mDNS の広告、鍵の保管、トレイと画面、設定とログ |
| `android` | Android（Jetpack Compose） | shared | HTTP クライアント、Room、MediaStore への書き込み、同期の実行、画面、権限 |

依存の向きは次のとおり。

```
desktop ──▶ itunes ──▶ shared ◀── android
   └────────────────────▲
```

- shared は誰にも依存しない。itunes と android は互いを知らない。desktop は android を知らない
- 循環と、desktop から android への依存は禁止する。Gradle の依存の宣言で保ち、CI で確かめる
- 判断（差分、m3u8、署名の手順、除外の規則）は shared と itunes に置く。desktop と android は薄く保つ
- shared は OS の API、ファイルと通信の入出力、時計と乱数の直接の利用を持たない。時刻と乱数は引数で受ける

### パッケージの概要

ルートのパッケージ名は `playlistsyncfox`。製品の正式名は KudagiTune に決まったが（review-15）、改めたのは表示名（アプリ名、画面、通知、文書）だけで、パッケージ名、アプリの ID、`psfox`（ヘッダ名、mDNS のサービス名）、保存先のフォルダ名（V22）などの識別子は変えない。下はその下の相対名。

| モジュール | パッケージ | 責務 |
|---|---|---|
| shared | `shared.manifest` | マニフェストと、P1・P3 の要求と P7 の応答の本文の型 |
| shared | `shared.diff` | 差分の計算（足す、置き換える、消す） |
| shared | `shared.path` | 保存先のパスの組み立て、使えない文字の置き換え |
| shared | `shared.m3u8` | m3u8 の生成 |
| shared | `shared.result` | エラーの種類、同期の結果、P8 の要求の本文の型 |
| shared | `shared.auth` | ペアリングの手順、署名、再送防止 |
| shared | `shared.crypto` | `CryptoProvider` の定義、確認コードの表示形式 |
| itunes | `itunes.plist` | plist XML の読み手 |
| itunes | `itunes.location` | `file://` の復号 |
| itunes | `itunes.library` | トラックとプレイリストの型、展開 |
| desktop | `desktop.server` | Ktor のルート、要求の検査、Range |
| desktop | `desktop.vault` | DPAPI による鍵の保管、証明書 |
| desktop | `desktop.discovery` | mDNS の広告（JmDNS） |
| desktop | `desktop.settings` | 設定とログ |
| desktop | `desktop.ui` | トレイと画面 |
| desktop | `desktop.watch` | XML の監視と読み直し |
| desktop | `desktop.crypto` | `CryptoProvider` の PC 側の実体 |
| android | `android.db` | Room の定義と移行 |
| android | `android.storage` | MediaStore の書き込みと削除 |
| android | `android.sync` | 同期の実行、前面サービス、WorkManager |
| android | `android.net` | Ktor のクライアント、証明書の固定 |
| android | `android.vault` | Keystore による鍵の保管、`CryptoProvider` の端末側の実体 |
| android | `android.discovery` | NSD の発見、手入力の検査 |
| android | `android.ui` | 画面 S1〜S6、権限の要求 |
| android | `android.notify` | 通知 |
| android | `android.log` | ログの書き込み、閲覧、共有 |

## 2. 必要なもの

| もの | 内容 |
|---|---|
| JDK | 定義ファイルで版を固定する（版は repo の定義が正。ここには書かない） |
| Android SDK | 同上。全モジュールの構成に要る（shared が Android の対象を持つため。desktop だけの作業でも要る） |
| Gradle | repo の wrapper（`gradlew`）を使う。手元に Gradle を入れない |
| Windows | desktop の配布物（MSI と zip）は Windows の実行環境で作る。実行も Windows |
| 実機 | Android 11 以降の端末。実機の確認の表を埋めるのに要る。最初の実機確認は Android 14 の端末。C-6・C-10 は 15・16 の端末を用意してから |

- Kotlin、Ktor、Compose の版は version catalog の 1 か所で持つ
- iOS など、他の対象は足さない

## 3. ビルドと実行

コマンドはプロジェクト名を指定して実行する。2 と 4 は CI で確認済み（2026-11-06）。1 は 2026-11-09 の実機の確認で使った。手元では Android SDK が要る。

1. desktop を実行する：`./gradlew :desktop:run`
2. android のデバッグビルドを作る：`./gradlew :android:assembleDebug`
3. 作った APK を端末に入れる。入れ方は手元の方法でよい（製品と通常の開発の手順では adb を使わない。実機の確認の C-14 の一部だけ使う。第 9 節）
4. 全モジュールのコンパイルを確かめる：`./gradlew build`

- 変更した層のテストだけを手元で回す。全層は CI で回す
- desktop の起動には、iTunes の XML の共有を有効にした iTunes が要る（PC 版の説明書を参照）

## 4. テスト

### 層ごとの方針

| 層 | 方針 |
|---|---|
| shared | 純粋関数の単体テストが中心。差分、マニフェストの直列化、m3u8、署名、鍵の導出、再送防止、ペアリングの状態遷移 |
| itunes | 純粋関数の単体テスト。日本語名や記号を含む `file://` の復号、XML の欠損、入れ子のプレイリスト。試料は小さな合成の XML |
| desktop | 薄く保つ。サーバーの結線（鍵なしの拒否など）を少数のテストで確かめる。画面の見た目のテストは持たない |
| android | 薄く保つ。ワーカーの手順、HTTP クライアント、ViewModel、手入力の検査は JVM の単体テストで試す。Room の移行は端末かエミュレーターの計測テスト（CI の外）で試す。権限と通知は実機で確かめる |

- 試料の XML に、個人の曲名とパスを含めない
- 時刻と乱数は引数で渡し、テストで固定する
- 公開のテストベクトルがある暗号の方式は、それで検算する
- 実機が要るもの（端末の実行環境が要るテスト）は CI の対象外とし、確認の表に残す
- テストの計画（束ごとのケースと観点）は repo の外（第 7 節）にある

### 実機の確認の表

- 実機でしか確かめられない項目は、C-1〜C-18 の表にまとめる
- 表は repo の `docs/device-check.md` に置く。各項目に結果と日付を記録する
- 項目の概要は次のとおり。状態はいずれも設計の時点のもの。C-6・C-10 は今後対応（Android 14 で確認するため）

| 番号 | 確かめること |
|---|---|
| C-1 | mDNS で端末が PC を見つけるか |
| C-2 | `MediaStore.Files` への insert と索引の実機確認（m3u8 の拡張子で）：`Music/<アプリ名>/` の相対パス、MIME `audio/x-mpegurl` で insert し、MediaStore のプレイリストとして索引されるか。相対パスが解かれるか |
| C-3 | 各プレイヤーでプレイリストが見え、順に再生できるか |
| C-4 | Tailscale の MagicDNS 名で届くか（名前を引けることは確認済み。名前は Tailscale のアプリで見て端末に入力する） |
| C-5 | USB テザリングで届くか |
| C-18 | Bluetooth テザリングで届くか。方式が「Bluetooth テザリング」と記録されるか |
| C-6 | Android 15 以降の 6 時間の上限で止まり、続きから再開できるか |
| C-7 | 入れ直し後の「以前の曲の整理」で同意の画面が出るか |
| C-8 | 従量制でないネットワークでの自動の起動 |
| C-9 | 保護付きの .m4p が転送されずに結果の一覧に出るか。ALAC・AIFF が転送され、Poweramp で再生できるか |
| C-10 | Android 16 以降の長い処理の割り当て時間 |
| C-11 | Ktor の OkHttp エンジンで固定した証明書だけを信頼できるか |
| C-12 | 既存の m3u8 の書き換えと、Keystore での HMAC、Keystore の別名の削除 |
| C-13 | PC の実環境（DPAPI、Run キー、Windows の WatchService、仮想アダプタと JmDNS） |
| C-14 | 端末の通知、権限、容量不足の案内、ログの共有と保存、時間切れの停止の模擬 |

## 5. CI

GitHub Actions で動かす。細部は変わりうる。

| 契機 | 内容 |
|---|---|
| プルリクエストと main への push | ubuntu の環境に JDK と Android SDK を用意し（版は repo の定義が正）、`./gradlew build :android:assembleDebug`（全モジュールのコンパイルとテスト。端末が要る計測テストは含まない。android のデバッグビルド）、依存の向きの検査 |

- 配布物（署名つき APK、MSI、zip）は CI では作らない。鍵のある手元の PC で作る（第 6 節、ADR 0017）
- repo が private の間も、同じ流れが動く

## 6. Release の手順

1. `v<major>.<minor>.<patch>` の形で版のタグを打つ（手動）
2. 手元で desktop の MSI と zip と APK を作る。`./gradlew :desktop:packageMsi :desktop:createDistributable :android:assembleRelease`（MSI は `desktop/build/compose/binaries/main/msi/`、zip にするのは `app/KudagiTune/`、APK は `android/build/outputs/apk/release/`）。版は `desktop/build.gradle.kts` の `packageVersion` と `android/build.gradle.kts` の `versionName` をそろえる。名前は `KudagiTune-<版>.msi`、`KudagiTune-<版>-windows.zip`、`KudagiTune-<版>.apk` にする
3. APK の署名を `apksigner verify --print-certs` で確かめる。署名なし（鍵の設定が無い環境）の APK は添えない
4. 下書きの Release を作り、配布物とチェックサム（SHA-256 の一覧）を付ける
5. APK を実機に入れ、確認の表の該当項目を確かめる（手動）
6. repo が public であることを確かめる
7. 下書きを公開する（手動）

- repo は最初 private。公開の前に public にする。手順は次のとおり
  1. 履歴と設定に、鍵、認証情報、個人の曲名・パス、実 IP、実ドメインが無いことを確かめる
  2. ライセンスの一覧が配布物に入っていることを確かめる
  3. GitHub の設定で可視性を public に変える
- Release の本文には、変更の要点、PC の導入手順、「提供元不明のアプリ」の許可の手順を書く。アプリ内で更新を確認しないため、案内は本文が担う
- チェックサムは取り違えの確認用で、署名の代わりではない
- PC の配布物はコード署名なし。SmartScreen の警告が出ることを本文に書く
- APK の署名の鍵は repo に入れない。失うと更新できないため、別の場所にバックアップを 1 つ持つ（ADR 0017）
  - 鍵は PKCS12 で、別名は `kudagitune`。置き場所とパスワードは、利用者の `~/.gradle/gradle.properties` の `kudagitune.signing.storeFile` と `kudagitune.signing.storePassword` に書く。無ければ release は署名なしでビルドされる
  - バックアップは AWS の Parameter Store の SecureString。鍵（base64）は `/kudagitune/release-keystore`、パスワードは `/kudagitune/release-keystore-password`。別の PC で作るときは、ここから戻して上の 2 つを書く

## 7. 設計書の場所と番号の読み方

設計書（決定 D と用語、要件 F・N、論点 Q、基本設計 B、詳細設計 X、テスト計画）と ADR は、2026-11-14 に repo の外の非公開のメモへ移した。repo の `docs/` に残るのは、説明書（`manual/`）と実機の確認の表（`device-check.md`）だけ。

コードのコメントや説明書に出てくる番号の読み方は次のとおり。

| 記号 | 意味 |
|---|---|
| D | 決定（概要設計で決めた方針。覆さない） |
| F | 機能要件 |
| N | 非機能要件 |
| Q | 未決の問い |
| B | 基本設計の節。B1-3 は「基本設計の B1 節の 3 項」 |
| X | 詳細設計の節。X1 は索引とパッケージ、X2〜X7 は各モジュール |
| V | 値の表。ペアリングコードの桁数、ログの上限などの暫定の値 |
| M | モジュール（M1〜M4。基本設計の B1） |
| A | 詳細設計の X1-4 に置いた値の候補（A1〜A19）。値は V34 以降に載せてある（A1・A2・A19 は識別子） |
| X-n | 詳細設計の決定（未付番）。X1-6 の表に集めてある |
| E | エラーの種類（E1〜E9） |
| T | 端末の同期状態の表（Room） |
| S | Android の画面 |
| W | PC の画面 |
| K | 鍵と保管場所 |
| P | API のルート（P1〜P8） |
| C | 実機の確認の表（C-1〜C-18） |
| H | PC 側のペアリングの状態（H0〜H3。詳細設計の X4） |
| TS、TD、TA | テスト計画のケースの番号（shared と itunes、desktop、android）。束ごとの範囲はテスト計画にある |
| J | レビューの決定の印。定義は repo の外にあり、取り込んだ先の設計書の節に書いてある |

- 値は V の番号で参照し、本文に直書きしない（設計書の規則）
- 確かめていないことには「（未確認）」と添えてある

## 8. ライセンス一覧の生成

- Gradle のプラグイン Licensee（`app.cash.licensee`）で、使う OSS の一覧をビルド時に生成する
- Licensee は `artifacts.json` を出力する。Android ではアセットに同梱する
- Licensee の一覧に出るもの：Kotlin、Ktor、Room、Compose、JmDNS など。PC が同梱する JVM（OpenJDK のビルド）は Licensee の一覧に出ないため、PC の画面ではライセンスの最後に足して出す（下の `legal/` の項目）
- 依存の jar の `META-INF/NOTICE*`（Apache 系の NOTICE）は、ビルドのときに集めて配布物に同梱する（PC と端末の両方）。画面では部品の全文と一緒に出す
- PC の同梱の Java 実行環境は、実行環境の `legal/` を実行時に読み、モジュールごとの全文を出す。全文の写しは repo に置かない
- 表示の場所：PC は画面の「ライセンス」ボタン、Android は設定の画面
- 内容は名前、版、ライセンスの種類、全文。全文は配布物に同梱し、名前と URL だけにしない（`artifacts.json` は許諾の名前と URL を持つが全文は持たないため、全文は別に同梱する。review-15）
- 依存を足したときは、一覧に入ることをビルドで確かめる

## 9. やらないこと

| やらないこと | 理由 |
|---|---|
| iTunes の COM の利用 | XML の読み取りだけで足りる。iTunes の操作を持たない |
| 曲の形式の変換 | 変換せずに転送する。再生できるかの判定はせず、プレイヤーに任せる（保護付きの .m4p だけは転送せず、結果の一覧に出す） |
| iTunes への書き戻し | 読み取り専用。ライブラリを変えない |
| adb の利用と同梱 | 端末は PC に取りに来る方式。製品と通常の開発の手順では adb を使わず、同梱もしない。使うのは実機の確認の C-14 の一部（時間切れの模擬）だけ |
| アプリ内の更新の確認 | 新しい版は Releases で案内する |
| M1 への永続化の追加 | Room は android だけに置く |
| iOS など他の対象 | shared の対象は JVM と Android だけ |
| Apple Music アプリの対応 | 最初の版は Windows の iTunes 12.x だけ |
