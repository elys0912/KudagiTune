---
id: "0002"
title: "全体を Kotlin で書き、共有部分を Kotlin Multiplatform の 1 モジュールに置く"
kind: architecture
form: full
status: accepted
date: 2026-11-10
approved: 2026-11-05
supersedes:
superseded-by:
tags: [kotlin, kmp, modules]
---

# 0002 全体を Kotlin で書き、共有部分を Kotlin Multiplatform の 1 モジュールに置く

## 背景

言語の決定は 2026-11-05、モジュール構成の決定は 2026-11-06 に確定した。

PC 側（Windows）と Android 側の両方を作る。マニフェストの型、差分の計算、m3u8 の生成、ペアリングと要求の署名は、両側が同じ規則で扱う必要がある。
最初に、PC と端末のどちらも Kotlin で書き、共有部分をまとめる方針を決めた。

## 決定の要因

- 両側で同じ規則（型・差分・署名）を 1 か所に持ち、ずれを出さない
- 共有部分を実機なしでテストしたい
- 保守の対象を増やさない（個人開発）

## 検討した選択肢

### 案 A：PC と端末を別々に書く

- 良い点：各側に最適な書き方を選べる
- 悪い点：型・差分・署名が二重になり、食い違う危険が出る

### 案 B：Kotlin で書き、共有部分を Kotlin Multiplatform（KMP）の 1 モジュールに置く

- 良い点：PC（JVM）と Android で同じコードを使える。PC の画面（Compose Multiplatform）と端末の画面（Jetpack Compose）の書き方も近い
- 悪い点：KMP と Android 向けの Gradle の設定が要り、共有部分は両側で使える機能に制限される

## 決定

案 B。モジュールは `shared`（KMP）、`itunes`（JVM）、`desktop`（JVM）、`android` の 4 つとし、増やさない。依存は desktop から itunes と shared、itunes から shared、android から shared だけにする。
`shared` の対象は JVM と Android に限り、iOS など他の対象は足さない。`shared` は OS の API・時計・乱数・暗号の部品に直接触れず、引数で受ける。永続化（Room）は `android` だけに置き、`shared` に持たせない。

## 結果

### 良い面

- 差分・署名・マニフェストの型が 1 か所にあり、両側で同じ振る舞いになる
- `shared` は OS に触れないので、JVM の単体テストだけで検証できる

### 悪い面

- 共有部分に OS 依存の処理を置けず、暗号などは実体を引数で渡す形になる
- KMP と Android の Gradle の設定と版合わせの手間が要る
- 他の対象（iOS など）を足すときは、この決定を見直す必要がある

## 適用範囲と決めないこと

- 効く範囲：言語、モジュールの数と依存の向き、`shared` の制約
- 決めないこと：画面の作りの中身、通信（0001）

## 参照

- 設計書：docs/design-overview.md の D4、docs/basic-design.md の B1-3・B1-4・B1-7（決定 1〜4、11）
- コミット：fccaa39（Gradle の 4 モジュールの土台と CI の骨組み）、0d7af09（shared の同期の実装）
