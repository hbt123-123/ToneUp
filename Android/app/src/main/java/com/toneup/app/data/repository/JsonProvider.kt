package com.toneup.app.data.repository

import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Json 单例提供者（与 NetworkModule 中同一份配置） */
@Singleton
class JsonProvider @Inject constructor(val json: Json)
