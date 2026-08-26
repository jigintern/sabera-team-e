package jp.jig.glasses.sample.kmp.glass

/**
 * 星図と案内矢印が、グラスの画像バッファ（[CANVAS_IMAGE_BUFFER_BYTES]）に同居できるか。
 *
 * **溢れるなら星図より矢印を捨てる。** 矢印が消えても文字の案内（「左へ 32°」）は残るが、
 * 星図が出なければ何も分からない。星が多い空ほど圧縮後が膨らむので、ここに来るのは
 * 案内中のいちばん濃い空だけ。
 *
 * 判定を画面から出してあるのは、**外したあとも送り続けていないか**を確かめるため。
 * 矢印は 130ms ごとに送り直すので、外した側と送る側で条件がずれると点滅になる。
 */
fun overlayFits(
    imageBytes: Int,
    overlayBytes: Int,
    limitBytes: Int = CANVAS_IMAGE_BUFFER_BYTES,
): Boolean = overlayBytes <= 0 || imageBytes + overlayBytes <= limitBytes
