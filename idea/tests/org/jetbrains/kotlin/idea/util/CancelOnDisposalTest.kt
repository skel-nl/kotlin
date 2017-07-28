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
    @Volatile private var exception: Throwable? = null

    private val exceptionHandler = CoroutineExceptionHandler { context, e ->
        exception = e.takeIf { it is AssertionError }

        handleCoroutineException(context.minusKey(CoroutineExceptionHandler.Key), e)
    }

    fun testCancellation() {
        val project = createProject()

        val dispatcher = Executors.newFixedThreadPool(1).asCoroutineDispatcher()

        val job = launch(dispatcher + exceptionHandler + project.cancelOnDisposal) {
            Assert.assertTrue(!project.isDisposed)
            while (!project.isDisposed) {
                delay(50)
            }
            Assert.assertTrue(project.isDisposed)
            delay(100)
            // coroutine must be canceled at this point
            Assert.fail()
        }

        Assert.assertTrue(!project.isDisposed)
        disposeProject(project)

        runBlocking {
            job.join()
            delay(50)
        }

        checkNoExceptionsInBackground()
    }

    private fun checkNoExceptionsInBackground() {
        exception?.let { throw AssertionError("Background thread threw an exception.", it) }
    }

    private fun disposeProject(project: Project) {
        super.tearDown()
        Assert.assertTrue(project.isDisposed)
    }

    private fun createProject(): Project {
        super.setUp()
        val project = myFixture.project
        Assert.assertTrue(!project.isDisposed)
        return project
    }

    override fun setUp() {
        // do nothing
    }

    override fun tearDown() {
        // do nothing
    }
}