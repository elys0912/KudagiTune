---
id: "0009"
title: "端末の曲は Music 配下に MediaStore 経由で置き、m3u8 を MediaStore.Files に登録する"
kind: architecture
form: short
status: accepted
date: 2026-11-10
approved: 2026-11-05
supersedes:
superseded-by:
tags: [android, mediastore, m3u8]
---

# 0009 端末の曲は Music 配下に MediaStore 経由で置き、m3u8 を MediaStore.Files に登録する

## 背景

端末へ取得した曲とプレイリストをどこにどう置くかを決める必要があった。保存先の細部（`Music/<アプリ名>/<アーティスト>/<アルバム>/`）は 2026-11-06 に確定した。設計の途中の確認で、`MediaStore.Audio.Media` には playlist の MIME を登録できず（例外になる）、`MediaStore.Files` なら `Music/` に置けると分かった。

## 選んだ案

曲は `Music/<アプリ名>/` 配下に MediaStore 経由で書く。プレイリストは m3u8 を正とし、`MediaStore.Files` に MIME `audio/x-mpegurl`（拡張子は m3u8 のまま）で登録する。MediaStore への反映は端末の索引に任せる。

## 捨てた案

- 保存先を SAF で利用者が選ぶ：場所の選択の画面と権限の保持が要る
- 索引されない端末では MediaStore の非推奨の API で直接書く：非推奨の API に頼ることになる
- `MediaStore.Audio.Media` に playlist として登録する：MIME を受け付けず、登録できない

## 理由

MediaStore 経由なら権限が少なく、プレイヤーから見える。索引は MIME で決まるので、`MediaStore.Files` への登録で足りる。

## 悪い面

プレイヤーによって m3u8 の読み方や反映の時機が異なり、実機で確かめる必要がある。アプリを入れ直すと、MediaStore 経由で置いた曲の所有が切れる（0010）。

## 参照

- 設計書：docs/design-overview.md の D3、docs/open-questions.md の Q4・Q7、docs/basic-design-b3.md
- 設計時の判断：2026-11-05 の設計レビュー（review-01 の J6、review-05 の J18、review-03 の J14）
- コミット：c9753ff（端末側の保存と同期の実行。MediaStore.Files への登録を含む）
