---
id: "0007"
title: "配布は GitHub Releases の APK と jpackage の zip にし、アプリ内の更新確認はしない"
kind: architecture
form: full
status: superseded
date: 2026-11-10
approved: 2026-11-05
supersedes:
superseded-by: "0016"
tags: [distribution, github, release]
---

# 0007 配布は GitHub Releases の APK と jpackage の zip にし、アプリ内の更新確認はしない

## 背景

決定は 2026-11-05 に確定した。

最初は利用者を自分だけと想定していたが、GitHub で公開する方針になった。配布先、配布物の形、更新の知らせ方を決める必要があった。
repo は最初は private で、公開の前に public にする。

## 決定の要因

- 最初の版では、利用者の導入の手間と配布側の保守を小さくする
- 新しい版を知るために、アプリが外へ問い合わせる通信を持たせない
- 将来 Play ストアへ対応する余地は残す

## 検討した選択肢

### 案 A：GitHub の Releases で配る。PC は jpackage のアプリイメージの zip、コード署名なし

- 良い点：追加の導入が無く、Windows の実行環境で CI が作れる。Releases への添付まで同じ場所で行える
- 悪い点：利用者は zip を展開して使う。署名が無いので、OS の警告が出る可能性がある

### 案 B：PC をインストーラで配る

- 良い点：導入の体験がよい
- 悪い点：WiX などの前提が増える

### 案 C：最初から Play ストアと署名つきの公開を前提にする

- 良い点：利用者が見つけやすく、更新も自動になる
- 悪い点：公開の手続きが最初から要る

## 決定

案 A。APK と PC の zip は GitHub の Releases に置く。タグ（`v<major>.<minor>.<patch>`）の push を契機に CI が下書きの Release を作り、公開は作者が手で行う。
アプリ内で更新の確認（外への通信）はしない。新しい版は Releases の案内に任せる。Play ストアは将来の対応とし、最初の版では行わない。

## 結果

### 良い面

- 追加の導入物が無く、CI で配布物を作れる
- アプリが外へ問い合わせる通信を持たない
- 下書きを確認してから公開できる

### 悪い面

- 利用者が更新に気づく手段は Releases の案内だけになる
- コード署名が無いので、PC の zip は OS の警告が出る可能性がある
- Play ストアに出すときは、署名と配布の形を改めて決める必要がある

## 適用範囲と決めないこと

- 効く範囲：配布先、配布物の形、更新の知らせ方、公開の手順
- 決めないこと：APK の署名鍵の保管の細部、private から public へ切り替える時期

## 参照

- 設計書：docs/design-overview.md の D12、docs/open-questions.md の Q12、docs/basic-design.md の B1-6・B1-7（決定 7、12）
- 設計時の判断：2026-11-05 の設計レビュー（review-01 の J4、review-03 の J13）
- コミット：fccaa39（CI の骨組み）
