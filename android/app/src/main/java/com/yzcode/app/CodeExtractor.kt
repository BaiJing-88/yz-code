package com.yzcode.app

object CodeExtractor {
    private val KEYWORDS = listOf(
        "验证码", "校验码", "动态码", "动态密码", "动态口令", "动态验证",
        "安全码", "确认码", "授权码", "交易码", "短信码", "提取码",
        "code", "Code", "CODE", "verification", "Verification", "OTP", "otp"
    )

    private val keywordBeforeCode: Regex
    private val codeBeforeKeyword: Regex
    private val standaloneDigits: Regex = Regex("""(?<![0-9A-Za-z])[0-9]{4,8}(?![0-9])""")

    init {
        val kw = KEYWORDS.joinToString("|") { Regex.escape(it) }
        // 关键词后 20 个非字母数字字符内的数字串（允许数字间带空格，如 "123 456"）
        keywordBeforeCode = Regex("""(?:$kw)[^0-9A-Za-z]{0,20}?([0-9][0-9 ]{2,14}[0-9])""")
        codeBeforeKeyword = Regex("""([0-9][0-9 ]{2,14}[0-9])[^0-9A-Za-z]{0,20}?(?:$kw)""")
    }

    private fun normalize(raw: String): String? {
        val digits = raw.replace(" ", "")
        return if (digits.length in 4..8) digits else null
    }

    fun containsKeyword(message: String): Boolean = KEYWORDS.any { message.contains(it) }

    fun extract(message: String): String? {
        keywordBeforeCode.find(message)?.let { m -> normalize(m.groupValues[1])?.let { return it } }
        codeBeforeKeyword.find(message)?.let { m -> normalize(m.groupValues[1])?.let { return it } }
        return standaloneDigits.find(message)?.value
    }
}
