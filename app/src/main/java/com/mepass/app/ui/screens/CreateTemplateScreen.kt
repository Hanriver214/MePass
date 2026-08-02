package com.mepass.app.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mepass.app.model.PresetQuestions
import com.mepass.app.model.Question
import com.mepass.app.template.TemplateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTemplateScreen(
    navController: NavController,
    onTemplateCreated: (com.mepass.app.model.Template) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var templateName by remember { mutableStateOf("我的密码恢复模板") }
    var threshold by remember { mutableStateOf(3) }
    var enableAes by remember { mutableStateOf(false) }
    var aesPassword by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }

    // 选中的问题与答案对 (questionId -> Pair(Question, answer))
    val selectedQAs = remember {
        mutableStateMapOf<String, Pair<Question, String>>()
    }

    // 展示预设问题选择器
    var showPresetPicker by remember { mutableStateOf(false) }
    // 自定义问题对话框
    var showCustomDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("创建新模板", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                generateTemplate(
                                    context = context,
                                    templateName = templateName,
                                    selectedQAs = selectedQAs.values.toList(),
                                    threshold = threshold,
                                    enableAes = enableAes,
                                    aesPassword = aesPassword,
                                    onLoading = { isGenerating = it },
                                    onSuccess = { template ->
                                        onTemplateCreated(template)
                                        Toast.makeText(
                                            context,
                                            "模板创建成功！可到「恢复 Passphrase」测试，或使用导出功能保存 JSON",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                )
                            }
                        },
                        enabled = !isGenerating && selectedQAs.size >= threshold
                    ) {
                        if (isGenerating) {
                            CircularProgressIndicator(Modifier.size(20.dp))
                        } else {
                            Icon(Icons.Default.Save, contentDescription = "生成")
                            Spacer(Modifier.width(4.dp))
                            Text("生成模板")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 模板名称
            OutlinedTextField(
                value = templateName,
                onValueChange = { templateName = it },
                label = { Text("模板名称") },
                modifier = Modifier.fillMaxWidth()
            )

            // 门限配置
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "恢复门限设置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "至少正确回答多少个问题才能恢复密码（k/N 门限）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    if (selectedQAs.size > 0) {
                        Text("当前问题数 N = ${selectedQAs.size}，门限 k 范围：1 ~ ${selectedQAs.size}")
                        Spacer(Modifier.height(8.dp))
                        Slider(
                            value = threshold.toFloat(),
                            onValueChange = { threshold = it.coerceIn(1f, selectedQAs.size.toFloat()).toInt() },
                            valueRange = 1f..selectedQAs.size.toFloat(),
                            steps = (selectedQAs.size - 2).coerceAtLeast(0)
                        )
                        Text(
                            text = "当前门限：$threshold / ${selectedQAs.size}（遗忘 ${selectedQAs.size - threshold} 个仍可恢复）",
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Text("请先添加问题", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // 可选 AES 加密
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "启用模板 AES 加密标记",
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.Bold
                        )
                        Switch(checked = enableAes, onCheckedChange = { enableAes = it })
                    }
                    Text(
                        text = "标记模板要求额外的加密保护（导出的 JSON 可附加 AES-GCM 加密参数）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (enableAes) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = aesPassword,
                            onValueChange = { aesPassword = it },
                            label = { Text("AES 加密密码（可选）") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 问题列表管理
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "问题列表 (${selectedQAs.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showPresetPicker = true }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("从预设添加")
                        }
                        OutlinedButton(onClick = { showCustomDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("自定义问题")
                        }
                    }
                }
            }

            // 已选问题列表
            selectedQAs.values.forEachIndexed { index, (q, answer) ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${index + 1}. ",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = q.text,
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Medium
                            )
                            IconButton(onClick = { selectedQAs.remove(q.id) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        if (q.hint != null) {
                            Text(
                                text = "提示：${q.hint}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        OutlinedTextField(
                            value = answer,
                            onValueChange = { newAns ->
                                selectedQAs[q.id] = q to newAns
                            },
                            label = { Text("您的答案") },
                            modifier = Modifier.fillMaxWidth(),
                            isError = answer.isBlank()
                        )
                    }
                }
            }
        }
    }

    // 预设问题选择对话框
    if (showPresetPicker) {
        PresetPickerDialog(
            onDismiss = { showPresetPicker = false },
            onConfirm = { selectedIds ->
                selectedIds.forEach { presetId ->
                    val preset = PresetQuestions.getById(presetId)
                    if (preset != null && !selectedQAs.contains(presetId)) {
                        selectedQAs[presetId] = preset to ""
                    }
                }
                // 自动调整门限到合理值
                if (threshold > selectedQAs.size && selectedQAs.size > 0) {
                    threshold = (selectedQAs.size * 0.6).toInt().coerceAtLeast(1)
                }
                showPresetPicker = false
            }
        )
    }

    // 自定义问题对话框
    if (showCustomDialog) {
        CustomQuestionDialog(
            onDismiss = { showCustomDialog = false },
            onConfirm = { questionText, hint ->
                val newQ = Question(
                    id = Question.generateId(),
                    text = questionText,
                    isCustom = true,
                    hint = hint.ifBlank { null }
                )
                selectedQAs[newQ.id] = newQ to ""
                if (threshold > selectedQAs.size && selectedQAs.size > 0) {
                    threshold = (selectedQAs.size * 0.6).toInt().coerceAtLeast(1)
                }
                showCustomDialog = false
            }
        )
    }
}

