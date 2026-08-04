pluginManagement {
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://mirrors.tuna.tsinghua.edu.cn/maven2/")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        maven("https://mirrors.tuna.tsinghua.edu.cn/maven2/")
        google()
        mavenCentral()
        maven("https://jitpack.io") // for mwiede/jsch
    }
}
rootProject.name = "SSHInjector"
include(":app")
