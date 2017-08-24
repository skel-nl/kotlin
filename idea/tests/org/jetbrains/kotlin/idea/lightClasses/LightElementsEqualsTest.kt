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

package org.jetbrains.kotlin.idea.lightClasses

import com.intellij.psi.*
import com.intellij.testFramework.LightProjectDescriptor
import junit.framework.TestCase
import org.jetbrains.kotlin.asJava.LightClassUtil
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.toLightElements
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCase
import org.jetbrains.kotlin.idea.test.KotlinWithJdkAndRuntimeLightProjectDescriptor
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFunction
import java.lang.System.identityHashCode as idh


class LightElementsEqualsTest : KotlinLightCodeInsightFixtureTestCase() {


    fun testParametersEquals() {

        val psiFile = myFixture.configureByText("a.kt", """
                class A(i: Int) {
                    val b: String

                    @Synchronized
                    fun foo(param: String){}
                }
            """.trimIndent())

//        println("elementAtCaret = " + myFixture.elementAtCaret.javaClass)

        psiFile.accept(object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                println("visiting: ${element}: ${element.javaClass} ${(element as? KtElement)?.toLightElements()}")
                if (element is KtElement) {
                    val lightElements1 = element.toLightElements()
                    val lightElements2 = element.toLightElements()
                    TestCase.assertEquals("LightElements from ${element.text} should be equal if they retrieved twice", lightElements1, lightElements2)
                    for ((e1, e2) in lightElements1.zip(lightElements2)) {
                        TestCase.assertEquals("LightElements '$e1'(${e1.javaClass}) and '$e2'(${e2.javaClass}) from `${element.text}` should have equal hashcode as long as they are equal", e1.hashCode(), e2.hashCode())
                    }

                }
                element.acceptChildren(this)
            }
        })

    }

    fun testParametersEquals2() {

        val psiFile = myFixture.configureByText("a.kt", """
                class A(i: Int) {
                    val b: String

                    @Synchronized
                    fun foo(param: String){}
                }
            """.trimIndent())

        val theClass1 = myFixture.javaFacade.findClass("A")
//        theClass1.accept(object: PsiElementVisitor() {
//            override fun visitElement(element: PsiElement) {
//                println("visiting: $element")
//                element.acceptChildren(this)
//            }
//        })
//        val theClass2 = myFixture.javaFacade.findClass("A")
        val theClass2 = myFixture.javaFacade.findClass("A")


        val theClass1InnerMethods = theClass1.methods.asSequence().flatMap { elementsSeq(it) }
        val theClass2InnerMethods = (theClass1 as KtLightClass).kotlinOrigin!!.declarations.asSequence().flatMap { it.toLightMethods().asSequence() }.flatMap { elementsSeq(it) }
        for ((e1, e2) in theClass1InnerMethods.zip(theClass2InnerMethods)) {
            println("comparing $e1 $e2")
            TestCase.assertEquals(e1, e2)
        }
    }

    fun testParametersEquals3() {

        myFixture.configureByText("a.kt", """
                class A(i: Int) {
                    val b: String

                    @Suppress
                    @Synchronized
                    fun fo<caret>o(param: String){}
                }
            """.trimIndent())

        val ktFunction = myFixture.elementAtCaret as KtFunction
        val parameters1 = LightClassUtil.getLightClassMethod(ktFunction)!!.parameterList.parameters.toList().single()
        val parameters2 = LightClassUtil.getLightClassMethod(ktFunction)!!.parameterList.parameters.toList().single()
        TestCase.assertEquals(parameters1, parameters2)
        TestCase.assertEquals(parameters1.hashCode(), parameters2.hashCode())

//        val parameters1 = (myFixture.elementAtCaret as KtFunction).toLightMethods().single().parameterList.parameters.toList()
//        val javaParams = myFixture.javaFacade.findClass("A").findMethodsByName("foo", false).single().parameterList.parameters.toList()
//        TestCase.assertEquals(parameters1, javaParams)
//        val ktParameter = myFixture.elementAtCaret as KtParameter
//        val lightElements = ktParameter.toLightElements()
//        val javaParams = myFixture.javaFacade.findClass("A").findMethodsByName("foo", false).single().parameterList.parameters.toList()
//        TestCase.assertEquals(ktParameter.toLightElements(), javaParams)

//        val params1 = AnnotatedMembersSearch.search(myFixture.javaFacade.findClass("kotlin.Suppress")).findFirst()!!.let {
//            (it as PsiMethod).parameterList.parameters.toList()
//        }
//        val params2 = AnnotatedMembersSearch.search(myFixture.javaFacade.findClass("kotlin.jvm.Synchronized")).findFirst()!!.let {
//            (it as PsiMethod).parameterList.parameters.toList()
//        }
//        TestCase.assertEquals(params1, params2)


    }


    override fun getProjectDescriptor(): LightProjectDescriptor {
        return KotlinWithJdkAndRuntimeLightProjectDescriptor.INSTANCE
    }

    fun elementsSeq(psiElement: PsiElement): Sequence<PsiElement> {
        return when (psiElement) {
            is PsiClass -> sequenceOf(psiElement, psiElement.modifierList).filterNotNull() +
                           psiElement.methods.asSequence().flatMap { elementsSeq(it) } +
                           psiElement.fields.asSequence().flatMap { elementsSeq(it) }
            is PsiMethod -> sequenceOf(psiElement, psiElement.parameterList) + elementsSeq(psiElement.parameterList)
            is PsiParameterList -> sequenceOf(psiElement) +
                                   psiElement.parameters.asSequence().flatMap { elementsSeq(it) }
            is PsiModifierList -> sequenceOf(psiElement) +
                                  psiElement.annotations.asSequence().flatMap { elementsSeq(it) }
            is PsiAnnotation -> sequenceOf(psiElement, psiElement.parameterList) + elementsSeq(psiElement.parameterList)

            else -> sequenceOf(psiElement)
        }
    }

}