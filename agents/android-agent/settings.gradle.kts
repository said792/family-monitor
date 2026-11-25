pluginManagement {
    repositories {
        // مرايا أولًا (أسرع وتتجنب قيود معدل الطلبات من mavenCentral)، ثم الرسمية كاحتياط
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://repo.huaweicloud.com/repository/maven")
        google()
        mavenCentral()
    }
}
rootProject.name = "FamilyMonitor"
include(":app")
