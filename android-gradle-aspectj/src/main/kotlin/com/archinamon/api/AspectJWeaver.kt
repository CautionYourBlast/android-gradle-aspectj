package com.archinamon.api

import com.archinamon.utils.logBuildParametersAdapted
import com.archinamon.utils.logExtraAjcArgumentAlreadyExists
import org.aspectj.bridge.IMessage
import org.aspectj.bridge.MessageHandler
import org.aspectj.tools.ajc.Main
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File
import java.util.*
import java.util.concurrent.CyclicBarrier

internal class AspectJWeaver(val project: Project) {

    private val errorReminder = "Look into %s file for details"

    var compilationLogFile: String? = null
        internal set(name) {
            if (name?.length!! > 0) {
                field = project.buildDir.absolutePath + File.separator + name
            }
        }

    var transformLogFile: String? = null
        internal set(name) {
            if (name?.length!! > 0) {
                field = project.buildDir.absolutePath + File.separator + name
            }
        }

    var encoding: String? = null

    var weaveInfo: Boolean = false
    var debugInfo: Boolean = false
    var addSerialVUID: Boolean = false
    var noInlineAround: Boolean = false
    var ignoreErrors: Boolean = false

    var breakOnError: Boolean = false
    var experimental: Boolean = false

    var ajcArgs = LinkedHashSet<String>()

    @Suppress("SetterBackingFieldAssignment")
    var ajSources: MutableSet<File> = LinkedHashSet()
        internal set(ajSources) {
            ajSources.forEach { field.add(it) }
        }

    var aspectPath: MutableSet<File> = LinkedHashSet()
    var inPath: MutableSet<File> = LinkedHashSet()
    var classPath: MutableSet<File> = LinkedHashSet()
    var bootClasspath: String? = null
    var sourceCompatibility: String? = null
    var targetCompatibility: String? = null
    var destinationDir: String? = null

    internal fun doWeave() {
        val log = prepareLogger()

        //http://www.eclipse.org/aspectj/doc/released/devguide/ajc-ref.html

        val args = mutableListOf(
            "-encoding", encoding,
            "-source", sourceCompatibility,
            "-target", targetCompatibility,
            "-d", destinationDir,
            "-bootclasspath", bootClasspath,
            "-classpath", classPath.joinToString(separator = File.pathSeparator)
        )

        if (ajSources.isNotEmpty()) {
            args.addAll(listOf("-sourceroots", ajSources.joinToString(separator = File.pathSeparator)))
        }

        if (inPath.isNotEmpty()) {
            args.addAll(listOf("-inpath", inPath.joinToString(separator = File.pathSeparator)))
        }

        if (aspectPath.isNotEmpty()) {
            args.addAll(listOf("-aspectpath", aspectPath.joinToString(separator = File.pathSeparator)))
        }

        if (getLogFile().isNotBlank()) {
            args.addAll(listOf("-log", getLogFile()))
        }

        if (debugInfo) {
            args.add("-g")
        }

        if (weaveInfo) {
            args.add("-showWeaveInfo")
        }

        if (addSerialVUID) {
            args.add("-XaddSerialVersionUID")
        }

        if (noInlineAround) {
            args.add("-XnoInline")
        }

        if (ignoreErrors) {
            args.addAll(listOf("-proceedOnError", "-noImportError"))
        }

        if (experimental) {
            args.addAll(listOf("-XhasMember", "-Xjoinpoints:synchronization,arrayconstruction"))
        }

        if (ajcArgs.isNotEmpty()) {
            ajcArgs.forEach { extra ->
                if (extra.startsWith('-') && args.contains(extra)) {
                    logExtraAjcArgumentAlreadyExists(extra)
                    log.writeText("[warning] Duplicate argument found while composing ajc config! Build may be corrupted.\n\n")
                }
                args.add(extra)
            }
        }

        log.writeText("Full ajc build args: ${args.joinToString()}\n\n")
        logBuildParametersAdapted(args, log.name)

        runBlocking {
            val handler = MessageHandler(true)
            Main().run(args.toTypedArray(), handler)

            for (message in handler.getMessages(null, true)) {
                when (message.kind) {
                    IMessage.ERROR -> {
                        log.writeText("[error]" + message?.message + "${message?.thrown}\n\n")
                        if (breakOnError) throw GradleException (errorReminder.format(getLogFile()))
                    }
                    IMessage.FAIL, IMessage.ABORT -> {
                        log.writeText("[error]" + message?.message + "${message?.thrown}\n\n")
                        throw GradleException (message.message)
                    }
                    IMessage.INFO, IMessage.DEBUG, IMessage.WARNING -> {
                        log.writeText("[warning]" + message?.message + "${message?.thrown}\n\n")
                        if (getLogFile().isNotBlank()) log.writeText("${errorReminder.format(getLogFile())}\n\n")
                    }
                }
            }

            detectErrors()
        }
    }

    private fun getLogFile(): String {
        return compilationLogFile ?: transformLogFile!!
    }

    private fun prepareLogger(): File {
        val lf = project.file(getLogFile())
        if (lf.exists()) {
            lf.delete()
        }

        return lf
    }

    private fun detectErrors() {
        val lf: File  = project.file(getLogFile())
        if (lf.exists()) {
            lf.readLines().forEach { line ->
                if (line.contains("[error]") && breakOnError) {
                    throw GradleException ("$line\n${errorReminder.format(getLogFile())}")
                }
            }
        }
    }

    private inline operator fun <reified E> MutableCollection<in E>.plus(elem: E): MutableCollection<in E> {
        this.add(elem)
        return this
    }

    private companion object {

        fun runBlocking(action: () -> Unit) = synchronized(Companion::class, action)
    }
}