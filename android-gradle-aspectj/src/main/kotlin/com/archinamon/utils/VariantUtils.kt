package com.archinamon.utils

import com.android.build.api.component.impl.ComponentImpl
import com.android.build.api.variant.ComponentIdentity
import com.android.build.gradle.internal.component.ComponentCreationConfig
import com.android.build.gradle.internal.core.VariantSources
import com.android.build.gradle.internal.plugins.BasePlugin
import com.android.build.gradle.internal.scope.TaskContainer
import com.android.build.gradle.internal.variant.BaseVariantData
import com.android.build.gradle.internal.variant.ComponentInfo
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.compile.JavaCompile
import java.io.File
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

const val LANG_AJ = "aspectj"
const val LANG_JAVA = "java"

fun getJavaTask(baseVariantData: BaseVariantData): JavaCompile {
    val field = BaseVariantData::class.memberProperties.find { p -> p.name == "taskContainer" }!!
    field.isAccessible = true
    val taskContainer = field.call(baseVariantData) as TaskContainer
    return taskContainer.javacTask.get()
}

fun getAjSourceAndExcludeFromJavac(project: Project, variantData: BaseVariantData): FileCollection {
    val javaTask = getJavaTask(variantData)
    val componentIdentity = getComponentIdentity(variantData)

    val flavors: List<String> = componentIdentity.productFlavors.map { flavor -> flavor.second }
    val srcSet = mutableListOf("main", componentIdentity.buildType!!)
    srcSet.addAll(flavors)

    val srcDirs = srcSet.map { "src/$it/aspectj" }
    val aspects: FileCollection = project.files(srcDirs.map(project::file))

    javaTask.exclude { treeElem ->
        treeElem.file in aspects.files
    }

    return aspects.filter(File::exists)
}

fun findAjSourcesForVariant(project: Project, variantName: String): MutableSet<File> {
    return findSourcesForVariant(project, variantName, LANG_AJ)
}

fun findJavaSourcesForVariant(project: Project, variantName: String): MutableSet<File> {
    return findSourcesForVariant(project, variantName, LANG_JAVA)
}

fun findSourcesForVariant(
    project: Project,
    variantName: String,
    language: String
): MutableSet<File> {
    val possibleDirs: MutableSet<File> = mutableSetOf()
    if (project.file("src/main/$language").exists()) {
        possibleDirs.add(project.file("src/main/$language"))
    }

    val types = variantName.split("(?=\\p{Upper})".toRegex())
    val root = project.file("src").listFiles()!!

    root.forEach { file ->
        types.forEach { type ->
            if (file.name.contains(type.toLowerCase()) &&
                file.list()?.any { it.contains(language) } == true
            ) {
                possibleDirs.add(File(file, language))
            }
        }
    }

    return LinkedHashSet(possibleDirs)
}

fun getVariantDataList(plugin: BasePlugin<*, *, *, *, *, *, *, *, *, *, *>): List<BaseVariantData> {
    return (plugin.variantManager.mainComponents.map {
        val field = ComponentInfo::class.memberProperties.find { p -> p.name == "variant" }!!
        field.isAccessible = true
        field.call(it) as ComponentImpl<*>
    } + plugin.variantManager.testComponents.map {
        it as ComponentImpl<*>
    }).map {
        val field = ComponentImpl::class.memberProperties.find { p -> p.name == "variantData" }!!
        field.isAccessible = true
        field.call(it) as BaseVariantData
    }
}

fun getComponentIdentity(variantData: BaseVariantData): ComponentIdentity {
    val field = BaseVariantData::class.memberProperties.find { p -> p.name == "componentIdentity" }!!
    field.isAccessible = true
    return field.call(variantData) as ComponentIdentity
}

fun getVariantSources(variantData: BaseVariantData): VariantSources {
    val field = BaseVariantData::class.memberProperties.find { p -> p.name == "variantSources" }!!
    field.isAccessible = true
    return field.call(variantData) as VariantSources
}

internal infix fun <E> MutableCollection<in E>.shl(elem: E): MutableCollection<in E> {
    this.add(elem)
    return this
}

internal infix fun <E> MutableCollection<in E>.from(elems: Collection<E>) {
    this.addAll(elems)
}