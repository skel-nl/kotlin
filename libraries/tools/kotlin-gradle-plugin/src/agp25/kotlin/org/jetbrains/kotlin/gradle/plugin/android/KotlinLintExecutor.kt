package org.jetbrains.kotlin.gradle.plugin.android

import com.android.annotations.NonNull
import com.android.build.gradle.internal.LintGradleClient
import com.android.build.gradle.internal.dsl.LintOptions
import com.android.build.gradle.internal.scope.VariantScope
import com.android.build.gradle.tasks.GroovyGradleDetector
import com.android.build.gradle.tasks.Lint
import com.android.builder.model.AndroidProject
import com.android.builder.model.Variant
import com.android.sdklib.BuildToolInfo
import com.android.tools.lint.LintCliFlags
import com.android.tools.lint.Reporter
import com.android.tools.lint.Reporter.Stats
import com.android.tools.lint.Warning
import com.android.tools.lint.checks.BuiltinIssueRegistry
import com.android.tools.lint.checks.GradleDetector
import com.android.tools.lint.checks.UnusedResourceDetector
import com.android.tools.lint.client.api.LintBaseline
import com.android.tools.lint.detector.api.*
import com.android.utils.FileUtils
import com.android.utils.Pair
import com.google.common.collect.Lists
import com.google.common.collect.Maps
import com.google.common.collect.Sets
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.TaskAction

import java.io.File
import java.io.IOException
import java.util.Comparator

import com.android.SdkConstants.VALUE_FALSE

