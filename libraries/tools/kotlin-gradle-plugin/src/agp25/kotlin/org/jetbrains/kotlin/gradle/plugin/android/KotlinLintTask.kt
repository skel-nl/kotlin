package org.jetbrains.kotlin.gradle.plugin.android

import com.android.build.gradle.internal.dsl.LintOptions
import com.android.builder.model.LintOptions as ILintOptions
import com.android.build.gradle.internal.scope.VariantScope
import com.android.build.gradle.internal.tasks.BaseTask
import com.android.build.gradle.tasks.Lint
import com.android.builder.core.AndroidBuilder
import com.android.builder.model.*
import com.android.ide.common.repository.GradleCoordinate
import com.android.manifmerger.ManifestMerger2
import com.android.repository.Revision
import com.android.sdklib.BuildToolInfo
import com.android.tools.lint.Reporter
import com.android.tools.lint.checks.ApiParser
import com.intellij.openapi.project.Project as IdeaProject
import com.android.tools.lint.client.api.LintRequest
import com.google.common.collect.Sets
import com.google.gson.*
import com.intellij.util.PathUtil
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.AbstractTask
import org.gradle.api.tasks.ParallelizableTask
import org.gradle.api.tasks.TaskAction
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import org.jetbrains.kotlin.gradle.plugin.Android25ProjectHandler.Companion.LINT_WITH_KOTLIN_CONFIGURATION_NAME
import org.jetbrains.uast.UastContext
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.analysis.AnalyzerException
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.lang.reflect.Field
import java.net.URL
import java.net.URLClassLoader

@ParallelizableTask
open class KotlinLintTask : AbstractTask() {
    private companion object {
        inline fun <reified T : Any> instanceCreator(newClassLoader: ClassLoader) = InstanceCreator {
            Class.forName(T::class.java.name, true, newClassLoader).newInstance()
        }

        fun createGson(newClassLoader: ClassLoader): Gson {
            val lintOptions = Class.forName(ILintOptions::class.java.name, false, newClassLoader)
            val variant = Class.forName(Variant::class.java.name, false, newClassLoader)

            return GsonBuilder()
                    .addDeserializationExclusionStrategy(object : ExclusionStrategy {
                        val excludedNames = listOf(
                                SigningConfig::class.java,
                                AaptOptions::class.java,
                                ArtifactMetaData::class.java,
                                JavaCompileOptions::class.java,
                                ProductFlavorContainer::class.java,
                                SyncIssue::class.java,
                                BuildTypeContainer::class.java,
                                NativeToolchain::class.java
                        ).map { it.name }

                        override fun shouldSkipClass(clazz: Class<*>) = clazz.name in excludedNames
                        override fun shouldSkipField(f: FieldAttributes?) = false
                    })
                    .registerTypeAdapter(lintOptions, instanceCreator<LintOptions>(newClassLoader))
                    .registerTypeAdapter(variant, instanceCreator<KotlinLintTaskVariant>(newClassLoader))
                    .create()
        }
    }

    lateinit var javaLintTask: Lint

    @TaskAction
    fun lint() {
        val androidBuilder = javaLintTask.field<BaseTask>("androidBuilder").get<AndroidBuilder>()

        val project = javaLintTask.project
        val variantName = javaLintTask.variantName
        val _buildTools = androidBuilder.targetInfo.buildTools
        val _lintOptions = javaLintTask.field("lintOptions").getOrNull<LintOptions>()
        val sdkHome = javaLintTask.field("sdkHome").getOrNull<File>()
        val fatalOnly = javaLintTask.field("fatalOnly").getOrNull<Boolean>() ?: false
        val _toolingRegistry = javaLintTask.field("toolingRegistry").get<ToolingModelBuilderRegistry>()
        val reportsDir = javaLintTask.field("reportsDir").getOrNull<File>()
        val manifestReportFile = javaLintTask.field("manifestReportFile").getOrNull<File>()
        val outputsDir = javaLintTask.field("outputsDir").get<File>()
        val manifestsForVariant = javaLintTask.field("manifestsForVariant").getOrNull<FileCollection>()
        val _variantScope = javaLintTask.field("variantScope").getOrNull<VariantScope>()

        val topmostClassLoader = findClassLoaderForLint(javaClass.classLoader)
        val classLoaderForLint = URLClassLoader(collectLibrariesForClasspath(project), topmostClassLoader)
        val gson = createGson(classLoaderForLint)

        val buildTools = mapNotNull(gson, _buildTools, classLoaderForLint)
        val lintOptions = map(gson, _lintOptions, classLoaderForLint)
        val androidProject = mapNotNull(gson, createAndroidProject(project, _toolingRegistry), classLoaderForLint)
        val variantScope = map(gson, _variantScope, classLoaderForLint)

        val executorClass = Class.forName(KotlinLintExecutor::class.java.name, true, classLoaderForLint)
        val executor = executorClass.constructors.single().newInstance(
                project, variantName, buildTools, lintOptions, sdkHome, fatalOnly, androidProject,
                reportsDir, manifestReportFile, outputsDir, manifestsForVariant, variantScope)

        executorClass.getDeclaredMethod("lint").invoke(executor)
    }

    private fun map(gson: Gson, obj: Any?, newClassLoader: ClassLoader): Any? {
        return if (obj == null) null else mapNotNull(gson, obj, newClassLoader)
    }

