---
id: "0014"
title: "Bluetooth の経路を足す"
kind: feature
form: short
status: accepted
date: 2026-11-11
approved: 2026-11-11
supersedes:
superseded-by:
tags: [feature, network, bluetooth]
---

# 0014 Bluetooth の経路を足す

## 背景

端末が PC へつなぐ経路は、同一 LAN の Wi-Fi、Tailscale、USB テザリングの 3 方式で、いずれも端末が PC の 1 つの HTTPS サーバーへ TCP でつなぐ形に揃えている（0001）。方式ごとの違いは「つなぐ先の決め方」だけで、端末は保存した宛先、mDNS、手入力の順に試す。
Wi-Fi も USB ケーブルも無い場面で同期したいので、Bluetooth の経路を足すかを決める。

## 選んだ案

案 A（2026-11-11）。

- 案 A：Bluetooth テザリング（PAN）で同じサーバーへつなぐ。端末の「Bluetooth テザリング」を有効にして PC を PAN につなぐと、端末が PC に IP を配り、USB テザリングと同じ形になる。端末側の宛先の決め方（mDNS と手入力）と接続先の保存の列は USB と共有する
- 案 B：Bluetooth の RFCOMM で独自の通信路を作り、HTTPS の代わりにプロトコルを流す。PC と端末の両方に Bluetooth の実装を足す
- 案 C：足さない（Wi-Fi・Tailscale・USB の 3 方式のまま）

## 捨てた案

- 案 B：サーバーが 2 つになり、0001 の「方式の違いはつなぐ先の決め方だけ」が崩れる。TLS の公開鍵の固定（0005）も RFCOMM 向けに別の形で作り直すことになる。JVM の PC 側に Bluetooth のライブラリを足す必要もある
- 案 C：Wi-Fi もケーブルも無い場面で同期できないまま

## 理由

案 A は、PC と端末のコードに Bluetooth の実装を足さずに済む。端末が IP を配る点は USB テザリングと同じなので、宛先の決め方（mDNS で届けば自動、届かなければ PC の IPv4 の手入力）と保存の列をそのまま使える。違いは利用者の説明書と、同期の結果に記録する方式の名前だけになる。
記録する方式は、保存の列の種類では USB と区別できないため、接続に成功した PC の IPv4 と同じ区画を持つ端末側のインターフェースの名前で判定する（`bt` は Bluetooth、`rndis`・`usb`・`ncm` は USB、`wlan` は LAN。当たらなければ保存の列の種類）。判定は接続に成功したときだけ行う。

## 悪い面

- Bluetooth テザリングの実効速度は USB や Wi-Fi より桁で遅い（数百 kbps〜数 Mbps 程度の見込み。実機で測る）。曲の転送は時間がかかる
- 端末の Bluetooth テザリングは、端末のモバイル回線を PC に貸す機能でもある。PC 側がその回線でインターネットへ出ないよう、利用者が注意する必要がある。自動の同期は従量制でないネットワークでしか起動しない（0008）ので、Bluetooth テザリング中は通常は手動の同期だけになる
- PAN のリンクで mDNS が届くかは未確認。届かなければ手入力に頼る（USB と同じ）
- Windows の PAN への参加は、OS の設定画面で利用者が行う。アプリからは案内だけ

## 参照

- 0001（接続を 1 つのサーバーへの TCP に揃える）、0005（TLS の公開鍵の固定）、0008（自動の同期の起動条件）
- 設計書：docs/design-overview.md の D7、docs/basic-design-b4a.md の B4-1 の 3（USB／Bluetooth テザリング時の到達）、docs/open-questions.md の Q17
