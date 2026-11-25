pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        exclusiveContent {
            forRepository {
                maven {
                    name = "KspMavenCentral"
                    url = uri("https://repo.maven.apache.org/maven2")
                }
            }
            filter { includeGroup("com.google.devtools.ksp") }
        }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven {
                    name = "KspMavenCentral"
                    url = uri("https://repo.maven.apache.org/maven2")
                }
            }
            filter { includeGroup("com.google.devtools.ksp") }
        }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}
rootProject.name = "HeyRead"
include(":app")
