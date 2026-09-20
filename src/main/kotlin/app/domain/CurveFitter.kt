package app.domain

/**
 * 最小二乘多项式拟合：Q = Σ c_k h^k，k=0..degree。
 * 解法是正规方程 + 部分主元高斯消元。
 *
 * 不修改任何原始实测点，只返回新的系数。
 */
object CurveFitter {
    data class Fit(val coeffs: List<Double>, val usedPointIds: List<Long>)

    data class FitPoint(val id: Long, val h: Double, val q: Double)

    fun fit(points: List<FitPoint>, degree: Int): Fit {
        require(degree in 0..4) { "拟合阶数必须在 0..4 之间" }
        require(points.isNotEmpty()) { "没有可用于拟合的实测点" }
        require(points.size > degree) {
            "实测点数量(${points.size})必须大于拟合阶数($degree)，以防过定与数值退化"
        }
        val n = degree + 1
        // A[k][j] = Σ h^(k+j)，b[k] = Σ q h^k
        val a = Array(n) { DoubleArray(n + 1) }
        for (p in points) {
            val hpow = DoubleArray(2 * degree + 1)
            hpow[0] = 1.0
            for (k in 1 until hpow.size) hpow[k] = hpow[k - 1] * p.h
            for (k in 0..degree) {
                for (j in 0..degree) a[k][j] += hpow[k + j]
                a[k][n] += p.q * hpow[k]
            }
        }
        val coeffs = gaussianSolve(a).toList()
        if (coeffs.any { !it.isFinite() }) throw IllegalStateException("拟合矩阵奇异或结果非有限")
        return Fit(coeffs, points.map { it.id })
    }

    private fun gaussianSolve(matrix: Array<DoubleArray>): DoubleArray {
        val n = matrix.size
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) {
                if (kotlin.math.abs(matrix[row][col]) > kotlin.math.abs(matrix[pivot][col])) pivot = row
            }
            if (kotlin.math.abs(matrix[pivot][col]) < 1e-12) {
                throw IllegalArgumentException("拟合矩阵奇异：实测点不足以支撑该阶数")
            }
            val tmp = matrix[col]; matrix[col] = matrix[pivot]; matrix[pivot] = tmp
            for (row in 0 until n) {
                if (row == col) continue
                val factor = matrix[row][col] / matrix[col][col]
                for (j in col..n) matrix[row][j] -= factor * matrix[col][j]
            }
        }
        return DoubleArray(n) { i -> matrix[i][n] / matrix[i][i] }
    }
}
