package com.yzcode.app

object CodeExtractor {
    private val KEYWORDS = listOf(
        "验证码", "校验码", "动态码", "动态密码", "动态口令",
        "code", "Code", "CODE",
        "verification", "Verification"
    )

    private val keywordBeforeDigits: Regex
    private val digitsBeforeKeyword: Regex
    private val standaloneDigits: Regex = Regex("""(?<![0-9A-Za-z])[0-9]{4,8}(?![0-9])""")

    init {
        val kw = KEYWORDS.joinToString("|") { Regex.escape(it) }
        keywordBeforeDigits = Regex("""(?:$kw)[^0-9]{0,20}?([0-9]{4,8})""")
        digitsBeforeKeyword = Regex("""([0-9]{4,8})[^0-9]{0,20}?(?:$kw)""")
    }

    fun extract(message: String): String? {
        keywordBeforeDigits.find(message)?.let { return it.groupValues[1] }
        digitsBeforeKeyword.find(message)?.let { return it.groupValues[1] }
        return standaloneDigits.find(message)?.value
    }
}
