package com.pixelden.launcher.system

import android.content.Context
import android.util.Base64
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * Минимальный клиент протокола adb к самому телевизору (127.0.0.1:5555).
 * Нужен, чтобы отключать и включать приложения без компьютера: у обычного приложения таких прав нет,
 * а у adb shell — есть. При первом подключении ТВ спросит «Разрешить отладку?» — один раз.
 */
class AdbClient(private val context: Context) {

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    /** Выполнить команду в оболочке ТВ и вернуть её вывод. */
    fun shell(command: String, timeoutMs: Int = 30_000): String {
        // Первый раз ТВ спрашивает «Разрешить отладку?». После ответа система не продолжает старое
        // соединение, поэтому переподключаемся каждые 3 секунды, пока не пустят (до минуты).
        val deadline = System.currentTimeMillis() + 60_000
        while (true) {
            try {
                return attempt(command, timeoutMs)
            } catch (e: NotAuthorizedYet) {
                if (System.currentTimeMillis() > deadline) error("на ТВ не разрешили отладку")
            }
        }
    }

    private class NotAuthorizedYet : Exception()
    private var pubkeySent = false

    private fun attempt(command: String, timeoutMs: Int): String {
        Socket().use { sock ->
            sock.connect(InetSocketAddress("127.0.0.1", 5555), 3000)
            sock.soTimeout = 10_000
            val input = DataInputStream(sock.getInputStream().buffered())
            val out = sock.getOutputStream()
            handshake(input, out)
            sock.soTimeout = timeoutMs
            send(out, OPEN, 1, 0, "shell:$command\u0000".toByteArray())
            val result = StringBuilder()
            var remote = 0
            while (true) {
                val m = read(input)
                when (m.cmd) {
                    OKAY -> remote = m.arg0
                    WRTE -> { result.append(String(m.data, Charsets.UTF_8)); send(out, OKAY, 1, m.arg0, ByteArray(0)) }
                    CLSE -> { send(out, CLSE, 1, remote, ByteArray(0)); return result.toString() }
                }
            }
        }
    }

    private fun handshake(input: DataInputStream, out: OutputStream) {
        send(out, CNXN, VERSION, MAX_DATA, "host::pixeltv\u0000".toByteArray())
        val keys = keyPair()
        var signed = false
        while (true) {
            val m = read(input)
            when {
                m.cmd == CNXN -> return
                m.cmd == AUTH && m.arg0 == AUTH_TOKEN && !signed -> {
                    signed = true
                    send(out, AUTH, AUTH_SIGNATURE, 0, sign(keys, m.data))
                }
                // Подпись не узнали — ключ новый: отправляем открытый ключ, ТВ покажет вопрос.
                m.cmd == AUTH && m.arg0 == AUTH_TOKEN -> {
                    // Ключ отправляем один раз — иначе ТВ будет спрашивать разрешение по кругу.
                    if (!pubkeySent) send(out, AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyBytes(keys.public as RSAPublicKey))
                    pubkeySent = true
                    // Ждём ответа на вопрос на экране и пробуем заново.
                    Thread.sleep(3000)
                    throw NotAuthorizedYet()
                }
                else -> error("неожиданный ответ adb: ${Integer.toHexString(m.cmd)}")
            }
        }
    }

    private fun send(out: OutputStream, cmd: Int, a0: Int, a1: Int, data: ByteArray) {
        val b = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(data.sumOf { it.toInt() and 0xFF }).putInt(cmd.inv())
        b.put(data)
        out.write(b.array())
        out.flush()
    }

    private fun read(input: DataInputStream): Msg {
        val head = ByteArray(24).also { input.readFully(it) }
        val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int
        if (len !in 0..MAX_DATA * 4) error("adb: странная длина $len")
        val data = ByteArray(len).also { input.readFully(it) }
        return Msg(cmd, a0, a1, data)
    }

    /** Ключ лаунчера для adb: создаётся один раз и хранится во внутренней памяти. */
    private fun keyPair(): KeyPair {
        val priv = File(context.filesDir, "adbkey")
        val pub = File(context.filesDir, "adbkey.pub")
        val kf = KeyFactory.getInstance("RSA")
        if (priv.exists() && pub.exists()) {
            return KeyPair(kf.generatePublic(X509EncodedKeySpec(pub.readBytes())), kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes())))
        }
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        priv.writeBytes(kp.private.encoded)
        pub.writeBytes(kp.public.encoded)
        return kp
    }

    /** adb подписывает 20-байтовый токен как готовый SHA-1: PKCS#1 v1.5 с префиксом DigestInfo. */
    private fun sign(keys: KeyPair, token: ByteArray): ByteArray {
        val c = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        c.init(Cipher.ENCRYPT_MODE, keys.private)
        return c.doFinal(SHA1_PREFIX + token)
    }

    /** Открытый ключ в формате Android (mincrypt): n0inv, n и R² в словах little-endian, base64 + имя. */
    private fun publicKeyBytes(key: RSAPublicKey): ByteArray {
        val words = 64
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n = key.modulus
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toInt()
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        val b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(words).putInt(n0inv)
        putWords(b, n, words)
        putWords(b, rr, words)
        b.putInt(key.publicExponent.toInt())
        val text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " pixeltv@tv\u0000"
        return text.toByteArray()
    }

    private fun putWords(b: ByteBuffer, v: BigInteger, words: Int) {
        var x = v
        val mask = BigInteger.valueOf(0xFFFFFFFFL)
        repeat(words) { b.putInt(x.and(mask).toInt()); x = x.shiftRight(32) }
    }

    companion object {
        private const val CNXN = 0x4e584e43
        private const val AUTH = 0x48545541
        private const val OPEN = 0x4e45504f
        private const val OKAY = 0x59414b4f
        private const val CLSE = 0x45534c43
        private const val WRTE = 0x45545257
        private const val VERSION = 0x01000000
        private const val MAX_DATA = 256 * 1024
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3
        private val SHA1_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )
    }
}
