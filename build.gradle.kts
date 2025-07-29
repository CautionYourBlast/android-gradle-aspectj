buildscript {
    repositories {
        mavenCentral()
    }

    val kotlinVersion: String by extra
    dependencies {
        classpath(kotlin("gradle-plugin", kotlinVersion))
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
