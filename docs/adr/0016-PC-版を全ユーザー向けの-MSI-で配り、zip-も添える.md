---
id: "0016"
title: "PC 版を全ユーザー向けの MSI で配り、zip も添える"
kind: architecture
form: full
status: accepted
date: 2026-11-14
approved: 2026-11-14
supersedes: "0007"
superseded-by:
tags: [distribution, github, release, windows]
---

# 0016 PC 版を全ユーザー向けの MSI で配り、zip も添える

## 背景

0007 では、PC 版を jpackage のアプリイメージの zip で配ると決めた。インストーラーは「WiX などの前提が増える」として採らなかった。その後、repo を public にし、一般の利用者に配る前提になった。zip では、利用者が置き場所を自分で決めて展開する必要がある。`C:\Program Files` に置くなら、管理者の権限で展開しなければならない。スタートメニューへの登録も、Windows の「アプリ」からの削除もできない。

ビルドに使う Compose Multiplatform の Gradle プラグインには、MSI を作るタスクがある。必要な WiX も、ビルドの中でプラグインが取ってくる。手元で WiX を入れる手間は、0007 で見込んだほど大きくない。

0007 のうち、Releases で配ること、アプリ内で更新を確かめないこと、APK の扱いは変えない。変えるのは PC 版の配布物の形だけ。

## 決定の要因

- 一般の Windows のアプリと同じ手順で、導入・更新・削除ができる
- 置き場所は、Windows で見慣れた `C:\Program Files` にする
- 管理者の権限が無い PC でも使える道を残す
- ビルドする PC に入れる前提を増やさない

## 検討した選択肢

### 案 A：全ユーザー向けの MSI（`C:\Program Files` に入る）と、zip を併せて配る

- 良い点：スタートメニューに登録され、Windows の「アプリ」から削除できる。版を上げた MSI で上書きして更新できる。管理者の権限が無い PC では zip を使える
- 悪い点：導入と更新のたびに UAC の確認が出る。配布物が 2 つになり、Release の手間が少し増える

### 案 B：ユーザーごとの MSI（ユーザーのフォルダに入る）

- 良い点：UAC の確認が出ない
- 悪い点：`C:\Program Files` には入らない。利用者から置き場所が見えにくい

### 案 C：zip のまま（0007 のとおり）

- 良い点：配布物が 1 つで、ビルドの手順も今のまま
- 悪い点：展開する場所を利用者が決める。スタートメニューへの登録と、「アプリ」からの削除ができない

## 決定

案 A。PC 版は全ユーザー向けの MSI を主な配布物にし、同じ内容の zip も同じ Release に添える。チェックサムの一覧には両方を載せる。最初の版は 1.0.0 とする。

## 結果

### 良い面

- 利用者は MSI を実行するだけで導入でき、スタートメニューから起動できる
- 削除は Windows の「アプリ」から行える
- 管理者の権限が無い PC では zip を使える

### 悪い面

- 導入と更新のたびに UAC の確認が出る
- コード署名が無いため、MSI にも SmartScreen の警告が出る
- MSI で削除しても、アプリがユーザーごとに書いたもの（設定、ログ、鍵、ログイン時の起動の登録）は残る。消すには利用者の手順が要る
- Release に添えるものが 2 つに増える

## 適用範囲と決めないこと

- 効く範囲：PC 版の配布物の形と置き場所
- 決めないこと：コード署名をするかどうか、MSI を CI で作るかどうか、Android の配布の形（0007 のまま）

## 参照

- 置き換え元：0007（配布は GitHub Releases の APK と jpackage の zip にし、アプリ内の更新確認はしない）
- Compose Multiplatform のネイティブ配布（`packageMsi`、`nativeDistributions`）：https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html
