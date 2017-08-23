/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.resolve.calls.tower

import org.jetbrains.kotlin.incremental.components.LookupLocation
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.progress.ProgressIndicatorAndCompilationCanceledStatus
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.resolve.descriptorUtil.HIDES_MEMBERS_NAME_LIST
import org.jetbrains.kotlin.resolve.scopes.ImportingScope
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.resolve.scopes.ResolutionScope
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValueWithSmartCastInfo
import org.jetbrains.kotlin.resolve.scopes.utils.parentsWithSelf
import org.jetbrains.kotlin.util.OperatorNameConventions
import java.util.*

interface Candidate {
    // this operation should be very fast
    val isSuccessful: Boolean

    val status: ResolutionCandidateStatus
}

interface CandidateFactory<out C: Candidate> {
    fun createCandidate(
            towerCandidate: CandidateWithBoundDispatchReceiver,
            explicitReceiverKind: ExplicitReceiverKind,
            extensionReceiver: ReceiverValueWithSmartCastInfo?
    ): C
}

interface CandidateFactoryProviderForInvoke<C : Candidate> {

    // variable here is resolved, invoke -- only chosen
    fun transformCandidate(variable: C, invoke: C): C

    fun factoryForVariable(stripExplicitReceiver: Boolean): CandidateFactory<C>

    // foo() -> ReceiverValue(foo), context for invoke
    // null means that there is no invoke on variable
    fun factoryForInvoke(variable: C, useExplicitReceiver: Boolean): Pair<ReceiverValueWithSmartCastInfo, CandidateFactory<C>>?
}

sealed class TowerData {
    object Empty : TowerData()
    class OnlyImplicitReceiver(val implicitReceiver: ReceiverValueWithSmartCastInfo): TowerData()
    class TowerLevel(val level: ScopeTowerLevel) : TowerData()
    class BothTowerLevelAndImplicitReceiver(val level: ScopeTowerLevel, val implicitReceiver: ReceiverValueWithSmartCastInfo) : TowerData()
}

interface ScopeTowerProcessor<out C> {
    // Candidates with matched receivers (dispatch receiver was already matched in ScopeTowerLevel)
    // Candidates in one groups have same priority, first group has highest priority.
    fun process(data: TowerData): List<Collection<C>>

    fun recordLookups(skippedData: Collection<TowerData>, name: Name)

    val mayNeedBothTowerLevelAndImplicitReceiver: Boolean get() = false
}

interface SimpleScopeTowerProcessor<out C> : ScopeTowerProcessor<C> {
    fun simpleProcess(data: TowerData): Collection<C>

    override fun process(data: TowerData): List<Collection<C>> = listOfNotNull(simpleProcess(data).takeIf { it.isNotEmpty() })
}

class TowerResolver {
    fun <C: Candidate> runResolve(
            scopeTower: ImplicitScopeTower,
            processor: ScopeTowerProcessor<C>,
            useOrder: Boolean,
            name: Name
    ): Collection<C> = scopeTower.run(processor, SuccessfulResultCollector { it.status }, useOrder, name)

    fun <C: Candidate> collectAllCandidates(
            scopeTower: ImplicitScopeTower,
            processor: ScopeTowerProcessor<C>,
            name: Name
    ): Collection<C>
            = scopeTower.run(processor, AllCandidatesCollector { it.status }, false, name)

    private data class ScopeLevelsAndSkippedForLookups(
            val levelsToProcess: Collection<ScopeTowerLevel>,
            val levelsToSkip: Collection<ScopeTowerLevel>
    )

    private fun ImplicitScopeTower.createNonLocalLevels(name: Name): ScopeLevelsAndSkippedForLookups {
        val mainResult = mutableListOf<ScopeTowerLevel>()
        val skippedLevels = mutableListOf<ScopeTowerLevel>()

        lexicalScope.parentsWithSelf.forEach { scope ->
            if (scope is LexicalScope) {
                if (!scope.kind.withLocalDescriptors) {
                    val level = ScopeBasedTowerLevel(this@createNonLocalLevels, scope)
                    if (scope.mayFitForName(name, location)) {
                        mainResult.add(level)
                    }
                    else {
                        skippedLevels.add(level)
                    }
                }

                getImplicitReceiver(scope)?.let {
                    val level = MemberScopeTowerLevel(this@createNonLocalLevels, it)
                    if (level.mayFitForName(name)) {
                        mainResult.add(level)
                    }
                    else {
                        skippedLevels.add(level)
                    }
                }
            }
            else {
                val level = ImportingScopeBasedTowerLevel(this@createNonLocalLevels, scope as ImportingScope)
                if (scope.mayFitForName(name, location)) {
                    mainResult.add(level)
                }
                else {
                    skippedLevels.add(level)
                }
            }
        }

        return ScopeLevelsAndSkippedForLookups(mainResult, skippedLevels)
    }

