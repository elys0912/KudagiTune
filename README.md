# playlist-sync-fox

Windows の iTunes（12.x）のプレイリストを、曲ごと Android 端末へ同期するツール。PC がサーバー、端末が取りに行く一方向の同期。接続は同一 LAN、Tailscale、USB テザリングの 3 方式。

状態：設計と実装まで完了。実機の確認は未（PC の C-13、端末の C-1〜C-17）。

## 文書

| 文書 | 内容 |
|---|---|
| [docs/design-overview.md](docs/design-overview.md) | 概要設計。動機、範囲、決定 D1〜D18、用語 |
| [docs/requirements.md](docs/requirements.md) | 要件 F1〜F6、N1〜N5 |
| [docs/open-questions.md](docs/open-questions.md) | 論点 Q1〜Q12 と結論 |
| [docs/basic-design.md](docs/basic-design.md) | 基本設計の索引（B1）。B2〜B4 は同じフォルダの `basic-design-*.md` |
| [docs/detailed-design.md](docs/detailed-design.md) | 詳細設計の索引（X1）。X2〜X7 は `detailed-design-x*.md` |
| [docs/overview.html](docs/overview.html) | 設計の視覚資料（単体の HTML） |
| [docs/manual/manual-pc.md](docs/manual/manual-pc.md) | 説明書 PC 版 |
| [docs/manual/manual-android.md](docs/manual/manual-android.md) | 説明書 Android 版 |
| [docs/manual/development.md](docs/manual/development.md) | 開発者向け（構成、ビルド、CI、Release、番号の読み方） |

レビューの記録と公式文書の確認の結果は、この repo には置かない。
