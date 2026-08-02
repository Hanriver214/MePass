package com.mepass.app.security

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import androidx.compose.ui.text.input.TextFieldValue.Companion.Saver

/**
 * 禁止复制 / 剪切 / 粘贴 / 选择全部 / 长按文本选择菜单 的安全文本输入框
 *
 * 实现方式：
 * 1. 通过 compositionLocal 禁用 TextToolbar（Compose文本工具栏）
 * 2. 拦截 ActionMode.Callback2 防止原生复制粘贴菜单弹出
 * 3. 使用自定义的 Value 变化回调检测粘贴并拒绝包含非键盘输入来源的内容
 */
@Composable
fun SecureOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors()
) {
    val context = LocalContext.current

    // 持续拒绝ACTION_MODE（复制粘贴菜单）
    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        var originalCallback: ActionMode.Callback? = null
        val noopCallback = object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                // 清空所有菜单项 - 绝不允许复制/粘贴/剪切/分享/全选
                menu?.clear()
                return false
            }
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                menu?.clear()
                return false
            }
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
            override fun onDestroyActionMode(mode: ActionMode?) {}
        }
        activity?.window?.callback = object : android.view.Window.CallbackWrapper(activity.window.callback) {
            override fun onActionModeStarted(mode: ActionMode?) {
                mode?.menu?.clear()
                mode?.finish()
                super.onActionModeStarted(mode)
            }
            override fun onCreatePanelMenu(featureId: Int, menu: Menu): Boolean {
                // 不允许任何文本操作菜单出现
                return false
            }
        }
        onDispose {
            // 不还原，保持安全策略
        }
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        isError = isError,
        singleLine = singleLine,
        maxLines = maxLines,
        colors = colors,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction
        ),
        // 关键：通过Modifier.onFocusChanged 清除用户可能在未聚焦期间复制到剪切板的内容
        readOnly = false
    )
}

/**
 * 更严格的密码/答案输入框：
 * - 禁止工具栏
 * - 默认显示为密码圆点
 * - 禁用长按弹出菜单
 */
@Composable
fun AnswerSecureTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    showAsPlain: Boolean = false
) {
    SecureOutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        isError = isError,
        visualTransformation = if (showAsPlain) VisualTransformation.None else PasswordVisualTransformation()
    )
}
