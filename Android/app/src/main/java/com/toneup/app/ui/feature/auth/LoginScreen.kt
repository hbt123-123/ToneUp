package com.toneup.app.ui.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.toneup.app.ui.components.ErrorRetryCard

/**
 * 登录页（AU）：
 * FR-AU-01 用户名+密码登录；FR-AU-03 输入校验前置就地提示；
 * FR-AU-04 loading 防重复点击；FR-AU-06 密码显隐与自动填充。
 */
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onGoRegister: () -> Unit,
    prefillUsername: String? = null,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val state by viewModel.loginState.collectAsStateWithLifecycle()
    val registeredUsername by viewModel.registeredUsername.collectAsStateWithLifecycle()
    var username by rememberSaveable { mutableStateOf(prefillUsername ?: "") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    // 就地输入校验（非空、长度）
    val usernameError = when {
        username.isBlank() -> null
        username.length < 3 -> "用户名至少 3 个字符"
        else -> null
    }
    val passwordError = when {
        password.isBlank() -> null
        password.length < 8 -> "密码至少 8 位"
        else -> null
    }
    val canSubmit = username.isNotBlank() && password.isNotBlank() &&
        usernameError == null && passwordError == null && state !is AuthViewModel.UiState.Loading

    LaunchedEffect(state) {
        if (state is AuthViewModel.UiState.Success) onLoginSuccess()
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
            text = "一潼上岸",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "ToneUp 考研刷题",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        OutlinedTextField(
            value = username,
            onValueChange = { username = it.trim() },
            label = { Text("用户名") },
            isError = usernameError != null,
            supportingText = {
                if (usernameError != null) {
                    Text(usernameError, color = MaterialTheme.colorScheme.error)
                } else if (registeredUsername != null &&
                    username.isEmpty()
                ) {
                    Text("注册成功，请登录", color = MaterialTheme.colorScheme.primary)
                }
            },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            isError = passwordError != null,
            supportingText = {
                if (passwordError != null) {
                    Text(passwordError, color = MaterialTheme.colorScheme.error)
                }
            },
            visualTransformation =
                if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showPassword) "隐藏密码" else "显示密码"
                    )
                }
            },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { viewModel.login(username, password) },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            if (state is AuthViewModel.UiState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("登录")
            }
        }

        when (val s = state) {
            is AuthViewModel.UiState.Failure ->
                ErrorRetryCard(message = s.message, onRetry = { viewModel.login(username, password) })
            else -> {}
        }

        TextButton(onClick = onGoRegister, modifier = Modifier.padding(top = 8.dp)) {
            Text("没有账号？去注册")
        }
    }
}
