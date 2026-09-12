package com.aptuidsh.kui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aptuidsh.kui.ui.theme.LocalDarkTheme

private val PrimaryBlue = Color(0xFF4D6BFE)
private val DangerRed = Color(0xFFE5484D)
private val SuccessGreen = Color(0xFF4CAF50)
private val TextSecondaryLight = Color(0xFF5B6478)
private val TextSecondaryDark = Color(0xFFA3B5AC)
private val DividerLight = Color(0xFFD8DEE4)
private val DividerDark = Color(0xFF2A322E)

/**
 * 提供方条目（来自 llm.providers RPC）
 */
data class ProviderEntry(
    val provider: String,
    val displayName: String,
    val settingsNs: String,
    val settingsPath: List<String>,
    val active: Boolean,
    val declared: Boolean,
    val defaultBaseURL: String = "", // 默认 API 地址
    val models: List<ConfiguredModel> = emptyList(), // 已配置的模型列表
)

/**
 * 凭据状态
 */
data class CredentialStatus(
    val configured: Boolean,
    val writable: Boolean,
    val value: String?,
)

/**
 * 已配置的模型条目
 */
data class ConfiguredModel(
    val id: String,
    val name: String,
)

/**
 * 可用模型条目（来自 llm.discoverModels RPC）
 */
data class CandidateModel(
    val id: String,
    val name: String?,
)

/**
 * 模型配置弹窗：两层结构
 */
