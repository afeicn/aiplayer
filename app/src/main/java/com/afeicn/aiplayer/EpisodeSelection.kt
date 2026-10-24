package com.afeicn.aiplayer

/**
 * 选集网格的选中集合纯计算：全选 / 前 N 集 / 闭区间 / 清空 / 单格点选切换。
 * 只依赖整数集合，不碰 Android 框架，方便纯 JVM 单测。
 *
 * 集号约定从 1 起、连续到 [total]。网格里点选单集与快捷按钮
 * （全选/前10集/下10集/后面全部）最终都收敛成同一个 Set<Int>，
 * 下载时按集号排序后交给 [SubscriptionPipeline.downloadRange]。
 */
object EpisodeSelection {

    /** 全选：1..total（total <= 0 时为空集）。 */
    fun all(total: Int): Set<Int> = if (total <= 0) emptySet() else (1..total).toSet()

    /** 前 N 集：1..min(N, total)；N <= 0 或 total <= 0 时为空集。 */
    fun firstN(total: Int, n: Int): Set<Int> =
        if (total <= 0 || n <= 0) emptySet() else (1..n.coerceAtMost(total)).toSet()

    /** 闭区间 [from, to]（自动裁到 1..total）；from > to 或 total <= 0 时为空集。 */
    fun range(total: Int, from: Int, to: Int): Set<Int> =
        if (total <= 0) emptySet() else (from.coerceAtLeast(1)..to.coerceAtMost(total)).toSet()

    /** 反选：全选减去当前已选。 */
    fun inverse(total: Int, selected: Set<Int>): Set<Int> = all(total) - selected

    /** 清空选中。 */
    fun clear(): Set<Int> = emptySet()

    /** 单格点选：点一下选中、再点一下取消。 */
    fun toggle(selected: Set<Int>, index: Int): Set<Int> =
        if (index in selected) selected - index else selected + index
}
