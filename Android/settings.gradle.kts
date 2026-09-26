// M-269（延后）：当前所有构建输入优先解析自 maven.aliyun.com 第三方镜像（代理 Google/Maven Central），
// 且未启用 Gradle dependency verification，镜像返回的内容无 checksum/签名校验，存在供应链风险。
// 建议：后续引入 dependency verification（checksums + signatures）并配套 CI 校验流程；
// 直接调整仓库顺序可能破坏国内构建环境，需运维配合评估，本轮不改动仓库配置。

pluginManagement {
    repositories {
        // H-83：Aliyun google 镜像与 google() 用同一组 content 过滤器，
        // 避免 Google 系 artifact 意外命中未过滤的镜像源
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://maven.aliyun.com/repository/public")
        // M-268：与 pluginManagement 中 google() 对齐，使用同一组 content 过滤器，
        // 保证 Google 系 artifact 的解析行为在两处一致
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "ToneUp"
include(":app")