    private fun mapNotNull(gson: Gson, obj: Any, newClassLoader: ClassLoader): Any {
        fun findTargetClass(clazz: Class<*>) =
                if (clazz.name.endsWith("_Decorated")) clazz.superclass ?: clazz else clazz

        val className = findTargetClass(obj.javaClass).let { it.canonicalName ?: it.name }
        val clazzFromNewClassLoader = Class.forName(className, false, newClassLoader)

        try {
            val data = gson.toJson(obj)
            return gson.fromJson(data, clazzFromNewClassLoader)
        } catch (e: Throwable) {
            throw RuntimeException("Can't map $className", e)
        }
    }

    private fun findClassLoaderForLint(classLoader: ClassLoader): ClassLoader {
        if (classLoader.defines<Project>()
                && classLoader.defines<FileCollection>()
                && !classLoader.defines<KotlinLintExecutor>()
                && !classLoader.defines<IdeaProject>()
                && !classLoader.defines<UastContext>()
                && !classLoader.defines<LintRequest>()
                && !classLoader.defines<BuildToolInfo>()
        ) {
            return classLoader
        }

        val parent = classLoader.parent ?: error("Can't find a classloader for Lint with Kotlin")
        return findClassLoaderForLint(parent)
    }

    private inline fun <reified T : Any> ClassLoader.defines(): Boolean {
        if (T::class.java.enclosingClass != null) {
            throw IllegalArgumentException("Only top level classes are supported, got ${T::class.java.name}")
        }

        return try {
            Class.forName(T::class.java.name, false, this) != null
        } catch (e: Exception) {
            false
        }
    }

    private fun collectLibrariesForClasspath(project: Project): Array<URL> {
        val kotlinGradlePlugin = findLibraryFor<KotlinLintTask>()
        val kotlinCompilerAndUast = project.configurations
                .getByName(LINT_WITH_KOTLIN_CONFIGURATION_NAME)
                .resolve()
                .map { it.toURI().toURL() }
                .toTypedArray()

        //TODO uast kotlin
        //TODO uast java

        // Android libraries
        val builder = findLibraryFor<AndroidBuilder>()
        val gradleCore = findLibraryFor<LintOptions>()
        val sdklib = findLibraryFor<BuildToolInfo>()
        val sdkCommon = findLibraryFor<GradleCoordinate>()
        val repository = findLibraryFor<Revision>()
        val builderModel = findLibraryFor<com.android.builder.model.LintOptions>()
        val common = findLibraryFor<com.android.utils.Pair<*, *>>()
        val manifestMerger = findLibraryFor<ManifestMerger2>()

        // Third-parties
        val asm = findLibraryFor<ClassVisitor>()
        val asmTree = findLibraryFor<AbstractInsnNode>()
        val asmAnalysis = findLibraryFor<AnalyzerException>()
        val guava = findLibraryFor<Sets>()
        val gson = findLibraryFor<Gson>()
        val kxml = findLibraryFor<XmlPullParserException>()

        // Lint
        val lint = findLibraryFor<Reporter>()
        val lintApi = findLibraryFor<LintRequest>()
        val lintChecks = findLibraryFor<ApiParser>()
        val uastJava = findLibraryFor<UastContext>() // TODO remove

        return arrayOf(guava, kotlinGradlePlugin, *kotlinCompilerAndUast,
                builder, gradleCore, sdklib, sdkCommon, repository, builderModel, common, manifestMerger,
                asm, asmTree, asmAnalysis, gson, kxml,
                lint, lintApi, lintChecks, uastJava).apply {
            project.logger.warn("Lint with Kotlin classpath: ${this.joinToString { it.path }}")
        }
    }

    private inline fun <reified T : Any> findLibraryFor(): URL {
        if (T::class.java.enclosingClass != null) {
            throw IllegalArgumentException("Only top level classes are supported, got ${T::class.java.name}")
        }

        return File(PathUtil.getJarPathForClass(T::class.java)).toURI().toURL()
    }

    private fun createAndroidProject(
            gradleProject: Project,
            toolingRegistry: ToolingModelBuilderRegistry
    ): AndroidProject {
        val modelName = AndroidProject::class.java.name
        val modelBuilder = toolingRegistry.getBuilder(modelName)

        // setup the level 3 sync.
        val ext = gradleProject.extensions.extraProperties
        ext.set(
                AndroidProject.PROPERTY_BUILD_MODEL_ONLY_VERSIONED,
                Integer.toString(AndroidProject.MODEL_LEVEL_3_VARIANT_OUTPUT_POST_BUILD))

        try {
            return modelBuilder.buildAll(modelName, gradleProject) as AndroidProject
        } finally {
            ext.set(AndroidProject.PROPERTY_BUILD_MODEL_ONLY_VERSIONED, null)
        }
    }
}

private inline fun <reified T: Any> T.field(name: String) = FieldWrapper(this, T::class.java.getDeclaredField(name))

private class FieldWrapper(val obj: Any, val field: Field) {
    private fun Field.obtain(): Any? {
        val oldIsAccessible = isAccessible
        try {
            isAccessible = true
            return get(obj)
        } finally {
            isAccessible = oldIsAccessible
        }
    }

    inline fun <reified T> getOrNull(): T? = field.obtain() as? T
    inline fun <reified T> get(): T = field.obtain() as? T ?: error("Unable to get ${field.name} from $obj")
}