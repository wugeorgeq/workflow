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
package com.squareup.workflow

import com.squareup.workflow.testing.testFromStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SnapshottingIntegrationTest {

  @Test fun `snapshots and restores single workflow`() {
    val root = TreeWorkflow("root")
    var snapshot: Snapshot? = null

    // Setup initial state and change the state the workflow in the tree.
    root.testFromStart("initial input") {
      awaitNextRendering()
          .let {
            assertEquals("root:initial input", it.data)
            it.setData("new data")
          }

      assertEquals("root:new data", awaitNextRendering().data)

      snapshot = awaitNextSnapshot()
    }

    root.testFromStart("unused input", snapshot!!) {
      assertEquals("root:new data", awaitNextRendering().data)
    }
  }

  @Test fun `snapshots and restores parent child workflows`() {
    val root = TreeWorkflow("root", TreeWorkflow("leaf"))
    var snapshot: Snapshot? = null

    // Setup initial state and change the state the workflow in the tree.
    root.testFromStart("initial input") {
      awaitNextRendering()
          .let {
            assertEquals("root:initial input", it.data)
            it["leaf"].setData("new leaf data")
          }
      awaitNextRendering()
          .setData("new root data")

      awaitNextRendering()
          .let {
            assertEquals("root:new root data", it.data)
            assertEquals("leaf:new leaf data", it["leaf"].data)
          }

      snapshot = awaitNextSnapshot()
    }

    root.testFromStart("unused input", snapshot!!) {
      awaitNextRendering()
          .let {
            assertEquals("root:new root data", it.data)
            assertEquals("leaf:new leaf data", it["leaf"].data)
          }
    }
  }

  @Test fun `snapshots and restores complex tree`() {
    val root = TreeWorkflow(
        "root",
        TreeWorkflow(
            "middle1",
            TreeWorkflow("leaf1"),
            TreeWorkflow("leaf2")
        ),
        TreeWorkflow(
            "middle2",
            TreeWorkflow("leaf3")
        )
    )
    var snapshot: Snapshot? = null

    // Setup initial state and change the state of two workflows in the tree.
    root.testFromStart("initial input") {
      awaitNextRendering()
          .let {
            assertEquals("root:initial input", it.data)
            assertEquals("middle1:initial input[0]", it["middle1"].data)
            assertEquals("middle2:initial input[1]", it["middle2"].data)
            assertEquals("leaf1:initial input[0][0]", it["middle1", "leaf1"].data)
            assertEquals("leaf2:initial input[0][1]", it["middle1", "leaf2"].data)
            assertEquals("leaf3:initial input[1][0]", it["middle2", "leaf3"].data)

            it["middle1", "leaf2"].setData("new leaf data")
          }
      awaitNextRendering()
          .setData("new root data")

      awaitNextRendering()
          .let {
            assertEquals("root:new root data", it.data)
            assertEquals("middle1:initial input[0]", it["middle1"].data)
            assertEquals("middle2:initial input[1]", it["middle2"].data)
            assertEquals("leaf1:initial input[0][0]", it["middle1", "leaf1"].data)
            assertEquals("leaf2:new leaf data", it["middle1", "leaf2"].data)
            assertEquals("leaf3:initial input[1][0]", it["middle2", "leaf3"].data)
          }

      snapshot = awaitNextSnapshot()
    }

    root.testFromStart("unused input", snapshot!!) {
      awaitNextRendering()
          .let {
            assertEquals("root:new root data", it.data)
            assertEquals("middle1:initial input[0]", it["middle1"].data)
            assertEquals("middle2:initial input[1]", it["middle2"].data)
            assertEquals("leaf1:initial input[0][0]", it["middle1", "leaf1"].data)
            assertEquals("leaf2:new leaf data", it["middle1", "leaf2"].data)
            assertEquals("leaf3:initial input[1][0]", it["middle2", "leaf3"].data)
          }
    }
  }

  // See https://github.com/square/workflow/issues/404
  @Test fun `descendant snapshots are independent over state transitions`() {
    val workflow = object : StatefulWorkflow<String, String, Nothing, Unit>() {
      override fun initialState(
        input: String,
        snapshot: Snapshot?
      ): String = input

      override fun onInputChanged(
        old: String,
        new: String,
        state: String
      ): String = new

      override fun render(
        input: String,
        state: String,
        context: RenderContext<String, Nothing>
      ) {
      }

      override fun snapshotState(state: String): Snapshot = Snapshot.write {
        it.writeUtf8WithLength(state)
      }
    }
    // This test specifically needs to test snapshots from a non-flat workflow tree.
    val root = Workflow.stateless<String, Nothing, Unit> {
      renderChild(workflow, it)
    }

    root.testFromStart("input1") {
      val snapshot1 = awaitNextSnapshot()

      // Change the input (and thus the state) to make a different snapshot.
      sendInput("input2")
      val snapshot2 = awaitNextSnapshot()

      // Send a new input to trigger a new render pass, but with the same snapshot.
      sendInput("input2")
      val snapshot3 = awaitNextSnapshot()

      assertNotEquals(snapshot1.bytes, snapshot2.bytes)
      assertEquals(snapshot2.bytes, snapshot3.bytes)
    }
  }
}