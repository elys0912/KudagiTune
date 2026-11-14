---
id: "0017"
title: "APK はリリース用の鍵で手元で署名して Release に添付し、鍵のバックアップを AWS の Parameter Store に置く"
kind: architecture
form: full
status: accepted
date: 2026-11-14
approved: 2026-11-14
supersedes:
superseded-by:
tags: [distribution, release, android, signing]
---

# 0017 APK はリリース用の鍵で手元で署名して Release に添付し、鍵のバックアップを AWS の Parameter Store に置く

## 背景

v1.0.0 の Release には、PC 版の MSI と zip とチェックサムの一覧だけを添えた。APK は無い。README と Android 版の説明書は、Releases から APK を入れる前提で書いてあるので、Android の利用者は導入できない。

0007 では「タグの push を契機に CI が APK を作り、下書きの Release に添える」と決めた。この仕組みは作っていない。0016 で PC 版の形を変えたとき、Android の配布の形は 0007 のままとした。PC 版の MSI と zip は、手元でビルドして Release に添えている。

APK を署名する鍵はまだ無く、`android` のビルドにリリース用の署名の設定も無い。Android は、前の版と同じ鍵で署名された APK しか更新として受け付けない。最初に公開した APK の鍵を失うと、利用者はアンインストールしないと次の版を入れられない。0007 は鍵を repo に入れず、別の場所にバックアップを 1 つ持つと決めたが、保管の細部は決めていなかった。

## 決定の要因

- 一度公開した APK の鍵は替えられない。失わないこと、漏らさないことを最優先にする
- 鍵を置く場所を増やさない
- 継続の費用を 0 に近づける
- Release の作り方を PC 版とそろえ、手順を 1 つにする

## 検討した選択肢

### 案 A：手元でビルドして署名し、Release に添える。鍵のバックアップは AWS の Parameter Store の SecureString に置く

- 良い点：鍵は手元と Parameter Store の 2 か所だけにある。標準のパラメータは無料で、暗号化して保管できる。PC 版と同じ手順で Release を作れる
- 悪い点：値の上限が 4KB なので、鍵を文字列（base64）にして収まる大きさにする必要がある。ビルドの環境は手元の PC に限られる

### 案 B：手元でビルドして署名する。鍵のバックアップは S3 の非公開のバケットに、パスワードは Parameter Store に分けて置く

- 良い点：鍵の大きさを気にせずに済む。バケットの版の管理で、上書きしても前の版を戻せる
- 悪い点：バケットとパラメータの 2 つを管理する。少額だが費用がかかる

### 案 C：手元でビルドして署名する。鍵とパスワードを AWS の Secrets Manager の 1 つのシークレットに置く

- 良い点：扱いがいちばん素直で、大きさの制約もゆるい
- 悪い点：1 つのシークレットにつき、月 0.40 USD の継続の費用がかかる

### 案 D：CI でタグの push から作る（0007 のとおり）。鍵は GitHub の Secrets に置く

- 良い点：手元の環境に頼らずに、同じ手順で作れる
- 悪い点：鍵を GitHub にも置くことになり、置き場所が増える。ワークフローを書き足す必要がある。PC 版は手元で作っているので、手順が 2 つに分かれる

### 案 E：鍵のバックアップを Google Drive・USB メモリ・家の NAS のどれかに置く

- 良い点：AWS のアカウントに頼らない
- 悪い点：Google Drive は、ほかのファイルと同じ場所に鍵が並ぶ。USB メモリは、失くしたり壊れたりするおそれがある。NAS は古い共有の方式で動いていて、外からの接続も確かめていない

## 決定

案 A。APK はリリース用の鍵で手元でビルドして署名し、PC 版と一緒に Release に添える。鍵は repo の外に置き、AWS の Parameter Store の SecureString にバックアップを 1 つ持つ。鍵とパスワードは別のパラメータに分ける。

v1.0.0 は、APK を作ってすでにある Release に添え、チェックサムの一覧にも足す。APK の版はもともと 1.0.0 で、アプリの中身は変えないため、新しい版は出さない。

## 結果

### 良い面

- Android の利用者が Releases から導入できるようになる
- 鍵は手元と Parameter Store の 2 か所だけにあり、GitHub には置かない
- 継続の費用がかからない
- PC 版と APK を同じ手順で作り、同じ Release に添える

### 悪い面

- 署名つきの APK は、鍵のある手元の PC でしか作れない。別の PC で作るときは、Parameter Store から鍵を戻す必要がある
- Parameter Store の値の上限（4KB）に収まる大きさの鍵にする必要がある
- バックアップは AWS のアカウントに頼る。アカウントに入れなくなると、手元の鍵が唯一の写しになる
- 0007 にあったタグの push からの自動のビルドは作らない。Release の作業は手で行う

## 適用範囲と決めないこと

- 効く範囲：APK の作り方、署名の鍵の置き場所とバックアップ先、v1.0.0 への APK の追加
- 決めないこと：Play ストアへ出すかどうか、PC 版のコード署名、MSI と zip の形（0016 のまま）

## 参照

- 関係する ADR：0007（配布は GitHub Releases の APK と jpackage の zip にし、アプリ内の更新確認はしない）、0016（PC 版を全ユーザー向けの MSI で配り、zip も添える）
- アプリの署名：https://developer.android.com/studio/publish/app-signing
- Parameter Store の上限と料金：https://docs.aws.amazon.com/systems-manager/latest/userguide/parameter-store-advanced-parameters.html
