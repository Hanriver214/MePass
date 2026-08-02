package com.mepass.app.security

import android.app.Activity
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.Window
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/**
 * 一个完全禁用文本操作（复制/剪切/粘贴/分享/全选）的空 TextToolbar
 * Compose 文本字段如果没提供 toolbar，则不会弹文本选择/操作浮窗，
 * 这是 Compose 层最干净的拦截方式。
 */
private object NoCopyPasteTextToolbar : TextToolbar {
    override val status: TextToolbarStatus = TextToolbarStatus.Hidden
    override fun hide() {}
    override fun showMenu(
        rect: androidx.compose.ui.geometry.Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        // 全部拒绝，永远不弹
    }
}

/**
 * 禁止复制 / 剪切 / 粘贴 / 选择全部 / 长按文本选择菜单 的安全文本输入框
 *
 * 两层防御：
 *  1. Compose 层：用 CompositionLocal 提供空的 TextToolbar，永远不弹复制粘贴浮窗
 *  2. Framework 层：包装当前 Activity Window.Callback，拦截 onActionModeStarted 立即 finish
 *     （系统原生的 ActionMode 被触发时也会被秒关闭）
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
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    textStyle: TextStyle = TextStyle.Default
) {
    val context = LocalContext.current

    // Layer 2: 拦截 Window.Callback，禁止原生 ActionMode
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val original: Window.Callback? = activity?.window?.callback
        if (activity != null && original != null) {
            activity.window.callback = object : Window.Callback by original {
                override fun onActionModeStarted(mode: ActionMode?) {
                    // 清空菜单项并立刻结束 -> 复制/粘贴/剪切/分享/全选都没了
                    runCatching { mode?.menu?.clear() }
                    runCatching { mode?.finish() }
                }

                override fun onCreatePanelMenu(featureId: Int, menu: Menu): Boolean {
                    // 不允许任何文本操作面板出现（例如长按弹出的上下文菜单）
                    return false
                }

                override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean {
                    // 屏蔽 android.R.id.selectAll/copy/cut/paste/share 等
                    val id = item.itemId
                    if (id == android.R.id.copy || id == android.R.id.cut ||
                        id == android.R.id.paste || id == android.R.id.shareText ||
                        id == android.R.id.selectAll
                    ) return true
                    return original.onMenuItemSelected(featureId, item)
                }
            }
        }
        onDispose {
            if (activity != null && original != null) {
                // 退出屏幕时恢复原 callback，避免影响其他页面
                runCatching { activity.window.callback = original }
            }
        }
    }

    // Layer 1: 空 TextToolbar（Compose 层，优先生效）
    CompositionLocalProvider(LocalTextToolbar provides NoCopyPasteTextToolbar) {
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
            textStyle = textStyle,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = imeAction
            )
        )
    }
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
