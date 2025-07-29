package com.archinamon.api.transform

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.archinamon.AndroidConfig
import com.archinamon.api.AspectJWeaver
import com.archinamon.utils.*
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.*
import java.io.File

/**
 * Simple task-based AspectJ integration for AGP 8.x
 * This approach integrates AspectJ weaving as a post-compilation step
 */
internal open class AspectJWeavingTask : DefaultTask() {
    
    internal var aspectJWeaver: AspectJWeaver? = null
    internal var androidConfig: AndroidConfig? = null
    internal var variantName: String? = null
    
    @TaskAction
    fun taskAction() {
        val weaver = aspectJWeaver ?: return
        val config = androidConfig ?: return  
        val variant = variantName ?: return
        
        logger.info("Starting AspectJ weaving for variant: $variant")
        
        // Find AspectJ source files for this variant
        val ajSources = findAjSourcesForVariant(project, variant)
        weaver.ajSources = ajSources
        
        // Configure weaver settings
        val aspectJConfig = config.aspectj()
        weaver.apply {
            sourceCompatibility = aspectJConfig.java.toString()
            targetCompatibility = aspectJConfig.java.toString()
            bootClasspath = config.getBootClasspath().joinToString(separator = File.pathSeparator)
            encoding = "UTF-8"
            
            compilationLogFile = aspectJConfig.compilationLogFile
            addSerialVUID = aspectJConfig.addSerialVersionUID
            debugInfo = aspectJConfig.debugInfo
            noInlineAround = aspectJConfig.noInlineAround
            ignoreErrors = aspectJConfig.ignoreErrors
            breakOnError = aspectJConfig.breakOnError
            experimental = aspectJConfig.experimental
            
            ajcArgs.addAll(aspectJConfig.ajcArgs)
        }
        
        // Perform weaving
        weaver.doWeave()
        
        logger.info("AspectJ weaving completed for variant: $variant")
    }
}

/**
 * Provider for creating and configuring AspectJ weaving tasks
 */
internal class AspectJTaskProvider(
    private val project: Project,
    private val config: AndroidConfig
) {
    
    fun configureForApp(extension: ApplicationAndroidComponentsExtension) {
        extension.onVariants { variant ->
            createWeavingTask(variant.name)
        }
    }
    
    fun configureForLibrary(extension: LibraryAndroidComponentsExtension) {
        extension.onVariants { variant ->
            createWeavingTask(variant.name)
        }
    }
    
    private fun createWeavingTask(variantName: String) {
        val taskName = "aspectjWeave${variantName.capitalize()}"
        
        val task = project.tasks.create(taskName, AspectJWeavingTask::class.java)
        task.group = "aspectj"
        task.description = "Weave AspectJ aspects for $variantName variant"
        task.aspectJWeaver = AspectJWeaver(project)
        task.androidConfig = config
        task.variantName = variantName
        
        // Configure basic task dependencies after evaluation
        project.afterEvaluate {
            val javaCompileTaskName = "compile${variantName.capitalize()}JavaWithJavac"
            val javaCompileTask = project.tasks.findByName(javaCompileTaskName)
            
            if (javaCompileTask != null) {
                task.dependsOn(javaCompileTask)
                
                // Configure AspectJ weaver with compiled classes
                task.doFirst {
                    val compiledClassesDir = (javaCompileTask as? org.gradle.api.tasks.compile.JavaCompile)?.destinationDirectory?.asFile?.get()
                    if (compiledClassesDir?.exists() == true) {
                        task.aspectJWeaver?.inPath?.add(compiledClassesDir)
                        task.aspectJWeaver?.destinationDir = compiledClassesDir.absolutePath
                    }
                }
            }
        }
    }
}