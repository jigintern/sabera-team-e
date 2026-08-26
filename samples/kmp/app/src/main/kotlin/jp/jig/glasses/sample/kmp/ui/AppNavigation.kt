package jp.jig.glasses.sample.kmp.ui

/**
 * 戻るキーの遷移表。**null は「1 つ戻る」では済まない画面**。
 *
 * - ホームは終わってよい場所（握るとアプリを閉じられなくなる）
 * - 星図は確認を挟む（観測中に終わると方位合わせからやり直しになる）
 *
 * 画面を足したら必ずこの表も埋める。when が網羅を強制する。
 */
internal fun backDestination(screen: AppScreen): AppScreen? = when (screen) {
    AppScreen.HOME -> null
    AppScreen.GUIDES -> AppScreen.HOME
    AppScreen.GUIDE_EDITOR -> AppScreen.GUIDES
    AppScreen.GUIDE_SHARE -> AppScreen.GUIDES
    AppScreen.GUIDE_IMPORT -> AppScreen.GUIDES
    AppScreen.CONNECTION -> AppScreen.HOME
    AppScreen.CALIBRATION -> AppScreen.CONNECTION
    AppScreen.STAR_MAP -> null
}
