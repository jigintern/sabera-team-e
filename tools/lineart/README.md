# 星座絵の元データ（NOIRLab 88 Constellations）

**手で編集しない。** 取得したままの SVG を置いてある。
`python3 tools/build-constellation-figures.py` がここを読んで `data/constellation-figures.json` を作る。

| | |
|---|---|
| 出処 | [NSF NOIRLab「The 88 Constellations」](https://noirlab.edu/public/education/constellations/) |
| ライセンス | **CC BY 4.0**（[NOIRLab の著作権ページ](https://noirlab.edu/public/copyright/)） |
| クレジット | **NOIRLab/NSF/AURA** |
| 取得日 | 2026-08-25 |
| 取得元の URL | `https://noirlab.edu/public/media/archives/lineart/svg/<名前>-outline.svg` |

- ファイル名は星座のラテン名を小書き・空白なしにしたもの（`canismajor.svg`）。
  取得元は 4 つだけハイフン入り（`ursa-major` / `ursa-minor` / `piscis-austrinus` / `triangulum-australe`）
- **リポジトリに置いてあるのは CI のため。** `data/` の作り直しは外部取得なしで完結させて、
  生成物に差分が出ないかを CI が見ている（`.github/workflows/checks.yml`）
- 1 枚が 360×360 の viewBox で、**輪郭の線・星（`<circle>`、半径が等級）・星座線（`<line>`）**が
  同じ座標系に入っている。**星の位置が実際の空と一致する**ので、
  絵をどこへ置くかは手で決めずに星から解ける
