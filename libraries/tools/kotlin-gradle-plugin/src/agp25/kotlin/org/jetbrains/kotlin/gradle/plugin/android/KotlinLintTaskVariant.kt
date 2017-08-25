package org.jetbrains.kotlin.gradle.plugin.android

import com.android.builder.model.*

class KotlinLintTaskVariant : Variant {
    private val name = ""
    private val displayName = ""
    private val buildTypeName = ""
    private val productFlavorNames = emptyList<String>()
    private val mergedFlavor: ProductFlavor? = null
    private val mainArtifactInfo: AndroidArtifact? = null
    private val extraAndroidArtifacts: Collection<AndroidArtifact>? = null
    private val extraJavaArtifacts: Collection<JavaArtifact>? = null
    private val testedTargetVariants: Collection<TestedTargetVariant>? = null

    override fun getName() = name
    override fun getDisplayName() = displayName
    override fun getBuildType() = buildTypeName
    override fun getProductFlavors() = productFlavorNames
    override fun getMergedFlavor() = mergedFlavor
    override fun getMainArtifact() = mainArtifactInfo
    override fun getExtraAndroidArtifacts() = extraAndroidArtifacts
    override fun getExtraJavaArtifacts() = extraJavaArtifacts
    override fun getTestedTargetVariants() = testedTargetVariants
}