@Composable
private fun PresetPickerDialog(
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val selectedIds = remember { mutableStateSetOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择预设隐私问题", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                PresetQuestions.presetQuestions.forEach { q ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = selectedIds.contains(q.id),
                            onCheckedChange = { checked ->
                                if (checked) selectedIds.add(q.id)
                                else selectedIds.remove(q.id)
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(q.text, style = MaterialTheme.typography.bodyMedium)
                            if (q.hint != null) {
                                Text(
                                    text = "提示：${q.hint}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedIds) }) {
                Text("添加 (${selectedIds.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun CustomQuestionDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var questionText by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加自定义问题", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = questionText,
                    onValueChange = { questionText = it },
                    label = { Text("问题内容") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = hint,
                    onValueChange = { hint = it },
                    label = { Text("输入提示（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (questionText.isNotBlank()) onConfirm(questionText.trim(), hint.trim())
                },
                enabled = questionText.isNotBlank()
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private suspend fun generateTemplate(
    context: Context,
    templateName: String,
    selectedQAs: List<Pair<Question, String>>,
    threshold: Int,
    enableAes: Boolean,
    aesPassword: String,
    onLoading: (Boolean) -> Unit,
    onSuccess: (com.mepass.app.model.Template) -> Unit
) {
    if (templateName.isBlank()) {
        Toast.makeText(context, "请填写模板名称", Toast.LENGTH_SHORT).show()
        return
    }
    val blanks = selectedQAs.filter { it.second.isBlank() }
    if (blanks.isNotEmpty()) {
        Toast.makeText(context, "问题「${blanks.first().first.text}」尚未填写答案", Toast.LENGTH_SHORT).show()
        return
    }
    if (selectedQAs.size < threshold) {
        Toast.makeText(context, "问题数量不足", Toast.LENGTH_SHORT).show()
        return
    }

    onLoading(true)
    val template = withContext(Dispatchers.Default) {
        try {
            TemplateManager.createTemplate(
                templateName = templateName,
                qaPairs = selectedQAs,
                threshold = threshold,
                enableAes = enableAes,
                aesPassword = aesPassword
            )
        } catch (e: Exception) {
            null
        }
    }
    onLoading(false)

    if (template != null) {
        val json = TemplateManager.exportTemplate(template)
        // 保存到 Downloads 目录
        try {
            val filename = "MePass_${template.name.replace(Regex("""[^\w\-]"""), "_")}_${System.currentTimeMillis()}.json"
            val dir = context.getExternalFilesDir(null)
            if (dir != null) {
                val file = java.io.File(dir, filename)
                file.writeText(json, Charsets.UTF_8)
                Toast.makeText(
                    context,
                    "模板已保存到：${file.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "保存文件失败：${e.message}", Toast.LENGTH_LONG).show()
        }
        onSuccess(template)
    } else {
        Toast.makeText(context, "生成模板失败", Toast.LENGTH_SHORT).show()
    }
}