@Composable
fun MaterialDialog(
    onDismiss: () -> Unit,
    onProviderEdit: (ProviderEntry, String, String) -> Unit = { _, _, _ -> },
    onProviderDelete: (ProviderEntry) -> Unit = {},
    onDiscoverModels: (ProviderEntry, String, String) -> Unit = { _, _, _ -> },
    onSaveModels: (ProviderEntry, List<CandidateModel>) -> Unit = { _, _ -> }, // 保存选中的模型
    allProviders: List<ProviderEntry> = emptyList(),
    credentials: Map<String, CredentialStatus> = emptyMap(),
    // 模型发现状态
    discoveredModels: List<CandidateModel> = emptyList(),
    isDiscovering: Boolean = false,
    discoverError: String? = null,
) {
    val dark = LocalDarkTheme.current
    val bg = if (dark) SidebarBgDark else SidebarBgLight
    val textSecondary = if (dark) TextSecondaryDark else TextSecondaryLight
    val divider = if (dark) DividerDark else DividerLight
    
    var showAddPage by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderEntry?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    
    // 首页：所有 active 的提供方（包括内置的 DeepSeek）
    val activeProviders = remember(allProviders) {
        allProviders.filter { it.active }
    }
    
    // 添加提供方页面：所有非 active 的提供方
    val inactiveProviders = remember(allProviders) {
        allProviders.filter { !it.active }
    }
    
    // 模型选择弹窗
    if (showModelPicker) {
        ModelPickerDialog(
            provider = editingProvider,
            models = discoveredModels,
            isLoading = isDiscovering,
            error = discoverError,
            onDismiss = { 
                showModelPicker = false
            },
            onConfirm = { selectedModels ->
                if (editingProvider != null) {
                    onSaveModels(editingProvider!!, selectedModels)
                }
                showModelPicker = false
            },
        )
    }
    
    // 编辑提供方对话框
    if (showEditDialog && editingProvider != null) {
        ProviderEditDialog(
            provider = editingProvider!!,
            credential = credentials[editingProvider!!.provider],
            isDiscovering = isDiscovering,
            onDismiss = { 
                showEditDialog = false
                editingProvider = null
            },
            onSave = { apiKey, baseURL ->
                onProviderEdit(editingProvider!!, apiKey, baseURL)
                showEditDialog = false
                editingProvider = null
            },
            onDiscoverModels = { apiKey, baseURL ->
                onDiscoverModels(editingProvider!!, apiKey, baseURL)
                showModelPicker = true
            },
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = bg),
        ) {
            Column(
                Modifier
                    .width(340.dp)
                    .height(460.dp),
            ) {
                // ===== 标题行 =====
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showAddPage) {
                        Text(
                            text = "← ",
                            fontSize = 16.sp,
                            color = PrimaryBlue,
                            modifier = Modifier.clickable { showAddPage = false }
                        )
                    }
                    Text(
                        text = if (showAddPage) "添加提供方" else "模型",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = textSecondary,
                    )
                    Spacer(Modifier.weight(1f))
                    if (!showAddPage) {
                        Text(
                            text = "填入各提供方的 API 密钥即可使用其模型。",
                            fontSize = 11.sp,
                            color = textSecondary,
                        )
                    }
                }
                HorizontalDivider(color = divider)

                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    if (!showAddPage) {
                        // ===== 首页：所有 active 的提供方（包括内置的 DeepSeek），用红/绿圆点表示状态 =====
                        if (activeProviders.isEmpty()) {
                            Text(
                                text = "暂无可用的提供方",
                                fontSize = 14.sp,
                                color = textSecondary.copy(alpha = 0.6f),
                                modifier = Modifier.padding(vertical = 20.dp),
                            )
                        } else {
                            activeProviders.forEach { provider ->
                                val cred = credentials[provider.provider]
                                ProviderStatusRow(
                                    provider = provider,
                                    credential = cred,
                                    onEdit = { 
                                        editingProvider = provider
                                        showEditDialog = true
                                    },
                                    onDelete = { onProviderDelete(provider) },
                                    textSecondary = textSecondary,
                                    divider = divider,
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        
                        HorizontalDivider(color = divider)
                        Text(
                            text = "添加提供方",
                            fontSize = 14.sp,
                            color = PrimaryBlue,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showAddPage = true }
                                .padding(vertical = 12.dp),
                        )
                    } else {
                        // ===== 添加提供方页面：所有非 active 的提供方 =====
                        if (inactiveProviders.isEmpty()) {
                            Text(
                                text = "所有提供方已添加",
                                fontSize = 14.sp,
                                color = textSecondary.copy(alpha = 0.6f),
                                modifier = Modifier.padding(vertical = 20.dp),
                            )
                        } else {
                            inactiveProviders.forEach { provider ->
                                AddableProviderRow(
                                    provider = provider,
                                    onAdd = {
                                        editingProvider = provider
                                        showEditDialog = true
                                    },
                                    textSecondary = textSecondary,
                                    divider = divider,
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderStatusRow(
    provider: ProviderEntry,
    credential: CredentialStatus?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    textSecondary: Color,
    divider: Color,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 提供方名称
            Text(
                text = provider.displayName, 
                fontSize = 14.sp, 
                color = textSecondary,
            )
            Spacer(Modifier.width(6.dp))
            // 状态圆点（对齐官方：绿点=正常，红点=异常）
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (credential?.configured == true) SuccessGreen else DangerRed)
            )
            Spacer(Modifier.weight(1f))
            // 编辑按钮
            Text(
                text = "编辑",
                fontSize = 12.sp,
                color = PrimaryBlue,
                modifier = Modifier.clickable { onEdit() }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            // 删除按钮（仅非内置提供方显示）
            if (provider.settingsNs != "llm-deepseek") {
                Text(
                    text = "删除",
                    fontSize = 12.sp,
                    color = DangerRed,
                    modifier = Modifier.clickable { onDelete() }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        HorizontalDivider(color = divider)
    }
}

@Composable
private fun AddableProviderRow(
    provider: ProviderEntry,
    onAdd: () -> Unit,
    textSecondary: Color,
    divider: Color,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = provider.displayName, fontSize = 14.sp, color = textSecondary)
                Text(text = "未配置", fontSize = 11.sp, color = DangerRed)
            }
            Text(
                text = "编辑",
                fontSize = 12.sp,
                color = PrimaryBlue,
                modifier = Modifier.clickable { onAdd() }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        HorizontalDivider(color = divider)
    }
}

/**
 * 提供方编辑对话框：配置 API 密钥、自定义 API 地址、获取可用模型。
 */
@Composable
private fun ProviderEditDialog(
    provider: ProviderEntry,
    credential: CredentialStatus?,
    isDiscovering: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit, // (apiKey, baseURL)
    onDiscoverModels: (String, String) -> Unit, // (apiKey, baseURL)
) {
    val dark = LocalDarkTheme.current
    val bg = if (dark) SidebarBgDark else SidebarBgLight
    val textSecondary = if (dark) TextSecondaryDark else TextSecondaryLight
    val divider = if (dark) DividerDark else DividerLight
    
    var apiKey by remember { mutableStateOf("") }
    var baseURL by remember { mutableStateOf("") }
    
    // 默认 API 地址
    val defaultBaseURL = provider.defaultBaseURL

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = bg),
        ) {
            Column(
                Modifier
                    .width(340.dp)
                    .padding(16.dp),
            ) {
                Text(
                    text = "配置 ${provider.displayName}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = textSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "填入 API 密钥即可启用此提供方。",
                    fontSize = 12.sp,
                    color = textSecondary.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(16.dp))
                
                // API 密钥
                Text(text = "API 密钥", fontSize = 12.sp, color = textSecondary)
                Spacer(Modifier.height(4.dp))
                BasicTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(divider.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                        .padding(8.dp),
                    textStyle = TextStyle(fontSize = 14.sp, color = textSecondary),
                    singleLine = true,
                    cursorBrush = SolidColor(PrimaryBlue),
                    decorationBox = { innerTextField ->
                        Box {
                            if (apiKey.isEmpty()) {
                                Text(
                                    text = if (credential?.configured == true) "留空保持现有密钥" else "输入 API 密钥",
                                    fontSize = 14.sp,
                                    color = textSecondary.copy(alpha = 0.5f),
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                
                // 自定义 API 地址
                Text(text = "自定义 API 地址（可选）", fontSize = 12.sp, color = textSecondary)
                Spacer(Modifier.height(4.dp))
                BasicTextField(
                    value = baseURL,
                    onValueChange = { baseURL = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(divider.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                        .padding(8.dp),
                    textStyle = TextStyle(fontSize = 14.sp, color = textSecondary),
                    singleLine = true,
                    cursorBrush = SolidColor(PrimaryBlue),
                    decorationBox = { innerTextField ->
                        Box {
                            if (baseURL.isEmpty()) {
                                Text(
                                    text = if (defaultBaseURL.isNotEmpty()) "默认: $defaultBaseURL" else "提供方默认",
                                    fontSize = 14.sp,
                                    color = textSecondary.copy(alpha = 0.5f),
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                
                // 获取可用模型按钮
                Text(
                    text = if (isDiscovering) "正在获取模型..." else "获取可用模型",
                    fontSize = 13.sp,
                    color = if (isDiscovering) textSecondary.copy(alpha = 0.5f) else PrimaryBlue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isDiscovering) {
                            onDiscoverModels(apiKey, baseURL)
                        }
                        .padding(vertical = 8.dp),
                )
                
                // 已配置模型列表
                if (provider.models.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "已配置模型",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = textSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    provider.models.forEach { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(divider.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = model.name,
                                    fontSize = 13.sp,
                                    color = textSecondary,
                                )
                                Text(
                                    text = model.id,
                                    fontSize = 11.sp,
                                    color = textSecondary.copy(alpha = 0.6f),
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
                
                Spacer(Modifier.height(12.dp))
                
                // 按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = textSecondary)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onSave(apiKey, baseURL) }) {
                        Text("保存", color = PrimaryBlue)
                    }
                }
            }
        }
    }
}

/**
 * 模型选择弹窗：显示可用模型列表，支持勾选。
 */
@Composable
private fun ModelPickerDialog(
    provider: ProviderEntry?,
    models: List<CandidateModel>,
    isLoading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (List<CandidateModel>) -> Unit,
) {
    val dark = LocalDarkTheme.current
    val bg = if (dark) SidebarBgDark else SidebarBgLight
    val textSecondary = if (dark) TextSecondaryDark else TextSecondaryLight
    val divider = if (dark) DividerDark else DividerLight
    
    val selectedModels = remember { mutableStateListOf<String>() }
    var selectAll by remember { mutableStateOf(false) }
    
    // 全选/取消全选
    LaunchedEffect(selectAll, models) {
        if (selectAll) {
            selectedModels.clear()
            selectedModels.addAll(models.map { it.id })
        } else if (selectedModels.size == models.size && models.isNotEmpty()) {
            selectedModels.clear()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = bg),
        ) {
            Column(
                Modifier
                    .width(340.dp)
                    .padding(16.dp),
            ) {
                Text(
                    text = "选择要添加的模型",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = textSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "以下是模型提供方的可用模型，勾选要添加的模型。",
                    fontSize = 12.sp,
                    color = textSecondary.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(12.dp))
                
                when {
                    isLoading -> {
                        // 加载中
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = PrimaryBlue)
                        }
                    }
                    error != null -> {
                        // 错误
                        Text(
                            text = error,
                            fontSize = 13.sp,
                            color = DangerRed,
                            modifier = Modifier.padding(vertical = 20.dp),
                        )
                    }
                    models.isEmpty() -> {
                        // 空
                        Text(
                            text = "未找到可用模型",
                            fontSize = 13.sp,
                            color = textSecondary.copy(alpha = 0.6f),
                            modifier = Modifier.padding(vertical = 20.dp),
                        )
                    }
                    else -> {
                        // 全选
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { 
                                    selectAll = !selectAll
                                    if (selectAll) {
                                        selectedModels.clear()
                                        selectedModels.addAll(models.map { it.id })
                                    } else {
                                        selectedModels.clear()
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = selectAll || selectedModels.size == models.size,
                                onCheckedChange = { checked ->
                                    selectAll = checked
                                    if (checked) {
                                        selectedModels.clear()
                                        selectedModels.addAll(models.map { it.id })
                                    } else {
                                        selectedModels.clear()
                                    }
                                },
                                colors = CheckboxDefaults.colors(checkedColor = PrimaryBlue),
                            )
                            Text(text = "全选", fontSize = 14.sp, color = textSecondary)
                        }
                        
                        // 模型列表
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp)
                        ) {
                            items(models) { model ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (selectedModels.contains(model.id)) {
                                                selectedModels.remove(model.id)
                                            } else {
                                                selectedModels.add(model.id)
                                            }
                                            selectAll = selectedModels.size == models.size
                                        }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = selectedModels.contains(model.id),
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                selectedModels.add(model.id)
                                            } else {
                                                selectedModels.remove(model.id)
                                            }
                                            selectAll = selectedModels.size == models.size
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = PrimaryBlue),
                                    )
                                    Column {
                                        Text(
                                            text = model.name ?: model.id,
                                            fontSize = 14.sp,
                                            color = textSecondary,
                                        )
                                        if (model.name != null && model.name != model.id) {
                                            Text(
                                                text = model.id,
                                                fontSize = 11.sp,
                                                color = textSecondary.copy(alpha = 0.6f),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                
                // 按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = textSecondary)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { 
                            val selected = models.filter { selectedModels.contains(it.id) }
                            onConfirm(selected)
                        },
                        enabled = selectedModels.isNotEmpty(),
                    ) {
                        Text("添加所选 (${selectedModels.size})", color = PrimaryBlue)
                    }
                }
            }
        }
    }
}
