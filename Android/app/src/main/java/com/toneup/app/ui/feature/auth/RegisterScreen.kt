package com.toneup.app.ui.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 注册页（AU）：FR-AU-02 注册成功引导直接登录 */
@Composable
fun RegisterScreen(
    onRegisterSuccess: () -> Unit,
    onBack: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val state by viewModel.registerState.collectAsStateWithLifecycle()
    var username by rememberSaveable { mutableStateOf("") }
    // H-45：密码/确认密码同 LoginScreen，不写入 savedInstanceState
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    // FR-AU-03 校验前置：非空、长度限制，就地提示
    val usernameError = when {
        username.isBlank() -> null
        !Regex("^[a-zA-Z0-9_\\-\\u4e00-\\u9fa5]{3,32}$").matches(username) ->
            "用户名 3~32 位，支持中文、字母、数字、下划线"
        else -> null
    }
    val passwordError = when {
        password.isBlank() -> null
        password.length < 8 -> "密码至少 8 位"
        else -> null
    }
    val confirmError = when {
        confirm.isBlank() -> null
        confirm != password -> "两次输入的密码不一致"
        else -> null
    }

    // H-47：注册成功导航走一次性事件流，Success 状态永不消费会导致组合重建后重复导航
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.registerSuccess.collect { onRegisterSuccess() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "创建账号",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        OutlinedTextField(
            value = username,
            // M-154：输入过程不做 trim（每键 trim 导致光标跳动），提交时再 trim；
            // 含空格的输入会由正则校验就地报错
            onValueChange = { username = it },
            label = { Text("用户名") },
            isError = usernameError != null,
            supportingText = {
                if (usernameError != null) {
                    Text(usernameError, color = MaterialTheme.colorScheme.error)
                }
            },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码（至少 8 位）") },
            isError = passwordError != null,
            supportingText = {
                if (passwordError != null) {
                    Text(passwordError, color = MaterialTheme.colorScheme.error)
                }
            },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Password, imeAction = ImeAction.Next
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text("确认密码") },
            isError = confirmError != null,
            supportingText = {
                if (confirmError != null) {
                    Text(confirmError, color = MaterialTheme.colorScheme.error)
                }
            },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Password, imeAction = ImeAction.Done
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))

        Button(
            // M-154：提交时统一 trim
            onClick = { viewModel.register(username.trim(), password) },
            enabled = username.isNotBlank() && password.isNotBlank() && confirm.isNotBlank() &&
                usernameError == null && passwordError == null && confirmError == null &&
                state !is AuthViewModel.UiState.Loading,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text(if (state is AuthViewModel.UiState.Loading) "注册中…" else "注册")
        }

        when (val s = state) {
            is AuthViewModel.UiState.Failure ->
                com.toneup.app.ui.components.ErrorRetryCard(
                    message = s.message,
                    retryLabel = "重新提交",
                    // M-154：重试与注册按钮同入口，提交时统一 trim
                    onRetry = { viewModel.register(username.trim(), password) }
                )
            else -> {}
        }

        TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) {
            Text("返回登录")
        }
    }
}
