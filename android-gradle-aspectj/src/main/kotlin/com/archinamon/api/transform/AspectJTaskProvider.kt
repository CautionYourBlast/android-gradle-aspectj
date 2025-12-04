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
    
    @get:Internal
    internal var aspectJWeaver: AspectJWeaver? = null
    
    @get:Internal
    internal var androidConfig: AndroidConfig? = null
    
    @get:Internal
    internal var variantName: String? = null
    
    @get:Internal
    internal var javaCompileTask: org.gradle.api.tasks.compile.JavaCompile? = null
    
    @TaskAction
    fun taskAction() {
        val weaver = aspectJWeaver ?: return
        val config = androidConfig ?: return
        val variant = variantName ?: return
        val javaTask = javaCompileTask ?: return

        logger.info("Starting AspectJ weaving for variant: $variant")

        // Add Kotlin compiled classes to inpath at execution time (when they exist)
        val kotlinClassesDir = project.file("${project.buildDir}/tmp/kotlin-classes/$variant")
        if (kotlinClassesDir.exists()) {
            weaver.inPath.add(kotlinClassesDir)
            logger.info("Added Kotlin classes to AspectJ inpath: ${kotlinClassesDir}")
        }

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
            
            // Now we can safely access the classpath at task execution time
            classPath.addAll(javaTask.classpath.files)
            
            // Process includeJar/excludeJar filters to add external JARs to inpath
            val includeJars = aspectJConfig.includeJar  
            val excludeJars = aspectJConfig.excludeJar
            val includeAspects = aspectJConfig.includeAspectsFromJar
            
            // Process classpath JARs for weaving
            javaTask.classpath.files.forEach { file ->
                if (file.name.endsWith(".jar")) {
                    val includeAllJars = aspectJConfig.includeAllJars
                    val includeFilterMatched = includeJars.isNotEmpty() && 
                        com.archinamon.utils.DependencyFilter.isIncludeFilterMatched(file, includeJars)
                    val excludeFilterMatched = excludeJars.isNotEmpty() && 
                        com.archinamon.utils.DependencyFilter.isExcludeFilterMatched(file, excludeJars)
                    
                    if (includeAllJars || includeFilterMatched) {
                        if (!excludeFilterMatched) {
                            logger.info("Adding JAR to inpath: ${file.name}")
                            inPath.add(file)
                        } else {
                            logger.info("Excluding JAR from inpath: ${file.name}")
                        }
                    }
                    
                    // Handle aspectPath for compiled aspects in JARs
                    val includeAspectsFilterMatched = includeAspects.isNotEmpty() && 
                        com.archinamon.utils.DependencyFilter.isIncludeFilterMatched(file, includeAspects)
                    if (includeAspectsFilterMatched) {
                        logger.info("Adding JAR to aspectpath: ${file.name}")  
                        aspectPath.add(file)
                    }
                }
            }
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
            
            // Also look for Kotlin compile task (so Kotlin classes are available)
            val kotlinCompileTaskName = "compile${variantName.capitalize()}Kotlin"
            val kotlinCompileTask = project.tasks.findByName(kotlinCompileTaskName)

            // Also look for KAPT tasks (Hilt annotation processing)
            val kaptTaskName = "kapt${variantName.capitalize()}Kotlin"
            val kaptTask = project.tasks.findByName(kaptTaskName)

            if (javaCompileTask != null) {
                val javaTask = javaCompileTask as org.gradle.api.tasks.compile.JavaCompile
                val compiledClassesDir = javaTask.destinationDirectory.asFile.get()

                task.dependsOn(javaCompileTask)
                task.javaCompileTask = javaTask

                // Ensure AspectJ runs after Kotlin compilation (so Kotlin classes exist)
                if (kotlinCompileTask != null) {
                    task.dependsOn(kotlinCompileTask)
                    task.mustRunAfter(kotlinCompileTask)
                }

                // Ensure AspectJ runs after KAPT (Hilt code generation)
                if (kaptTask != null) {
                    task.dependsOn(kaptTask)
                    task.mustRunAfter(kaptTask)
                }
                
                // Make the weaving task run automatically during builds
                // We need to hook into the build pipeline after Java compilation but before DEX/packaging
                
                // Connect to assemble task 
                project.tasks.named("assemble${variantName.capitalize()}") { 
                    dependsOn(task) 
                }
                
                // Connect to DEX builder task (processes compiled classes)
                project.tasks.matching { it.name.startsWith("dexBuilder") && it.name.contains(variantName, ignoreCase = true) }
                    .configureEach { dependsOn(task) }
                
                // Connect to transform tasks that process classes  
                project.tasks.matching { it.name.startsWith("transform") && it.name.contains(variantName, ignoreCase = true) }
                    .configureEach { dependsOn(task) }
                
                // Configure AspectJ weaver with compiled classes immediately
                // Note: Don't access javaTask.classpath.files here as it triggers configuration-time resolution
                // Kotlin classes are added at execution time (see taskAction method)
                task.aspectJWeaver?.apply {
                    // Set the actual compiled classes directory as both inpath and destination
                    inPath.add(compiledClassesDir)
                    destinationDir = compiledClassesDir.absolutePath
                }

                project.logger.info("Configured AspectJ weaving task: $taskName with inPath: ${compiledClassesDir}")
            } else {
                project.logger.warn("Could not find Java compile task: $javaCompileTaskName")
            }
        }
    }
}