class KotlinLintExecutor(
        val project: Project,
        val variantName: String?,
        val buildTools: BuildToolInfo,
        private var lintOptions: LintOptions?,
        private var sdkHome: File?,
        private var fatalOnly: Boolean,
        private var androidProject: AndroidProject,
        private var reportsDir: File?,
        private var manifestReportFile: File?,
        var outputsDir: File?,
        val manifestsForVariant: FileCollection?,
        private val variantScope: VariantScope?
) {
    @TaskAction
    @Throws(IOException::class)
    fun lint() {
        // we run by default in headless mode, so the JVM doesn't steal focus.
        System.setProperty("java.awt.headless", "true")

        val modelProject = androidProject // createAndroidProject(project)
        if (variantName != null && !variantName.isEmpty()) {
            for (variant in modelProject.variants) {
                if (variant.name == variantName) {
                    lintSingleVariant(modelProject, variant)
                }
            }
        } else {
            lintAllVariants(modelProject)
        }
    }

    /**
     * Runs lint individually on all the variants, and then compares the results
     * across variants and reports these
     */
    @Throws(IOException::class)
    fun lintAllVariants(@NonNull modelProject: AndroidProject) {
        // In the Gradle integration we iterate over each variant, and
        // attribute unused resources to each variant, so don't make
        // each variant run go and inspect the inactive variant sources
        UnusedResourceDetector.sIncludeInactiveReferences = false

        val warningMap = Maps.newHashMap<Variant, List<Warning>>()
        val baselines = Lists.newArrayList<LintBaseline>()
        for (variant in modelProject.variants) {
            val pair = runLint(modelProject, variant, false)
            val warnings = pair.first
            warningMap.put(variant, warnings)
            val baseline = pair.second
            if (baseline != null) {
                baselines.add(baseline)
            }
        }

        // Compute error matrix
        var quiet = false
        if (lintOptions != null) {
            quiet = lintOptions!!.isQuiet
        }

        for ((variant, warnings) in warningMap) {
            if (!fatalOnly && !quiet) {
                LOG.warn("Ran lint on variant {}: {} issues found",
                        variant.name, warnings.size)
            }
        }

        val mergedWarnings = LintGradleClient.merge(warningMap, modelProject)
        var errorCount = 0
        var warningCount = 0
        for (warning in mergedWarnings) {
            if (warning.severity == Severity.ERROR || warning.severity == Severity.FATAL) {
                errorCount++
            } else if (warning.severity == Severity.WARNING) {
                warningCount++
            }
        }

        // We pick the first variant to generate the full report and don't generate if we don't
        // have any variants.
        if (!modelProject.variants.isEmpty()) {
            val allVariants = Sets.newTreeSet(
                    Comparator<Variant> { v1, v2 -> v1.name.compareTo(v2.name) })

            allVariants.addAll(modelProject.variants)
            val variant = allVariants.iterator().next()

            val registry = BuiltinIssueRegistry()
            val flags = LintCliFlags()
            val client = LintGradleClient(
                    registry, flags, project, modelProject,
                    sdkHome, variant, buildTools, getManifestReportFile(variant))
            syncOptions(lintOptions, client, flags, null, project, reportsDir,
                    true, fatalOnly)

            // Compute baseline counts. This is tricky because an error could appear in
            // multiple variants, and in that case it should only be counted as filtered
            // from the baseline once, but if there are errors that appear only in individual
            // variants, then they shouldn't count as one. To correctly account for this we
            // need to ask the baselines themselves to merge their results. Right now they
            // only contain the remaining (fixed) issues; to address this we'd need to move
            // found issues to a different map such that at the end we can successively
            // merge the baseline instances together to a final one which has the full set
            // of filtered and remaining counts.
            var baselineErrorCount = 0
            var baselineWarningCount = 0
            var fixedCount = 0
            if (!baselines.isEmpty()) {
                // Figure out the actual overlap; later I could stash these into temporary
                // objects to compare
                // For now just combine them in a dumb way
                for (baseline in baselines) {
                    baselineErrorCount = Math.max(baselineErrorCount,
                            baseline.foundErrorCount)
                    baselineWarningCount = Math.max(baselineWarningCount,
                            baseline.foundWarningCount)
                    fixedCount = Math.max(fixedCount, baseline.fixedCount)
                }
            }

            val stats = Stats(errorCount, warningCount, baselineErrorCount,
                    baselineWarningCount, fixedCount)

            for (reporter in flags.reporters) {
                reporter.write(stats, mergedWarnings)
            }

            val baselineFile = flags.baselineFile
            if (baselineFile != null && !baselineFile.exists()) {
                val dir = baselineFile.parentFile
                var ok = true
                if (!dir.isDirectory) {
                    ok = dir.mkdirs()
                }
                if (!ok) {
                    System.err.println("Couldn't create baseline folder " + dir)
                } else {
                    val reporter = Reporter.createXmlReporter(client, baselineFile, true)
                    reporter.write(stats, mergedWarnings)
                    System.err.println("Created baseline file " + baselineFile)
                    System.err.println("(Also breaking build in case this was not intentional.)")
                    val message = ""
                    "Created baseline file " + baselineFile + "\n"+
                    "\n"+
                    "Also breaking the build in case this was not intentional. If you\n"+
                    "deliberately created the baseline file, re-run the build and this\n"+
                    "time it should succeed without warnings.\n"+
                    "\n"+
                    "If not, investigate the baseline path in the lintOptions config\n"+
                    "or verify that the baseline file has been checked into version\n"+
                    "control.\n"
                    throw GradleException(message)
                }
            }

            if (baselineErrorCount > 0 || baselineWarningCount > 0) {
                println(String.format("%1\$s were filtered out because " + "they were listed in the baseline file, %2\$s\n",
                        LintUtils.describeCounts(baselineErrorCount, baselineWarningCount, false,
                                true),
                        baselineFile))
            }
            if (fixedCount > 0) {
                println(String.format("%1\$d errors/warnings were listed in the "
                        + "baseline file (%2\$s) but not found in the project; perhaps they have "
                        + "been fixed?\n", fixedCount, baselineFile))
            }

            if (flags.isSetExitCode && errorCount > 0) {
                abort()
            }
        }
    }

    private fun abort() {
        val message: String
        if (fatalOnly) {
            message = "" +
                    "Lint found fatal errors while assembling a release target.\n" +
                    "\n" +
                    "To proceed, either fix the issues identified by lint, or modify your build script as follows:\n" +
                    "...\n" +
                    "android {\n" +
                    "    lintOptions {\n" +
                    "        checkReleaseBuilds false\n" +
                    "        // Or, if you prefer, you can continue to check for errors in release builds,\n" +
                    "        // but continue the build even when errors are found:\n" +
                    "        abortOnError false\n" +
                    "    }\n" +
                    "}\n" +
                    "..."
        } else {
            message = "" +
                    "Lint found errors in the project; aborting build.\n" +
                    "\n" +
                    "Fix the issues identified by lint, or add the following to your build script to proceed with errors:\n" +
                    "...\n" +
                    "android {\n" +
                    "    lintOptions {\n" +
                    "        abortOnError false\n" +
                    "    }\n" +
                    "}\n" +
                    "..."
        }
        throw GradleException(message)
    }

    /**
     * Runs lint on a single specified variant
     */
    fun lintSingleVariant(@NonNull modelProject: AndroidProject, @NonNull variant: Variant) {
        runLint(modelProject, variant, true)
    }

    /** Runs lint on the given variant and returns the set of warnings  */
    private fun runLint(
            /*
             * Note that as soon as we disable {@link #MODEL_LIBRARIES} this is
             * unused and we can delete it and all the callers passing it recursively
             */
            @NonNull modelProject: AndroidProject,
            @NonNull variant: Variant,
            report: Boolean): Pair<List<Warning>, LintBaseline> {
        val registry = createIssueRegistry()
        val flags = LintCliFlags()
        val client = LintGradleClient(registry, flags, project, modelProject,
                sdkHome, variant, buildTools, getManifestReportFile(variant))
        if (fatalOnly) {
            flags.isFatalOnly = true
        }
        if (lintOptions != null) {
            syncOptions(lintOptions, client, flags, variant, project, reportsDir, report,
                    fatalOnly)
        }
        if (!report || fatalOnly) {
            flags.isQuiet = true
        }
        flags.isWriteBaselineIfMissing = report

        val warnings: Pair<List<Warning>, LintBaseline>
        try {
            warnings = client.run(registry)
        } catch (e: IOException) {
            throw GradleException("Invalid arguments.", e)
        }

        if (report && client.haveErrors() && flags.isSetExitCode) {
            abort()
        }

        return warnings
    }

    fun getManifestReportFile(variant: Variant?): File? {
        if (manifestReportFile == null && outputsDir != null && variant != null) {
            // When running the lint-all (all variants task) we don't
            // have a report file since it varies by variant; this
            // duplicates the logic in VariantScopeImpl#getManifestReportFile
            manifestReportFile = FileUtils.join(outputsDir,
                    "logs", "manifest-merger-" + variant.displayName
                    + "-report.txt")
            // variant.getDisplayName corresponds to variantData.getVariantConfiguration().getBaseName()
        }
        return manifestReportFile
    }

    // Issue registry when Lint is run inside Gradle: we replace the Gradle
    // detector with a local implementation which directly references Groovy
    // for parsing. In Studio on the other hand, the implementation is replaced
    // by a PSI-based check. (This is necessary for now since we don't have a
    // tool-agnostic API for the Groovy AST and we don't want to add a 6.3MB dependency
    // on Groovy itself quite yet.
    class LintGradleIssueRegistry : BuiltinIssueRegistry() {
        private var mInitialized: Boolean = false

        @NonNull
        override fun getIssues(): List<Issue> {
            val issues = super.getIssues()
            if (!mInitialized) {
                mInitialized = true
                for (issue in issues) {
                    if (issue.implementation.detectorClass == GradleDetector::class.java) {
                        issue.implementation = Implementation(
                                GroovyGradleDetector::class.java,
                                Scope.GRADLE_SCOPE)
                    }
                }
            }

            return issues
        }
    }

    companion object {

        /** Name of property used to enable [.MODEL_LIBRARIES]  */
        val MODEL_LIBRARIES_PROPERTY = "lint.new-lib-model" // for test access
        /**
         * Whether lint should attempt to do deep analysis of libraries. E.g. when
         * building up the project graph, when it encounters an AndroidLibrary or JavaLibrary
         * dependency, it should check if it's a local project, and if so recursively initialize
         * the project with the local source paths etc of the library (in the past, this was not
         * the case: it would naively just point to the library's resources and class files,
         * which were the compiled outputs.
         *
         *
         * The new behavior is clearly the correct behavior (see issue #194092), but since this
         * is a risky fix, we're putting it behind a flag now and as soon as we get some real
         * user testing, we should enable this by default and remove the old code.
         */
        val MODEL_LIBRARIES = VALUE_FALSE != System.getProperty(MODEL_LIBRARIES_PROPERTY)

        private val LOG = Logging.getLogger(Lint::class.java)

        private fun syncOptions(
                @NonNull options: LintOptions?,
                @NonNull client: LintGradleClient,
                @NonNull flags: LintCliFlags,
                variant: Variant?,
                @NonNull project: Project,
                reportsDir: File?,
                report: Boolean,
                fatalOnly: Boolean) {
            options!!.syncTo(client, flags, variant?.name, project,
                    reportsDir, report)

            val displayEmpty = !(fatalOnly || flags.isQuiet)
            for (reporter in flags.reporters) {
                reporter.isDisplayEmpty = displayEmpty
            }
        }

        private fun createIssueRegistry(): BuiltinIssueRegistry {
            return LintGradleIssueRegistry()
        }
    }
}