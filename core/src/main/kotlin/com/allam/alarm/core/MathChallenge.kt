package com.allam.alarm.core

import kotlin.random.Random

/**
 * 긴급 해제용 계산 문제. 센서 고장·몸이 아플 때 등 움직일 수 없는 상황을 위한 탈출구이며,
 * 비몽사몽 상태로는 풀기 어렵게 두 자리 곱셈 + 덧셈으로 만든다.
 */
data class MathProblem(val text: String, val answer: Int)

object MathChallenge {
    const val PROBLEM_COUNT = 3

    fun generate(random: Random = Random.Default, count: Int = PROBLEM_COUNT): List<MathProblem> =
        List(count) {
            val a = random.nextInt(12, 50)
            val b = random.nextInt(3, 10)
            val c = random.nextInt(11, 90)
            MathProblem("$a × $b + $c", a * b + c)
        }
}
