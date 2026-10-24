package com.afeicn.aiplayer.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * 海报共享元素转场：卡片上的小海报与详情页的大海报共用同一个 key，
 * 页面转场期间海报从小变大「飞」过去，观感上是同一张海报被放大。
 * 作用域由 PlayerApp 的 SharedTransitionLayout / AnimatedContent 注入；
 * 任一作用域缺失（理论上不会发生）时原样返回，退化为普通转场。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** 当前页面在 AnimatedContent 里的可见性作用域，与上面的容器作用域配对使用。 */
val LocalPageTransitionScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** 海报共享元素：key 用「poster-作品名」，卡片与详情页两端必须一致才能配对。 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.posterSharedElement(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val pageScope = LocalPageTransitionScope.current ?: return this
    val motionOn = rememberMotionEnabled()
    return shared.run {
        this@posterSharedElement.sharedElement(
            rememberSharedContentState(key = key),
            animatedVisibilityScope = pageScope,
            boundsTransform = { _, _ ->
                tween(motionDuration(motionOn, Motion.DETAIL_ENTER), easing = Motion.EmphasizedDecelerate)
            }
        )
    }
}

/** 海报共享元素 key：卡片端与详情页端同一算法，避免两边手写串不一致。 */
fun posterKey(title: String) = "poster-$title"
