/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.q.handler

import com.google.common.util.concurrent.MoreExecutors
import com.netflix.spinnaker.orca.DefaultStageResolver
import com.netflix.spinnaker.orca.TaskResolver
import com.netflix.spinnaker.orca.api.pipeline.CancellableStage
import com.netflix.spinnaker.orca.api.pipeline.graph.StageDefinitionBuilder
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus.*
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionType.PIPELINE
import com.netflix.spinnaker.orca.api.test.pipeline
import com.netflix.spinnaker.orca.api.test.stage
import com.netflix.spinnaker.orca.pipeline.DefaultStageDefinitionBuilderFactory
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionRepository
import com.netflix.spinnaker.orca.pipeline.util.StageNavigator
import com.netflix.spinnaker.orca.q.CancelStage
import com.netflix.spinnaker.orca.q.StageDefinitionBuildersProvider
import com.netflix.spinnaker.orca.q.TasksProvider
import com.netflix.spinnaker.orca.q.singleTaskStage
import com.netflix.spinnaker.q.Queue
import com.nhaarman.mockito_kotlin.*
import org.jetbrains.spek.api.dsl.context
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.api.lifecycle.CachingMode.GROUP
import org.jetbrains.spek.subject.SubjectSpek

object CancelStageHandlerTest : SubjectSpek<CancelStageHandler>({
  val queue: Queue = mock()
  val repository: ExecutionRepository = mock()
  val executor = MoreExecutors.directExecutor()
  val taskResolver: TaskResolver = TaskResolver(TasksProvider(emptyList()))
  val stageNavigator: StageNavigator = mock()

  val cancellableStage: CancelableStageDefinitionBuilder = mock()
  whenever(cancellableStage.type) doReturn "cancellable"
  val stageResolver = DefaultStageResolver(StageDefinitionBuildersProvider(listOf(singleTaskStage, cancellableStage)))

  subject(GROUP) {
    CancelStageHandler(
      queue,
      repository,
      DefaultStageDefinitionBuilderFactory(stageResolver),
      stageNavigator,
      executor,
      taskResolver
    )
  }

  fun resetMocks() = reset(queue, repository, cancellableStage)

  describe("cancelling a stage") {
    val pipeline = pipeline {
      application = "whatever"
      stage {
        type = "cancellable"
        refId = "1"
        status = SUCCEEDED
      }
      stage {
        type = "cancellable"
        refId = "2a"
        requisiteStageRefIds = listOf("1")
        status = CANCELED
      }
      stage {
        type = singleTaskStage.type
        refId = "2b"
        requisiteStageRefIds = listOf("1")
        status = CANCELED
      }
      stage {
        type = "cancellable"
        refId = "2c"
        requisiteStageRefIds = listOf("1")
        status = TERMINAL
      }
      stage {
        type = "cancellable"
        refId = "3"
        requisiteStageRefIds = listOf("2a", "2b", "2c")
        status = NOT_STARTED
      }
    }

    mapOf(
      "2a" to "a cancellable stage that was canceled",
      "2c" to "a cancellable stage that failed"
    ).forEach { refId, description ->
      context(description) {
        val message = CancelStage(PIPELINE, pipeline.id, pipeline.application, pipeline.stageByRef(refId).id)

        beforeGroup {
          whenever(cancellableStage.type) doReturn "cancellable"
          whenever(repository.retrieve(PIPELINE, pipeline.id)) doReturn pipeline
        }

        afterGroup(::resetMocks)

        action("the handler receives a message") {
          subject.handle(message)
        }

        it("invokes the cancel routine for the stage") {
          verify(cancellableStage).cancel(pipeline.stageByRef(refId))
        }

        it("should not push any messages to the queue") {
          verifyNoMoreInteractions(queue)
        }
      }
    }

    mapOf(
      "1" to "a cancellable stage that completed already",
      "2b" to "a running non-cancellable stage",
      "3" to "a cancellable stage that did not start yet"
    ).forEach { refId, description ->
      context(description) {
        val message = CancelStage(PIPELINE, pipeline.id, pipeline.application, pipeline.stageByRef(refId).id)

        beforeGroup {
          whenever(cancellableStage.type) doReturn "cancellable"
          whenever(repository.retrieve(pipeline.type, pipeline.id)) doReturn pipeline
        }

        afterGroup(::resetMocks)

        action("the handler receives a message") {
          subject.handle(message)
        }

        it("does not run any cancel routine") {
          verify(cancellableStage, never()).cancel(any())
        }

        it("should not push any messages to the queue") {
          verifyNoMoreInteractions(queue)
        }
      }
    }
  }
})

interface CancelableStageDefinitionBuilder : StageDefinitionBuilder, CancellableStage
