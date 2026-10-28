package com.b50.roast.data

/**
 * 开发者模式（解锁后不受每日在线刷新次数限制）的凭据校验。
 *
 * 为什么不写明文：这串东西一旦以字符串常量的形式留在 dex 里，
 * 任何人 `strings classes.dex | grep 1487` 就能挖出来，等于把开关公开了。
 * 所以沿用 OAuthClient 里 client_id 的那套做法 —— 只存 XOR 之后的形式，运行时还原再比较，
 * 静态扫描搜不到原文。数组还刻意倒序存放，dump 出来也不能直接读。
 *
 * ⚠️ 这只是「藏起来」，不是密码学意义上的鉴权。
 * 有心人反编译 R8 后的代码照样能把 key 和数组还原出来。
 * 它挡住的是随手搜字符串、看 prefs 的人，别当成真正的权限系统。
 */
internal object DevKey {

    /** 逐字符 XOR 后**倒序**存放 */
    private val ENC = intArrayOf(
        0x5B, 0x5B, 0x5F, 0x5C, 0x55, 0x59, 0x5A, 0x55, 0x59, 0x5C,
    )
    private const val K = 0x6D

    /**
     * 输入是否匹配。
     *
     * 用「全部位异或累加」而不是提前 return 的写法：
     * 一方面不会因为长度/位置不同而提前退出，另一方面 R8 也更难把整套比较折叠成常量。
     */
    fun matches(input: String): Boolean {
        val t = input.trim()
        if (t.length != ENC.size) return false
        var diff = 0
        for (i in ENC.indices) {
            val v = t[ENC.size - 1 - i].code xor K
            diff = diff or (v xor ENC[i])
        }
        return diff == 0
    }
}
