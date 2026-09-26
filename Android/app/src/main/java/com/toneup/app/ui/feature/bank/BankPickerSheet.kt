package com.toneup.app.ui.feature.bank

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.toneup.app.data.remote.dto.BankSummaryDto
import com.toneup.app.data.remote.dto.CatalogDto
import com.toneup.app.ui.common.Load

/**
 * FR-BS-01 三级联动：学科 → 题型(题库分类) → 题库 → 年份。
 * 半屏约 60% 高、下拉可关；面包屑任意一级回退；选定年份后出现"开始刷题"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankPickerSheet(
    viewModel: BankViewModel,
    onSessionReady: (String) -> Unit,
    onOpenSectionList: (bankId: String) -> Unit = {}
) {
    val picker by viewModel.picker.collectAsStateWithLifecycle()
    val home by viewModel.home.collectAsStateWithLifecycle()
    if (!picker.visible) return
    // M-156：局部空安全绑定消除 !! 断言链（delegated 属性无法 smart cast）
    val p = picker
    val subjectId = p.subjectId
    val typeId = p.typeId
    val bankId = p.bankId
    val yearsError = p.yearsError

    ModalBottomSheet(
        onDismissRequest = { viewModel.closePicker() },
        modifier = Modifier.heightIn(max = 420.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            // M-155：catalog 未就绪（加载中/失败）时显示加载/错误占位，不再静默早退留白
            val catalog = (home.catalog as? Load.Ready)?.value
            if (catalog == null) {
                when (val cs = home.catalog) {
                    is Load.Failed -> Text(
                        cs.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                    else -> Row(Modifier.padding(vertical = 24.dp)) { CircularProgressIndicator() }
                }
            } else {
                Breadcrumb(
                    crumbs = buildList {
                        subjectId?.let {
                            add(BreadcrumbCrumb(subjectLabel(catalog, it)) { viewModel.selectType(null) })
                        }
                        typeId?.let {
                            add(BreadcrumbCrumb(typeLabel(catalog, subjectId, it)) { viewModel.selectBank(null) })
                        }
                    },
                    onSelectRoot = { viewModel.selectSubject(null) }
                )

                when {
                    subjectId == null ->
                        // M-157：weight(1f, fill=false) 约束列表高度，避免占满整屏挤压下方按钮
                        SubjectLevel(viewModel, catalog, Modifier.weight(1f, fill = false))

                    typeId == null ->
                        TypeLevel(viewModel, catalog, subjectId, Modifier.weight(1f, fill = false))

                    bankId == null ->
                        BankLevel(viewModel, catalog, subjectId, typeId, Modifier.weight(1f, fill = false))

                    p.yearsLoading -> Row(Modifier.padding(24.dp)) { CircularProgressIndicator() }

                    yearsError != null ->
                        Text(yearsError, color = MaterialTheme.colorScheme.error)

                    else -> YearLevel(viewModel, p, Modifier.weight(1f, fill = false))
                }
            }

            // EC-01：选定题库后可跳转专题/章节列表选题（修复原死路由）
            if (bankId != null) {
                TextButton(
                    onClick = { onOpenSectionList(bankId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text("按专题/章节选题")
                }
            }

            if (p.year != null && bankId != null) {
                Button(
                    onClick = { viewModel.startPractice(onSessionReady) },
                    enabled = !picker.creating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 24.dp)
                ) {
                    Text(if (picker.creating) "正在创建…" else "开始刷题")
                }
                val createError = picker.error
                if (!createError.isNullOrBlank()) {
                    Text(createError, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun subjectLabel(catalog: CatalogDto, subjectId: String): String =
    catalog.subjects.firstOrNull { it.id == subjectId }?.name ?: "学科"

private fun typeLabel(catalog: CatalogDto, subjectId: String?, typeId: String): String =
    catalog.subjects.firstOrNull { it.id == subjectId }?.types
        ?.firstOrNull { it.id == typeId }?.name ?: "类型"

data class BreadcrumbCrumb(val label: String, val onClick: () -> Unit)

@Composable
private fun Breadcrumb(crumbs: List<BreadcrumbCrumb>, onSelectRoot: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = "选题",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(role = Role.Button, onClick = onSelectRoot)
        )
        crumbs.forEach { crumb ->
            Text("›", color = MaterialTheme.colorScheme.outline)
            Text(
                text = crumb.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(role = Role.Button, onClick = crumb.onClick)
            )
        }
    }
}

@Composable
private fun SubjectLevel(
    viewModel: BankViewModel,
    catalog: CatalogDto,
    modifier: Modifier = Modifier
) {
    // M-157：由调用方传入 weight/heightIn 约束，避免无高度约束占满 Sheet
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(catalog.subjects, key = { it.id }) { subject ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { viewModel.selectSubject(subject.id) }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(subject.name, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun TypeLevel(
    viewModel: BankViewModel,
    catalog: CatalogDto,
    subjectId: String,
    modifier: Modifier = Modifier
) {
    val types = catalog.subjects.firstOrNull { it.id == subjectId }?.types ?: emptyList()
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(types, key = { it.id }) { type ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { viewModel.selectType(type.id) }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(type.name, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun BankLevel(
    viewModel: BankViewModel,
    catalog: CatalogDto,
    subjectId: String,
    typeId: String,
    modifier: Modifier = Modifier
) {
    val banks: List<BankSummaryDto> =
        catalog.banksOf(subjectId, typeId).filter { it.enabled }
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (banks.isEmpty()) {
            item { Text("该分类下暂无可用题库", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(banks, key = { it.id }) { bank ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { viewModel.selectBank(bank.id) }
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(bank.name, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun YearLevel(
    viewModel: BankViewModel,
    picker: PickerUiState,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text("选择年份", style = MaterialTheme.typography.titleSmall)
        LazyColumn(
            // M-157：内部列表同样以 weight 约束高度，不挤压外部布局
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // M-158：年份已在 ViewModel 侧 distinct() 去重，key 唯一性成立
            items(picker.years, key = { it }) { year ->
                FilterChip(
                    selected = picker.year == year,
                    onClick = { viewModel.selectYear(year) },
                    label = { Text("$year 年") }
                )
            }
        }
    }
}
