# 生成スクリプト

`data/` の同梱データを作る。**出力はすべて生成物なので手で編集しない。**

| スクリプト | 出力 | 取得元 |
|---|---|---|
| `build-star-catalog.py` | `data/stars.json` / `constellations.json` / `bright-stars.json` | [d3-celestial](https://github.com/ofrohn/d3-celestial)（BSD-3-Clause） |
| `build-satellites.py` | `data/satellites.tle` / `starlink.tle` / `satellites-fetched.txt` | [CelesTrak](https://celestrak.org/) |
| `build-constellation-lore.py` | `data/constellation-lore.json` | **スクリプトの中の手書き**（88 星座の神話と豆知識） |
| `build-constellation-figures.py` | `data/constellation-figures.json` | **`tools/lineart/`**（[NOIRLab の 88 星座線画](https://noirlab.edu/public/education/constellations/)・CC BY 4.0） |
| `build-asterisms.py` | `data/asterisms.json` | **スクリプトの中の手書き**（大三角の HIP と天の川の帯） |

```bash
python3 tools/build-star-catalog.py           # 取得して data/ を作り直す
python3 tools/build-satellites.py             # 足りないものだけ取る（--force で取り直す）
python3 tools/build-constellation-lore.py     # 解説文（長さと読み上げできない記号を検査する）
python3 tools/build-constellation-figures.py  # 星座絵
python3 tools/build-asterisms.py              # 大三角と天の川
```

**下の 3 つは外部取得が要らない**ので、CI が作り直して `data/` に差分が出ないか見ている。

- 取得結果は `.cache/` に置いて使い回す（`.gitignore` 済み）

## デモ画像

`docs/images/` を作り直す。**`data/` とは別で、実機も外部取得も要らない。**

```bash
tools/build-demo-images.sh      # スマホ・グラスの全画面（docs/images/）
```

| スクリプト | 出力 | 中身を作るところ |
|---|---|---|
| `build-demo-images.sh` | `docs/images/phone-*.png` | `PhoneScreenshotTest`（**アプリの Compose をそのまま描く**） |
| `compose-glass-images.java` | `docs/images/glass-*.png` | `DocumentImagesTest` → `build/doc-images/panels.txt` |

一覧と読み方は [docs/images/README.md](../docs/images/README.md)。

## 手で管理するファイル

| ファイル | 中身 |
|---|---|
| `names-ja.json` | 88 星座の日本語名。**すべて埋まっていないと生成が止まる** |
| `lineart/*.svg` | 星座絵の元データ。**取得したままで手を入れない**（[lineart/README.md](lineart/README.md)） |
| `satellites-ja.json` | グラスに出す衛星の名前と NORAD 番号。**足すときはここに 1 行** |

**衛星の `ja` は短くする。** グラスの文字は 1 文字 28px 見当で、長い名前は枠に入らず重なって消える
（「みちびき1号機後継機」→「みちびき1R」）。

## CelesTrak の作法

非営利で運営されている。守らないと弾かれる。

| 制限 | 内容 |
|---|---|
| 更新間隔 | **2 時間ごと。** それより短い間隔で取り直しても意味がない |
| 転送量 | **1 IP あたり 1 日 100MB** |
| `starlink` | **更新期間ごとに 1 回まで。** 繰り返すと HTTP 403 |