    private fun <C> ImplicitScopeTower.run(
            processor: ScopeTowerProcessor<C>,
            resultCollector: ResultCollector<C>,
            useOrder: Boolean,
            name: Name
    ): Collection<C> {
        val skippedDataForLookup = mutableListOf<TowerData>()

        fun recordLookups() {
            processor.recordLookups(skippedDataForLookup, name)
        }

        fun TowerData.process(): Collection<C>? =
            processTowerData(processor, resultCollector, useOrder, this)?.also {
                recordLookups()
            }

        // Lazy calculation
        var nonLocalLevels: Collection<ScopeTowerLevel>? = null
        val hidesMembersLevel = HidesMembersTowerLevel(this)
        val syntheticLevel = SyntheticScopeBasedTowerLevel(this, syntheticScopes)

        if (name in HIDES_MEMBERS_NAME_LIST) {
            // hides members extensions for explicit receiver
            TowerData.TowerLevel(hidesMembersLevel).process()?.let { return it }
        }

        // possibly there is explicit member
        TowerData.Empty.process()?.let { return it }
        // synthetic property for explicit receiver
        TowerData.TowerLevel(syntheticLevel).process()?.let { return it }

        val localLevels =
                lexicalScope.parentsWithSelf.
                        filterIsInstance<LexicalScope>().filter { it.kind.withLocalDescriptors && it.mayFitForName(name, location) }.
                        map { ScopeBasedTowerLevel(this@run, it) }.toList()

        // local non-extensions or extension for explicit receiver
        for (localLevel in localLevels) {
            TowerData.TowerLevel(localLevel).process()?.let { return it }
        }

        for (scope in lexicalScope.parentsWithSelf) {
            if (scope is LexicalScope) {
                // statics
                if (!scope.kind.withLocalDescriptors) {
                    val towerLevel = TowerData.TowerLevel(ScopeBasedTowerLevel(this, scope))

                    if (scope.mayFitForName(name, location)) {
                        towerLevel.process()?.let { return it }
                    }
                    else {
                        skippedDataForLookup.add(towerLevel)
                    }
                }

                val implicitReceiver = getImplicitReceiver(scope)
                if (implicitReceiver != null) {
                    if (name in HIDES_MEMBERS_NAME_LIST) {
                        // hides members extensions
                        TowerData.BothTowerLevelAndImplicitReceiver(hidesMembersLevel, implicitReceiver).process()?.let { return it }
                    }

                    // members of implicit receiver or member extension for explicit receiver
                    MemberScopeTowerLevel(this, implicitReceiver).let {
                        if (it.mayFitForName(name)) {
                            TowerData.TowerLevel(it).process()?.let { return it }
                        }
                        else {
                            skippedDataForLookup.add(TowerData.TowerLevel(it))
                        }
                    }

                    // synthetic properties
                    TowerData.BothTowerLevelAndImplicitReceiver(syntheticLevel, implicitReceiver).process()?.let { return it }

                    // invokeExtension on local variable
                    TowerData.OnlyImplicitReceiver(implicitReceiver).process()?.let { return it }

                    // local extensions for implicit receiver
                    for (localLevel in localLevels) {
                        TowerData.BothTowerLevelAndImplicitReceiver(localLevel, implicitReceiver).process()?.let { return it }
                    }

                   if (nonLocalLevels == null) {
                        val (toProcess, toSkip) = createNonLocalLevels(name)
                        nonLocalLevels = toProcess
                        toSkip.mapTo(skippedDataForLookup) { TowerData.BothTowerLevelAndImplicitReceiver(it, implicitReceiver) }
                    }

                    // extension for implicit receiver
                    for (nonLocalLevel in nonLocalLevels) {
                        TowerData.BothTowerLevelAndImplicitReceiver(nonLocalLevel, implicitReceiver).process()?.let { return it }
                    }
                }
            }
            else {
                val towerLevel = TowerData.TowerLevel(ImportingScopeBasedTowerLevel(this, scope as ImportingScope))

                if (scope.mayFitForName(name, location)) {
                    towerLevel.process()?.let { return it }
                }
                else {
                    skippedDataForLookup.add(towerLevel)
                }
            }
        }

        recordLookups()

        return resultCollector.getFinalCandidates()
    }

