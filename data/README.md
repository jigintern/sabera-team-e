# 同梱データ

- グラスに星図と人工衛星を描くための星表・星座線・軌道要素
- **外部 API に依存せず、オフラインで完結する**（夜空の下は電波が悪い）
- **すべて生成物。手で編集しない**

| ファイル | 件数 | サイズ | 中身 | 作り直し |
|---|---|---|---|---|
| `stars.json` | 1,627 | 49 KB | 5 等までの恒星。`[hip, 赤経°, 赤緯°, 等級]` | `python3 tools/build-star-catalog.py` |
| `constellations.json` | 88 | 22 KB | 星座線と日本語名 | 同上 |
| `bright-stars.json` | 25 | 3 KB | 1.6 等までの固有名つき恒星 | 同上 |
| `constellation-figures.json` | 88 | 166 KB | **星座絵**（[NOIRLab](https://noirlab.edu/public/education/constellations/) の線画・CC BY 4.0）。**J2000 の赤経・赤緯**で持ち、星と同じ道筋で投影して薄く敷く | `python3 tools/build-constellation-figures.py` |
| `constellation-lore.json` | 88 | 24 KB | **星座の解説文**（神話と豆知識・2〜3 文）。**圏外でも喋るために持つ** | `python3 tools/build-constellation-lore.py` |
| `asterisms.json` | 4 ＋ 天の川 | 9 KB | **大三角などの結び**（HIP 番号）と**天の川の帯**（銀河座標 b=±10°） | `python3 tools/build-asterisms.py` |
| `meteor-showers.json` | 11 | 2 KB | **主な流星群**（極大日・活動期間・放射点・ZHR）。**日付だけで決まるので通信が要らない** | `python3 tools/build-meteor-showers.py` |
| `satellites.tle` | 24 | 4 KB | 名前で分かる衛星（ISS・みちびき・ひまわり・ハッブル・GPS…） | `python3 tools/build-satellites.py` |
| `starlink.tle` | 10,748 | **1.8 MB** | スターリンクの群れ | 同上 |
| `satellites-fetched.txt` | — | 21 B | TLE を取得した日時（UTC） | 同上 |

- 日本語名は手管理 — `tools/names-ja.json`（星座）/ `tools/satellites-ja.json`（衛星）
- 解説文も手管理で、本体は `tools/build-constellation-lore.py` の中にある

## 座標の約束（星表）

- **赤経・赤緯は度。赤経は 0〜360**
- **元期は J2000.0**（2026 年に使うなら歳差を約 0.36° 分入れる）
- 元データは赤経を −180〜180 の経度に折り返して持っているが、**生成時に 0〜360 に直してある**
- **`hip` は Hipparcos 番号。** `constellations.json` の星座線は星 ID ではなく座標を直接持っているので、
  対応づけは不要
- へび座（`Ser`）は元データで頭部と尾部に分かれているが、**同じ略号にまとめてある**

## 限界等級をなぜ 5 等にしたか

- 視野内の密度から決めた。FOV 40° は全天の約 3.0%

| 限界等級 | 全天 | 視野内 | 見え方 |
|---|---|---|---|
| 4 等 | 519 | 約 16 個 | 疎すぎる |
| **5 等** | **1,627** | **約 49 個** | **ちょうどよい** |
| 6 等 | 5,044 | 約 152 個 | 潰れる |

- 変えるなら `tools/build-star-catalog.py` の `LIMIT_MAGNITUDE`

## TLE は古くなる

- **1 日あたり 1〜5km** ずれる。角度に直すと **500km 先で 0.6°/日**
- **「1〜2 日以内なら実用、1 週間で怪しい」**が運用の目安
- **古さは取得日ではなく元期（TLE 自身の時刻）で測る。** 落とした時点で元期はすでに数時間〜1 日前
- **同梱したものは出発点。** オンラインのときに 1 日 1 回だけ静かに更新する（古くても動く、が原則）
- 詳しくは [人工衛星モード](../docs/team-e/40_satellites.md)

## 出処とライセンス

| データ | 出処 | ライセンス |
|---|---|---|
| 星表・星座線 | [d3-celestial](https://github.com/ofrohn/d3-celestial)（大元は XHIP, Anderson & Francis 2012） | BSD-3-Clause |
| TLE | [CelesTrak](https://celestrak.org/) | 取得の作法を守る（2 時間ごと更新・1 IP 100MB/日・`starlink` は更新期間ごとに 1 回） |
| 流星群 | IMO（国際流星機構）の年間カレンダー | 極大日・放射点・ZHR は**事実の記載**でスクリプトに直接書いてある |

- BSD-3-Clause は**著作権表示と条件文の保持**が条件。表示は [`NOTICE`](../NOTICE) にある
- 星表の他の候補を採らなかった理由 —

| 候補 | ライセンス | 理由 |
|---|---|---|
| HYG Database | CC BY-SA 4.0 | 継承（ShareAlike）が付く |
| Stellarium | GPL-2.0 | **アプリ全体に GPL が及ぶ懸念** |
