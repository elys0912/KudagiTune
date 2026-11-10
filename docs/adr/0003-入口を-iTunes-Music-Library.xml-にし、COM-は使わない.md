---
id: "0003"
title: "入口を iTunes Music Library.xml にし、COM は使わない"
kind: architecture
form: short
status: accepted
date: 2026-11-10
approved: 2026-11-05
supersedes:
superseded-by:
tags: [itunes, xml, apple-music]
---

# 0003 入口を iTunes Music Library.xml にし、COM は使わない

## 背景

曲とプレイリストの情報を PC のどこから取るかを決める必要があった。当初の案は iTunes 12.x と Apple Music アプリ（Windows）の両方に対応するもので、入口は両方に共通の XML だった。iTunes 12.x は、設定で共有を有効にすると XML が自動で更新される。

## 選んだ案

対応は iTunes 12.x だけにし、入口は `iTunes Music Library.xml`（plist 形式）にする。COM は使わない。Apple Music アプリは、書き出しの機能が無い見込みのため保留にし、手段が見つかったときに足す。

## 捨てた案

- iTunes 12.x を COM で読み、書き出しなしで即時に反映する：後から足す余地としては残すが、最初の版では入れない
- iTunes 12.x と Apple Music アプリの両方を最初から対象にする：Apple Music アプリ側に書き出しの機能が無い見込みになった

## 理由

一方向の同期（0004）なので、iTunes への書き戻し（COM 前提）が要らない。XML だけで曲・プレイリスト・展開済みのスマートプレイリストが読める。

## 悪い面

Apple Music アプリの利用者には使えない。XML の更新を待つため、iTunes の操作の反映に間が空く。XML が古いときは画面で警告する形になる。

## 参照

- 設計書：docs/design-overview.md の D1、docs/open-questions.md の Q11、docs/requirements.md の F1-1・F1-3
- コミット：6666cc8（iTunes 12.x の XML の実物の確認の結果を設計書に写す）
