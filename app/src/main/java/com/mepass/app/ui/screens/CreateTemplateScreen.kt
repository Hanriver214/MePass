package com.mepass.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mepass.app.template.TemplateManager
import com.mepass.app.model.PresetQuestions
import com.mepass.app.model.Question
import com.mepass.app.model.Template
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTemplateScreen(
    onTemplateCreated: (Template) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var templateName by remember { mutableStateOf("我的密码恢复模板") }
    var questions by remember { mutableStateOf(listOf<Question>()) }
    var answers by remember { mutableStateOf(mapOf<String, String>()) }
    var threshold by remember { mutableStateOf(1f) }
    var isGenerating by remember { mutableStateOf(false) }
    var showPresetPicker by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("创建新模板") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (questions.isEmpty()) {
                                Toast.makeText(context, "请至少添加 1 个问题", Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            if (answers.any { it.value.isBlank() }) {
                                Toast.makeText(context, "请填写所有答案", Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            scope.launch {
                                isGenerating = true
                                try {
                                    val template = withContext(Dispatchers.Default) {
                                        TemplateManager.createTemplate(
                                            name = templateName,
                                            questionAnswers = questions.map { it to (answers[it.id] ?: "") },
                                            threshold = threshold.toInt().coerceAtLeast(1)
                                        )
                                    }
                                    onTemplateCreated(template)
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "生成失败: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                } finally {
                                    isGenerating = false
                                }
                            }
                        },
                        enabled = !isGenerating
                    ) {
                        Text("生成模板")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 模板名
            OutlinedTextField(
                value = templateName,
                onValueChange = { templateName = it },
                label = { Text("模板名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // 门限配置
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "门限配置: ${threshold.toInt()} / ${questions.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "需要 ${threshold.toInt()} 个正确答案才能恢复密码",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (questions.size >= 2) {
                        Slider(
                            value = threshold,
                            onValueChange = { threshold = it },
                            valueRange = 1f..questions.size.toFloat(),
                            steps = questions.size - 2
                        )
                    }
                }
            }

            // 问题列表
            questions.forEachIndexed { index, question ->
                QuestionCard(
                    question = question,
                    answer = answers[question.id] ?: "",
                    onAnswerChange = { newAnswer ->
                        answers = answers + (question.id to newAnswer)
                    },
                    onDelete = {
                        questions = questions.filterNot { it.id == question.id }
                        answers = answers - question.id
                        if (threshold > questions.size) {
                            threshold = questions.size.toFloat().coerceAtLeast(1f)
                        }
                    }
                )
            }

            // 添加问题按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showPresetPicker = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("从预设添加")
                }
                OutlinedButton(
                    onClick = { showCustomDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("自定义问题")
                }
            }

            if (isGenerating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "正在生成模板... (Argon2id 计算)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }

    if (showPresetPicker) {
        PresetPickerDialog(
            selectedIds = questions.filter { !it.isCustom }.map { it.id }.toSet(),
            onConfirm = { selectedIds ->
                val currentIds = questions.map { it.id }.toSet()
                val toAdd = selectedIds.filter { it !in currentIds }
                val newQuestions = toAdd.mapNotNull { PresetQuestions.getById(it) }
                questions = questions + newQuestions
                if (questions.size >= 1) {
                    threshold = (questions.size * 0.6f).toFloat().coerceIn(1f, questions.size.toFloat())
                }
                showPresetPicker = false
            },
            onDismiss = { showPresetPicker = false }
        )
    }

    if (showCustomDialog) {
        CustomQuestionDialog(
            onConfirm = { text ->
                val newQuestion = Question(
                    id = "custom_${System.currentTimeMillis()}",
                    text = text,
                    isCustom = true
                )
                questions = questions + newQuestion
                if (questions.size >= 1) {
                    threshold = (questions.size * 0.6f).toFloat().coerceIn(1f, questions.size.toFloat())
                }
                showCustomDialog = false
            },
            onDismiss = { showCustomDialog = false }
        )
    }
}

@Composable
private fun QuestionCard(
    question: Question,
    answer: String,
    onAnswerChange: (String) -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    question.text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除")
                }
            }
            question.hint?.let {
                Text(
                    "提示: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = answer,
                onValueChange = onAnswerChange,
                label = { Text("答案") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetPickerDialog(
    selectedIds: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val currentSelection = remember { mutableStateListOf(*selectedIds.toTypedArray()) }
    var searchQuery by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择预设问题") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("搜索") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.height(300.dp)
                ) {
                    val filtered = PresetQuestions.all.filter {
                        it.text.contains(searchQuery, ignoreCase = true)
                    }
                    items(filtered) { question ->
                        val isSelected = question.id in currentSelection
                        ListItem(
                            headlineContent = { Text(question.text) },
                            supportingContent = question.hint?.let { hint ->
                                { Text("提示: $hint", style = MaterialTheme.typography.bodySmall) }
                            },
                            trailingContent = {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            currentSelection.add(question.id)
                                        } else {
                                            currentSelection.remove(question.id)
                                        }
                                    }
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(currentSelection.toSet()) }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun CustomQuestionDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var questionText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义问题") },
        text = {
            OutlinedTextField(
                value = questionText,
                onValueChange = { questionText = it },
                label = { Text("问题内容") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (questionText.isNotBlank()) {
                        onConfirm(questionText.trim())
                    }
                }
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
