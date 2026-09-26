plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.toneup.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.toneup.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // M-1：模块没有任何 androidTestImplementation 依赖，也无 androidTest 源码目录，
        // testInstrumentationRunner 属无效死配置，移除（日后补充 UI 测试时随依赖一起恢复）
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:8000/\"")
            buildConfigField("boolean", "ENABLE_NETWORK_LOG", "true")
        }
        release {
            // M-2：release BASE_URL 不允许悄悄打包占位域名——优先读 gradle 属性
            // toneup.baseUrl（gradle.properties 或 -Ptoneup.baseUrl=...），其次环境变量
            // TONEUP_BASE_URL；均缺省时回退占位域名，并在构建期打印 MUST-CONFIGURE 告警
            val releaseBaseUrl = (project.findProperty("toneup.baseUrl") as String?)
                ?: System.getenv("TONEUP_BASE_URL")
                ?: "https://api.toneup.example.com/"
            if (releaseBaseUrl.contains("example.com")) {
                logger.warn(
                    "M-2 MUST-CONFIGURE: release BASE_URL 仍为占位域名 api.toneup.example.com，" +
                        "发布前请通过 -Ptoneup.baseUrl=<真实服务地址> 或环境变量 TONEUP_BASE_URL 注入"
                )
            }
            buildConfigField("String", "BASE_URL", "\"$releaseBaseUrl\"")
            buildConfigField("boolean", "ENABLE_NETWORK_LOG", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // H-1：release 必须产出可安装的签名包。若根目录存在 keystore.properties
            // （已加入 .gitignore，见 H-134）则用它签名；否则回退 debug 签名，
            // 保证 assembleRelease 产物可直接安装/分发（正式签名由发布环境提供）。
            val keystorePropsFile = rootProject.file("keystore.properties")
            if (keystorePropsFile.exists()) {
                val keystoreProps = java.util.Properties().apply {
                    keystorePropsFile.inputStream().use { load(it) }
                }
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(keystoreProps.getProperty("storeFile"))
                    storePassword = keystoreProps.getProperty("storePassword")
                    keyAlias = keystoreProps.getProperty("keyAlias")
                    keyPassword = keystoreProps.getProperty("keyPassword")
                }
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        // H-2：恢复 lint 可见性——release 构建也执行 lint，但发现问题时只告警不阻断
        // （历史遗留告警会在后续专项清理；关键项在 OCR 修复报告跟踪）
        abortOnError = false
        checkReleaseBuilds = true
    }
}

// M-3：kotlinOptions DSL 已随 Kotlin 2.0 弃用（当前 KGP 2.0.21），迁移到等价的
// compilerOptions DSL，消除弃用告警
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.datastore.core)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.compose)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.androidx.exifinterface)

    implementation(libs.vico.compose.m3)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
