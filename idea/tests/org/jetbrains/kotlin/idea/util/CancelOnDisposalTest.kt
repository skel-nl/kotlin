/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

package org.jetbrains.kotlin.idea.util

import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase
import kotlinx.coroutines.experimental.*
import org.jetbrains.kotlin.idea.core.util.cancelOnDisposal
import org.junit.Assert
import java.util.concurrent.Executors

class CancelOnDisposalTest : LightCodeInsightFixtureTestCase() {
    lateinit var latch: Deferred<*>
    lateinit var proj: Project
    lateinit var jobs: List<Job>
    @Volatile
    var exception: Throwable? = null
    private val dispatcher = Executors.newFixedThreadPool(1).asCoroutineDispatcher()

    private val exceptionHandler = CoroutineExceptionHandler { context, e ->
        exception = e.takeIf { it is AssertionError }

        handleCoroutineException(context.minusKey(CoroutineExceptionHandler.Key), e)
    }

    fun testCancellation() {
        Assert.assertTrue(!proj.isDisposed)
        jobs = (1..10).map {
            launch(dispatcher + exceptionHandler + proj.cancelOnDisposal) {
                Assert.assertTrue(!proj.isDisposed)
                // TODO: while ! tornDown
                latch.await()
                Assert.assertTrue(proj.isDisposed)
                delay(300)
                // TODO: comment
                Assert.fail()
            }
        }
    }

    override fun setUp() {
        super.setUp()
        proj = project
        Assert.assertTrue(!proj.isDisposed)
        latch = async(dispatcher) {
            delay(200)
        }
    }

    override fun tearDown() {
        Assert.assertTrue(!proj.isDisposed)
        super.tearDown()
        Assert.assertTrue(proj.isDisposed)
        runBlocking {
            jobs.forEach {
                it.join()
            }
        }
        exception?.let { throw AssertionError("Background thread threw an exception.", it) }
    }
}