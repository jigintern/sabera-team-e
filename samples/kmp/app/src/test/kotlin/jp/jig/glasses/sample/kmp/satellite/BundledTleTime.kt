package jp.jig.glasses.sample.kmp.satellite

import java.io.File
import java.time.Instant

/**
 * 同梱 TLE を取ってきた時刻。衛星のテストはこの時刻で伝播する。
 *
 * **壁時計で伝播しない。** TLE は元期から離れるほど位置がずれるので、壁時計だと
 * 同梱データが古くなるにつれてテストが勝手に落ちる（ひまわり 8 号の真下の点が
 * 取得から 1 か月で東経 140.7° → 139.3° へ流れた）。取り直せばこの時刻も一緒に進む。
 */
internal fun bundledTleFetchedMillis(dataDir: File): Long =
    Instant.parse(File(dataDir, "satellites-fetched.txt").readText().trim()).toEpochMilli()
