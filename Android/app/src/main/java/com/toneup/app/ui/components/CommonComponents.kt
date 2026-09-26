package com.toneup.app.ui.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** 错误重试卡：就地提示，不弹阻断对话框 */
@Composable
fun ErrorRetryCard(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
    // TODO(M-117)：默认标签为硬编码中文，与当前全应用中文文案现状一致；
    // 资源化需项目级 i18n 决策（补 strings.xml 条目并统一其余硬编码文案），延后处理
    retryLabel: String = "重试"
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry) { Text(retryLabel) }
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

/** 骨架屏（§8.1：loading 展示骨架而非转圈） */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 700)),
        label = "alpha"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(16.dp)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            // M-118：骨架块为纯装饰占位，清空语义避免 TalkBack 播报无意义内容
            .clearAndSetSemantics { }
    )
}

@Composable
fun QuestionSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SkeletonBlock(Modifier.height(20.dp))
        SkeletonBlock()
        SkeletonBlock()
        // M-119：删除仅含单个 Spacer 的空 Row——渲染无任何可见内容，组内间距已由 Column spacedBy 统一控制
        repeat(4) {
            SkeletonBlock(Modifier.height(48.dp))
        }
    }
}
