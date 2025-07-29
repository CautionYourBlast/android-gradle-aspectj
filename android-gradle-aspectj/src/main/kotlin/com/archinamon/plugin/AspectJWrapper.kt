package com.archinamon.plugin

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.AppExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.TestedExtension
import com.archinamon.AndroidConfig
import com.archinamon.AspectJExtension
import com.archinamon.api.transform.*
import com.archinamon.utils.LANG_AJ
import org.gradle.api.Plugin
import org.gradle.api.Project
import javax.inject.Inject

sealed class AspectJWrapper(private val scope: ConfigScope): Plugin<Project> {

    class DryRun @Inject constructor(): AspectJWrapper(ConfigScope.STANDARD)

    class Standard @Inject constructor(): AspectJWrapper(ConfigScope.STANDARD)

    class Provides @Inject constructor(): AspectJWrapper(ConfigScope.PROVIDE)

    class Extended @Inject constructor(): AspectJWrapper(ConfigScope.EXTEND)

    class Test @Inject constructor(): AspectJWrapper(ConfigScope.JUNIT)

    private val noWeavingScopes = arrayOf(
            ConfigScope.PROVIDE,
            ConfigScope.JUNIT
    )

    override fun apply(project: Project) {
        val config = AndroidConfig(project, scope)
        val settings = project.extensions.create(LANG_AJ, AspectJExtension::class.java).apply {
            dryRun = this@AspectJWrapper is DryRun
        }

        configProject(project, config, settings)

        if (this is DryRun) {
            return
        }

        if (scope in noWeavingScopes) {
            return
        }

        // Configure weaving using the new AGP 8.x androidComponents API
        val taskProvider = AspectJTaskProvider(project, config)
        
        project.pluginManager.withPlugin("com.android.application") {
            val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            taskProvider.configureForApp(androidComponents)
        }
        
        project.pluginManager.withPlugin("com.android.library") {
            val androidComponents = project.extensions.getByType(LibraryAndroidComponentsExtension::class.java) 
            taskProvider.configureForLibrary(androidComponents)
        }
    }
}