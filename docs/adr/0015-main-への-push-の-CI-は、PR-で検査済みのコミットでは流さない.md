---
id: "0015"
title: "main への push の CI は、PR で検査済みのコミットでは流さない"
kind: cost
form: short
status: accepted
date: 2026-11-14
approved: 2026-11-14
supersedes:
superseded-by:
tags: [ci, github-actions, cost]
---

# 0015 main への push の CI は、PR で検査済みのコミットでは流さない

## 背景

この repo は private で、GitHub Actions の分数は月の無料枠（2,000 分）を、ほかの private の repo と分け合う。予算は 0 ドルなので、枠を超えると月末まで Actions が止まる。2026-11 は全体が月の枠を超えるペースで、この repo は 14 日までに約 305 分を使った。CI は PR と main への push の両方で流れ、PR をマージしたコミットでも同じ検査をもう一度流していた（push だけで 27 回、約 110 分）。無料のプランの private の repo ではブランチの保護を使えないので、main へ直接 push することもできる。

## 選んだ案

main への push では、先に小さなジョブで、そのコミットがマージ済みの PR に紐づくかを GitHub の API で調べる。紐づけば検査を飛ばし、紐づかない（直接の push）か、調べられなかったときは検査する。PR の検査は今までどおり流し、同じ PR で新しい push があれば古い実行を打ち切る。

## 捨てた案

- main への push の CI をやめる：直接の push が検査されなくなる
- コミットのメッセージ（`Merge pull request #`）で判定する：調べるジョブの 1 分も要らないが、メッセージの形に頼るので、判定を誤ることがある

## 理由

PR で検査したコミットを、もう一度検査しても結果は変わらない。直接の push は、今までどおり検査できる。

## 悪い面

main への push のたびに、調べるジョブの分（1 分）がかかる。PR の検査の後に main が先に進んでいた場合、マージした結果の組み合わせは検査されない。

## 参照

- 料金と枠：GitHub Actions の billing（private の repo の分数）
