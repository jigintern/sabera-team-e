# 生成スクリプト

`data/` の同梱データと天球シミュレータを作る。**出力はすべて生成物なので手で編集しない。**

| スクリプト | 出力 | 取得元 |
|---|---|---|
| `build-star-catalog.py` | `data/stars.json` / `constellations.json` / `bright-stars.json` | [d3-celestial](https://github.com/ofrohn/d3-celestial)（BSD-3-Clause） |
| `build-satellites.py` | `data/satellites.tle` / `starlink.tle` / `satellites-fetched.txt` | [CelesTrak](https://celestrak.org/) |
| `build-simulator.py` | `simulator/index.html` | `data/` ＋ `simulator/template.html` |

```bash
python3 tools/build-star-catalog.py           # 取得して data/ を作り直す
python3 tools/build-satellites.py             # 足りないものだけ取る（--force で取り直す）
python3 tools/build-simulator.py              # シミュレータを作り直す
```

- `--check` を付けると生成物が最新かだけ確かめる（**CI が回すのは `build-simulator.py --check` だけ**）
- 取得結果は `.cache/` に置いて使い回す（`.gitignore` 済み）

## 手で管理するファイル

| ファイル | 中身 |
|---|---|
| `names-ja.json` | 88 星座の日本語名。**すべて埋まっていないと生成が止まる** |
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

## 天球シミュレータ

**実機で確かめる前に、ブラウザで変換パイプラインを検証する。**
`simulator/index.html` をダブルクリックするだけ（自己完結。ローカルサーバー不要）。
何が忠実で何がそうでないかは [simulator/README.md](simulator/README.md)。
