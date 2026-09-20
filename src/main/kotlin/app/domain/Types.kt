package app.domain

/**
 * 率定曲线分支：
 *  - COMMON：不区分涨落（在没有足够分支实测点时使用）
 *  - RISING：上涨（dLevel > 0）
 *  - FALLING：下落（dLevel < 0）
 */
enum class Branch(val code: String, val label: String) {
    COMMON("common", "通用"),
    RISING("rising", "上涨"),
    FALLING("falling", "下落");

    companion object {
        fun of(code: String?): Branch =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("未知分支: $code")
    }
}

/**
 * 派生流量的质量标志。彼此互斥，一个点恰好取一个；
 * 任何情况下都不得用 0 掩盖问题。
 */
enum class Quality(val code: String, val label: String) {
    OK("ok", "正常"),
    MISSING_LEVEL("missing_level", "原始水位缺失"),
    OUT_OF_RANGE("out_of_range", "超出标定水位范围"),
    BRANCH_UNKNOWN("branch_unknown", "分支无法判定"),
    NO_ACTIVE_CURVE("no_active_curve", "该时刻无已发布曲线"),
    OVERFLOW("overflow", "计算溢出/非有限结果");

    companion object {
        fun of(code: String): Quality = entries.first { it.code == code }
    }
}

/** 半开水位区间端点的开闭性质。 */
enum class Endpoint(val code: String) {
    OPEN("open"),
    CLOSED("closed");

    companion object {
        fun of(code: String): Endpoint = entries.first { it.code == code }
    }
}
