// INSPECTION_CLASS: org.jetbrains.android.inspections.klint.AndroidLintInspectionToolProvider$AndroidKLintSupportAnnotationUsageInspection
// DEPENDENCY: IntRange.java -> android/support/annotation/IntRange.java
// DEPENDENCY: RequiresPermission.java -> android/support/annotation/RequiresPermission.java


import android.support.annotation.IntRange
import android.support.annotation.RequiresPermission
import android.Manifest
import android.view.View

const val constantVal = 0L

<error descr="Invalid range: the `from` attribute must be less than the `to` attribute">@IntRange(from = 10, to = 0)</error>
fun invalidRange1a(): Int = 5

@IntRange(from = constantVal, to = 10) // ok
fun invalidRange0b(): Int = 5

<error descr="Invalid range: the `from` attribute must be less than the `to` attribute">@IntRange(from = 10, to = constantVal)</error>
fun invalidRange1b(): Int = 5


// should be ok, KT-16600
@RequiresPermission(anyOf = arrayOf(Manifest.permission.ACCESS_CHECKIN_PROPERTIES,
                                    Manifest.permission.ACCESS_FINE_LOCATION))
fun needsPermissions() { }