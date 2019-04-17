/*
 * Copyright 2019 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("EXPERIMENTAL_API_USAGE", "DEPRECATION")

package com.squareup.workflow.internal

import com.squareup.workflow.Snapshot
import com.squareup.workflow.StatefulWorkflow
import com.squareup.workflow.Workflow
import com.squareup.workflow.WorkflowAction.Companion.emitOutput
import com.squareup.workflow.WorkflowAction.Companion.noop
import com.squareup.workflow.WorkflowContext
import com.squareup.workflow.internal.Behavior.WorkflowOutputCase
import com.squareup.workflow.internal.RealWorkflowContext.Renderer
import com.squareup.workflow.internal.RealWorkflowContextTest.TestRenderer.Rendering
import com.squareup.workflow.renderChild
import com.squareup.workflow.stateless
import kotlinx.coroutines.CoroutineScope
import kotlin.reflect.full.starProjectedType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class RealWorkflowContextTest {

  private class TestRenderer : Renderer<String, String> {

    data class Rendering(
      val case: WorkflowOutputCase<*, *, *, *>,
      val child: Workflow<*, *, *>,
      val id: WorkflowId<*, *, *>,
      val input: Any
    )

    @Suppress("UNCHECKED_CAST")
    override fun <IC : Any, OC : Any, RC : Any> render(
      case: WorkflowOutputCase<IC, OC, String, String>,
      child: Workflow<IC, OC, RC>,
      id: WorkflowId<IC, OC, RC>,
      input: IC
    ): RC {
      return Rendering(case, child, id, input) as RC
    }
  }

  private class TestWorkflow : StatefulWorkflow<String, String, String, Rendering>() {
    override fun initialState(
      input: String,
      snapshot: Snapshot?,
      scope: CoroutineScope
    ): String = fail()

    override fun render(
      input: String,
      state: String,
      context: WorkflowContext<String, String>
    ): Rendering {
      fail("This shouldn't actually be called.")
    }

    override fun snapshotState(state: String): Snapshot = fail()
  }

  private class PoisonRenderer<S : Any, O : Any> : Renderer<S, O> {
    override fun <IC : Any, OC : Any, RC : Any> render(
      case: WorkflowOutputCase<IC, OC, S, O>,
      child: Workflow<IC, OC, RC>,
      id: WorkflowId<IC, OC, RC>,
      input: IC
    ): RC = fail()
  }

  @Test fun `make sink completes update`() {
    val context = RealWorkflowContext<String, String>(PoisonRenderer())
    val expectedUpdate = noop<String, String>()
    val handler = context.onEvent<String> { expectedUpdate }
    val behavior = context.buildBehavior()
    assertFalse(behavior.nextActionFromEvent.isCompleted)

    handler("")

    assertTrue(behavior.nextActionFromEvent.isCompleted)
    val actualUpdate = behavior.nextActionFromEvent.getCompleted()
    assertSame(expectedUpdate, actualUpdate)
  }

  @Test fun `make sink gets event`() {
    val context = RealWorkflowContext<String, String>(PoisonRenderer())
    val handler = context.onEvent<String> { event -> emitOutput(event) }

    handler("foo")

    val behavior = context.buildBehavior()
    val update = behavior.nextActionFromEvent.getCompleted()
    val (state, output) = update("state")
    assertEquals("state", state)
    assertEquals("foo", output)
  }

  @Test fun `composeChild works`() {
    val context = RealWorkflowContext(TestRenderer())
    val workflow = TestWorkflow()

    val (case, child, id, input) = context.renderChild(workflow, "input", "key") { output ->
      emitOutput("output:$output")
    }

    assertSame(workflow, child)
    assertEquals(workflow.id("key"), id)
    assertEquals("input", input)
    assertEquals<Workflow<*, *, *>>(workflow, case.workflow)
    assertEquals(workflow.id("key"), case.id)
    assertEquals("input", case.input)

    @Suppress("UNCHECKED_CAST")
    case as WorkflowOutputCase<String, String, String, String>
    val (state, output) = case.handler.invoke("output").invoke("state")
    assertEquals("state", state)
    assertEquals("output:output", output)

    val childCases = context.buildBehavior()
        .childCases
    assertEquals(1, childCases.size)
    assertSame(case, childCases.single())
  }

  @Test fun `all methods throw after buildBehavior`() {
    val context = RealWorkflowContext(TestRenderer())
    context.buildBehavior()

    assertFailsWith<IllegalStateException> { context.onEvent<Unit> { fail() } }
    assertFailsWith<IllegalStateException> { context.onTeardown { fail() } }
    assertFailsWith<IllegalStateException> {
      context.onReceive<Unit>({ fail() }, Unit::class.starProjectedType) { fail() }
    }
    val child = Workflow.stateless<Nothing, Unit> { fail() }
    assertFailsWith<IllegalStateException> { context.renderChild(child) }
    assertFailsWith<IllegalStateException> { context.buildBehavior() }
  }
}