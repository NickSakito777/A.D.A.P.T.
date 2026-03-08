package com.example.adaptapp.voice

// 语音命令匹配器 — 意图分类 + 位置名模糊匹配
// Voice command matcher — intent classification + fuzzy position name matching
class CommandMatcher {

    // 匹配结果
    // Match result
    sealed class MatchResult {
        data object EmergencyStop : MatchResult()
        data object Cancel : MatchResult()
        data object Landscape : MatchResult()
        data object Portrait : MatchResult()
        data object FoldArm : MatchResult()
        data class PositionMatch(val name: String) : MatchResult()
        data class Ambiguous(val candidates: List<String>) : MatchResult()
        data object NoMatch : MatchResult()
    }

    // 从语音文本中匹配命令
    // Match command from speech text
    fun match(text: String, positionNames: List<String>): MatchResult {
        val lower = text.lowercase().trim()

        // 1. 急停 — 最高优先级
        if (lower.contains("stop") || lower.contains("emergency")) {
            return MatchResult.EmergencyStop
        }

        // 2. 取消
        if (lower.contains("cancel") || lower.contains("never mind")) {
            return MatchResult.Cancel
        }

        // 3. 横屏 / 竖屏
        if (lower.contains("landscape")) return MatchResult.Landscape
        if (lower.contains("portrait")) return MatchResult.Portrait

        // 4. 折叠
        if (lower.contains("fold")) return MatchResult.FoldArm

        // 5. 位置召回 — 提取位置名关键词，模糊匹配
        val query = extractPositionQuery(lower)
        if (query.isNotEmpty() && positionNames.isNotEmpty()) {
            return matchPosition(query, positionNames)
        }

        // 6. 没有明确前缀时，尝试用整句话直接匹配位置名
        if (positionNames.isNotEmpty()) {
            return matchPosition(lower, positionNames)
        }

        return MatchResult.NoMatch
    }

    // 判断确认态下用户的回答
    // Classify confirmation response
    fun isConfirmation(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower in listOf("yes", "yeah", "yep", "confirm", "do it", "sure", "ok", "okay")
    }

    fun isDenial(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower in listOf("no", "nope", "cancel", "never mind", "stop")
    }

    // 从 "go to reading" / "move to drinking" 中提取位置名部分
    // Extract position name from phrases like "go to reading"
    private fun extractPositionQuery(text: String): String {
        val prefixes = listOf(
            "go to ", "move to ", "recall ", "load ",
            "go ", "move ", "take me to ", "bring me "
        )
        for (prefix in prefixes) {
            if (text.startsWith(prefix)) {
                return text.removePrefix(prefix).trim()
            }
            val idx = text.indexOf(prefix)
            if (idx >= 0) {
                return text.substring(idx + prefix.length).trim()
            }
        }
        // "reading please" → "reading"
        val suffixes = listOf(" please", " position")
        var result = text
        for (suffix in suffixes) {
            if (result.endsWith(suffix)) {
                result = result.removeSuffix(suffix).trim()
            }
        }
        return if (result != text) result else ""
    }

    // 模糊匹配位置名
    // Fuzzy match against saved position names
    private fun matchPosition(query: String, names: List<String>): MatchResult {
        data class Scored(val name: String, val score: Double)

        // 生成 query 的标准化变体（数字词→数字，去空格等）
        val queryVariants = normalizeVariants(query.lowercase())

        val scored = names.map { name ->
            val nameVariants = normalizeVariants(name.lowercase())
            // 取所有变体组合中的最高分
            val score = queryVariants.maxOf { q ->
                nameVariants.maxOf { n ->
                    maxOf(containsScore(n, q), levenshteinSimilarity(n, q))
                }
            }
            Scored(name, score)
        }.sortedByDescending { it.score }

        if (scored.isEmpty()) return MatchResult.NoMatch

        val best = scored[0]
        val second = scored.getOrNull(1)

        return when {
            // 完美匹配 — 直接返回，不管第二名
            best.score >= 0.95 ->
                MatchResult.PositionMatch(best.name)
            // 高置信度且与第二名拉开差距
            best.score >= 0.8 && (second == null || second.score < best.score - 0.15) ->
                MatchResult.PositionMatch(best.name)
            // 歧义：两个候选分数都高且差距小
            best.score >= 0.6 && second != null && second.score >= 0.6 ->
                MatchResult.Ambiguous(scored.filter { it.score >= 0.6 }.take(3).map { it.name })
            // 单一中等匹配
            best.score >= 0.6 && (second == null || second.score < 0.4) ->
                MatchResult.PositionMatch(best.name)
            else -> MatchResult.NoMatch
        }
    }

    // 生成标准化变体：数字词↔数字，有空格/无空格
    // "test one" → ["test one", "test 1", "test1", "testone"]
    private fun normalizeVariants(text: String): List<String> {
        val numberWords = mapOf(
            "zero" to "0", "one" to "1", "two" to "2", "three" to "3",
            "four" to "4", "five" to "5", "six" to "6", "seven" to "7",
            "eight" to "8", "nine" to "9", "ten" to "10"
        )
        val digitWords = numberWords.entries.associate { (k, v) -> v to k }

        var withDigits = text
        for ((word, digit) in numberWords) {
            withDigits = withDigits.replace(word, digit)
        }
        var withWords = text
        for ((digit, word) in digitWords) {
            withWords = withWords.replace(digit, word)
        }

        val variants = mutableSetOf(text, withDigits, withWords)
        // 无空格变体
        variants.add(text.replace(" ", ""))
        variants.add(withDigits.replace(" ", ""))
        return variants.toList()
    }

    // 包含匹配分数：query 是 name 的子串，或 name 是 query 的子串
    // Containment score: substring matching
    private fun containsScore(name: String, query: String): Double {
        if (name == query) return 1.0
        if (name.contains(query) && query.length >= 3) {
            return 0.7 + 0.3 * query.length / name.length
        }
        if (query.contains(name) && name.length >= 3) {
            return 0.7 + 0.3 * name.length / query.length
        }
        // 单词级别匹配："drinking position" 的某个词包含 query
        val words = name.split(" ")
        for (word in words) {
            if (word == query) return 0.95
            if (word.startsWith(query) && query.length >= 3) {
                return 0.7 + 0.2 * query.length / word.length
            }
        }
        return 0.0
    }

    // Levenshtein 编辑距离相似度
    // Levenshtein edit distance similarity
    private fun levenshteinSimilarity(a: String, b: String): Double {
        val dist = levenshteinDistance(a, b)
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - dist.toDouble() / maxLen
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[m][n]
    }
}
