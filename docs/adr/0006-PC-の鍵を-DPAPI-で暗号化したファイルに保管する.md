---
id: "0006"
title: "PC の鍵を DPAPI で暗号化したファイルに保管する"
kind: architecture
form: short
status: accepted
date: 2026-11-10
approved: 2026-11-05
supersedes:
superseded-by:
tags: [security, dpapi, windows]
---

# 0006 PC の鍵を DPAPI で暗号化したファイルに保管する

## 背景

PC は、ペアリングした端末の鍵と自分の TLS の鍵を保管する必要がある。設計の途中で、JNA に Windows の資格情報マネージャーを呼ぶ束縛が見当たらず、1 項目の上限が 2560 バイトであることが分かった。

## 選んだ案

利用者ごとの DPAPI（JNA の `Crypt32Util`）で暗号化したファイル `secrets.bin` を、設定の置き場に置く。

## 捨てた案

- 資格情報マネージャーを自前の JNA の束縛で呼ぶ：構造体の束縛を自分で持つ必要がある
- 鍵を平文のファイルに置く：鍵を平文に置かない要件に反する

## 理由

DPAPI の束縛は JNA に既にあり、利用者ごとに OS が保護する形になる。

## 悪い面

別の Windows ユーザーや別の PC へそのまま移せない。移すときは再ペアリングが必要になる。

## 参照

- 設計書：docs/basic-design.md の B1-4（資格情報の保管の行）
- 設計時の判断：2026-11-05 の設計レビュー（review-05 の J17）
- コミット：3460d31（PC 側のサーバーと鍵の保管）