    private fun MemberScopeTowerLevel.mayFitForName(name: Name) =
            !definitelyDoesNotContainName(name) || !definitelyDoesNotContainName(OperatorNameConventions.INVOKE)

    private fun ResolutionScope.mayFitForName(name: Name, location: LookupLocation) =
            !definitelyDoesNotContainName(name) || !definitelyDoesNotContainName(OperatorNameConventions.INVOKE)

    fun <C> runWithEmptyTowerData(
            processor: ScopeTowerProcessor<C>,
            resultCollector: ResultCollector<C>,
            useOrder: Boolean
    ): Collection<C> = processTowerData(processor, resultCollector, useOrder, TowerData.Empty) ?: resultCollector.getFinalCandidates()

    private fun <C> processTowerData(
            processor: ScopeTowerProcessor<C>,
            resultCollector: ResultCollector<C>,
            useOrder: Boolean,
            towerData: TowerData
    ): Collection<C>? {
        ProgressIndicatorAndCompilationCanceledStatus.checkCanceled()

        val candidatesGroups = if (useOrder) {
            processor.process(towerData)
        }
        else {
            listOf(processor.process(towerData).flatMap { it })
        }

        for (candidatesGroup in candidatesGroups) {
            resultCollector.pushCandidates(candidatesGroup)
            resultCollector.getSuccessfulCandidates()?.let { return it }
        }

        return null
    }


    abstract class ResultCollector<C>(protected val getStatus: (C) -> ResolutionCandidateStatus) {
        abstract fun getSuccessfulCandidates(): Collection<C>?

        abstract fun getFinalCandidates(): Collection<C>

        fun pushCandidates(candidates: Collection<C>) {
            val filteredCandidates = candidates.filter {
                getStatus(it).resultingApplicability != ResolutionCandidateApplicability.HIDDEN
            }
            if (filteredCandidates.isNotEmpty()) addCandidates(filteredCandidates)
        }

        protected abstract fun addCandidates(candidates: Collection<C>)
    }

    class AllCandidatesCollector<C>(getStatus: (C) -> ResolutionCandidateStatus): ResultCollector<C>(getStatus) {
        private val allCandidates = ArrayList<C>()

        override fun getSuccessfulCandidates(): Collection<C>? = null

        override fun getFinalCandidates(): Collection<C> = allCandidates

        override fun addCandidates(candidates: Collection<C>) {
            allCandidates.addAll(candidates)
        }
    }

    class SuccessfulResultCollector<C>(getStatus: (C) -> ResolutionCandidateStatus): ResultCollector<C>(getStatus) {
        private var currentCandidates: Collection<C> = emptyList()
        private var currentLevel: ResolutionCandidateApplicability? = null

        override fun getSuccessfulCandidates(): Collection<C>? = getResolved()

        fun getResolved() = currentCandidates.takeIf { currentLevel == ResolutionCandidateApplicability.RESOLVED }

        fun getResolvedLowPriority() = currentCandidates.takeIf { currentLevel == ResolutionCandidateApplicability.RESOLVED_LOW_PRIORITY }

        fun getErrors() = currentCandidates.takeIf {
            currentLevel == null || currentLevel!! > ResolutionCandidateApplicability.RESOLVED_LOW_PRIORITY
        }

        override fun getFinalCandidates() = getResolved() ?: getResolvedLowPriority() ?: getErrors() ?: emptyList()

        override fun addCandidates(candidates: Collection<C>) {
            val minimalLevel = candidates.map { getStatus(it).resultingApplicability }.min()!!
            if (currentLevel == null || currentLevel!! > minimalLevel) {
                currentLevel = minimalLevel
                currentCandidates = candidates.filter { getStatus(it).resultingApplicability == minimalLevel }
            }
        }
    }
}
