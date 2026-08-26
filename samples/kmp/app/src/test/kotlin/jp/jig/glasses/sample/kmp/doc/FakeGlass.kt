package jp.jig.glasses.sample.kmp.doc

import app.jigglass.glass.CommandManager
import app.jigglass.glass.GlassClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.Proxy

/**
 * デモ画像を撮るためだけの**つながったふりをするグラス**。
 *
 * SDK の口はすべて interface で、しかも R8 で名前が潰れた型（`k0` など）が引数に出てくる。
 * 1 つずつ override すると SDK が上がるたびに壊れるので、**動的プロキシで受け流す**。
 * 送ったものはどこへも行かない（BLE は無い）。返すのは「つながっている・何も来ない」だけ。
 *
 * **これで撮れるのは画面の見た目だけ。** 実機の描画も転送も再現しない。
 */
object FakeGlass {

    private val connected = MutableStateFlow(true)
    private val imuStarted = MutableStateFlow(true)
    private val micStreaming = MutableStateFlow(false)
    private val gestures = MutableSharedFlow<Any>()

    /** 6DoF の口。**テストから流し込んで、実際の描画をひととおり走らせる** */
    val imu = MutableSharedFlow<CommandManager.ImuData>(extraBufferCapacity = 64)
    private val mic = MutableSharedFlow<ByteArray>()
    private val channel = MutableStateFlow<Int?>(null)

    fun client(): GlassClient = proxy(GlassClient::class.java)

    fun commandManager(): CommandManager = proxy(CommandManager::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>): T = Proxy.newProxyInstance(
        type.classLoader,
        arrayOf(type),
    ) { _, method, _ -> answer(method.name, method.returnType) } as T

    private fun answer(name: String, returnType: Class<*>): Any? = when (name) {
        "getConnected" -> connected
        "isConnectionValid" -> true
        "getDeviceName" -> "SABERA (デモ)"
        "getDeviceIdentifier" -> "demo"
        "createCommandManager" -> commandManager()
        "getGestureEvents" -> gestures
        "getImuData" -> imu
        "getMicAudio" -> mic
        "getMicChannel" -> channel
        "getImuDataStarted" -> imuStarted
        "getMicStreaming" -> micStreaming
        "toString" -> "FakeGlass"
        "hashCode" -> System.identityHashCode(this)
        "equals" -> false
        else -> empty(returnType)
    }

    /** 戻り値の型に合わせた「何もない」値。suspend 関数は Unit を返せば止まらずに抜ける */
    private fun empty(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Byte::class.javaPrimitiveType -> 0.toByte()
        Void.TYPE -> null
        else -> Unit
    }
}
