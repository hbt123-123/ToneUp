package com.toneup.app.ui.feature.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.toneup.app.ui.components.formula.FormulaRenderEvent
import com.toneup.app.ui.components.formula.FormulaText

/** A0 PoC 样本：覆盖行内/独立公式、分式、根号、上下标、矩阵、分段函数、积分 */
private val POC_SAMPLES = listOf(
    "行内公式 \$a^2+b^2=c^2\$ 与文本混排。",
    "\$\$E=mc^2\$\$",
    "分式：\$\$\\frac{1}{1+\\frac{1}{x}}\$\$",
    "根号：\$\\sqrt{x^2+y^2} \\le \\sqrt[3]{z}\$",
    "上下标：\$x_1^2 + y_{n-1}^{(k)}\$",
    "矩阵：\$\$\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}\$\$",
    "分段函数：\$\$f(x)=\\begin{cases} x & x\\ge 0 \\\\ -x & x<0 \\end{cases}\$\$",
    "积分：\$\$\\int_0^\\infty e^{-x^2}\\,dx = \\frac{\\sqrt{\\pi}}{2}\$\$",
    "求和与极限：\$\$\\lim_{n\\to\\infty}\\sum_{k=1}^{n}\\frac{1}{k^2}=\\frac{\\pi^2}{6}\$\$",
    "**Markdown 加粗** 与 *斜体* 以及 `code` 混排 \$\\alpha+\$beta。",
    "### 1. markdown 残留前缀清洗后应正常渲染 \$\\sin^2\\theta+\$cos^2θ=1".replace("θ", "\\theta"),
    "超长公式横向滚动：\$\$\\underbrace{a+b+\\cdots+z}_{26}=\\text{alphabet}\$\$"
)

/**
 * 公式渲染 PoC（Debug 专用，§7.5）：
 * 统计成功率与首帧耗时；真机门槛指标（帧率/内存）需配合 Perfetto 手工测量。
 */
@Composable
fun FormulaPocScreen(onBack: () -> Unit) {
    var successCount by remember { mutableIntStateOf(0) }
    var failureCount by remember { mutableIntStateOf(0) }
    var totalFirstFrameMs by remember { mutableLongStateOf(0L) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text("公式渲染 PoC", style = MaterialTheme.typography.titleLarge)
            }
        }
        item {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("成功 $successCount / 失败 $failureCount")
                    val avg = if (successCount > 0) totalFirstFrameMs / successCount else 0
                    Text(
                        "平均首帧 ${avg}ms（池化热复用口径）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "门槛：成功率≥99%、首帧≤150ms；掉帧率与内存需真机 Perfetto 复测",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        items(POC_SAMPLES.size) { index ->
            val sample = POC_SAMPLES[index]
            var startAt by remember(index) { mutableLongStateOf(System.currentTimeMillis()) }
            Card {
                Column(Modifier.padding(10.dp)) {
                    FormulaText(
                        text = sample,
                        onRenderEvent = { event ->
                            when (event) {
                                is FormulaRenderEvent.Success -> {
                                    successCount++
                                    totalFirstFrameMs +=
                                        System.currentTimeMillis() - startAt
                                }
                                is FormulaRenderEvent.Failure -> failureCount++
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
