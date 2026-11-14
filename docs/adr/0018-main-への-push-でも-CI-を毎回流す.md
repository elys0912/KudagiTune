---
id: "0018"
title: "main への push でも CI を毎回流す"
kind: cost
form: short
status: accepted
date: 2026-11-14
approved: 2026-11-14
supersedes: "0015"
superseded-by:
tags: [ci, github-actions, cost]
---

# 0018 main への push でも CI を毎回流す

## 背景

0015 では、private の repo の Actions の無料枠（月 2,000 分）を節約するため、PR で検査したコミットを main への push で検査し直さないことにした。2026-11-14 に repo を public にした。public の repo では、標準のランナーの分数は課金されないため、0015 の前提が無くなった。

## 選んだ案

main への push でも、毎回すべての検査を流す。マージ済みの PR に紐づくかを調べる仕組みは消す。PR の検査は今までどおりで、同じ PR で新しい push があれば古い実行を打ち切る。

## 捨てた案

- 0015 のまま残す：動いてはいるが、理由の無くなった仕組みが残る。PR の検査の後に main が先に進んでいた場合、マージした結果の組み合わせが検査されない

## 理由

分数の費用が無くなったので、節約より検査の漏れの無さを取る。ワークフローも単純になる。

## 悪い面

main への push のたびに、PR と同じ検査の時間（2 分ほど）がかかる。repo を private に戻すと、また無料枠を使うようになる。

## 参照

- 置き換え元：0015（main への push の CI は、PR で検査済みのコミットでは流さない）
- 料金：GitHub Actions の billing（public の repo の標準のランナーは無料）
