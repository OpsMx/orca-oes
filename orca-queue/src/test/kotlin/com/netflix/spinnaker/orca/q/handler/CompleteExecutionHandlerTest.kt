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

import com.netflix.spectator.api.NoopRegistry
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus.*
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionType.PIPELINE
import com.netflix.spinnaker.orca.api.test.pipeline
import com.netflix.spinnaker.orca.api.test.stage
import com.netflix.spinnaker.orca.events.ExecutionComplete
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionRepository
import com.netflix.spinnaker.orca.q.CancelStage
import com.netflix.spinnaker.orca.q.CompleteExecution
import com.netflix.spinnaker.orca.q.StartWaitingExecutions
import com.netflix.spinnaker.q.Queue
import com.nhaarman.mockito_kotlin.*
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.api.dsl.on
import org.jetbrains.spek.api.lifecycle.CachingMode.GROUP
import org.jetbrains.spek.subject.SubjectSpek
import org.springframework.context.ApplicationEventPublisher
import java.time.Duration
import java.util.*
import kotlin.collections.set


object CompleteExecutionHandlerTest : SubjectSpek<CompleteExecutionHandler>({

  val queue: Queue = mock()
  val repository: ExecutionRepository = mock()
  val publisher: ApplicationEventPublisher = mock()
  val registry = NoopRegistry()
  val retryDelay = Duration.ofSeconds(5)

  subject(GROUP) {
    CompleteExecutionHandler(queue, repository, publisher, registry, retryDelayMs = retryDelay.toMillis())
  }

  fun resetMocks() = reset(queue, repository, publisher)

  setOf(SUCCEEDED, TERMINAL, CANCELED).forEach { stageStatus ->
    describe("when an execution completes and has a single stage with $stageStatus status") {
      val pipeline = pipeline {
        stage {
          refId = "1"
          status = stageStatus
        }
      }
      val message = CompleteExecution(pipeline)

      beforeGroup {
        whenever(repository.retrieve(PIPELINE, message.executionId)) doReturn pipeline
      }

      afterGroup(::resetMocks)

      on("receiving $message") {
        subject.handle(message)
      }

      it("updates the execution") {
        assertThat(pipeline.status).isEqualTo(stageStatus)
        verify(repository).updateStatus(pipeline)
      }

      it("publishes an event") {
        verify(publisher).publishEvent(
          check<ExecutionComplete> {
            assertThat(it.executionType).isEqualTo(pipeline.type)
            assertThat(it.executionId).isEqualTo(pipeline.id)
            assertThat(it.status).isEqualTo(stageStatus)
            assertThat(it.execution.endTime).isNotNull()
          }
        )
      }

      it("does not queue any other commands") {
        verifyNoMoreInteractions(queue)
      }
    }

    describe("when an execution with a pipelineConfigId completes with $stageStatus") {
      val configId = UUID.randomUUID().toString()
      val pipeline = pipeline {
        pipelineConfigId = configId
        stage {
          refId = "1"
          status = stageStatus
        }
      }
      val message = CompleteExecution(pipeline)

      beforeGroup {
        whenever(repository.retrieve(PIPELINE, message.executionId)) doReturn pipeline
      }

      afterGroup(::resetMocks)

      on("receiving $message") {
        subject.handle(message)
      }

      it("triggers any waiting pipelines") {
        verify(queue).push(StartWaitingExecutions(configId, !pipeline.isKeepWaitingPipelines))
      }

      it("does not queue any other commands") {
        verifyNoMoreInteractions(queue)
      }
    }
  }

  setOf(SUCCEEDED, STOPPED, FAILED_CONTINUE, SKIPPED).forEach { stageStatus ->
    describe("an execution appears to complete with one branch $stageStatus but other branches are still running") {
      val pipeline = pipeline {
        stage {
          refId = "1"
          status = stageStatus
        }
        stage {
          refId = "2"
          status = RUNNING
        }
      }
      val message = CompleteExecution(pipeline)

      beforeGroup {
        whenever(repository.retrieve(PIPELINE, message.executionId)) doReturn pipeline
      }

      afterGroup(::resetMocks)

      on("receiving $message") {
        subject.handle(message)
      }

      it("waits for the other branch(es)") {
        verify(repository, never()).updateStatus(eq(PIPELINE), eq(pipeline.id), any())
        verify(repository, never()).updateStatus(any())
      }

      it("does not publish any events") {
        verifyNoMoreInteractions(publisher)
      }

      it("re-queues the message for later evaluation") {
        verify(queue).push(message, retryDelay)
        verifyNoMoreInteractions(queue)
      }
    }
  }

  setOf(TERMINAL, CANCELED).forEach { stageStatus ->
    describe("a stage signals branch completion with $stageStatus but other branches are still running") {
      val pipeline = pipeline {
        stage {
          refId = "1"
          status = stageStatus
        }
        stage {
          refId = "2"
          status = RUNNING
        }
        stage {
          refId = "3"
          status = RUNNING
        }
      }
      val message = CompleteExecution(pipeline)

      beforeGroup {
        whenever(repository.retrieve(PIPELINE, message.executionId)) doReturn pipeline
      }

      afterGroup(::resetMocks)

      on("receiving $message") {
        subject.handle(message)
      }

      it("updates the pipeline status") {
        assertThat(pipeline.status).isEqualTo(stageStatus)
        verify(repository).updateStatus(pipeline)
      }

      it("publishes an event") {
        verify(publisher).publishEvent(
          check<ExecutionComplete> {
            assertThat(it.executionType).isEqualTo(pipeline.type)
            assertThat(it.executionId).isEqualTo(pipeline.id)
            assertThat(it.status).isEqualTo(stageStatus)
          }
        )
      }

      it("cancels other stages") {
        verify(queue).push(CancelStage(pipeline.stageByRef("2")))
        verify(queue).push(CancelStage(pipeline.stageByRef("3")))
        verifyNoMoreInteractions(queue)
      }
    }
  }

  describe("when a stage status was STOPPED but should fail the pipeline at the end") {
    val pipeline = pipeline {
      stage {
        refId = "1a"
        status = STOPPED
        context["completeOtherBranchesThenFail"] = true
      }
      stage {
        refId = "1b"
        requisiteStageRefIds = setOf("1a")
        status = NOT_STARTED
      }
      stage {
        refId = "2"
        status = SUCCEEDED
      }
    }
    val message = CompleteExecution(pipeline)

    beforeGroup {
      whenever(repository.retrieve(PIPELINE, message.executionId)) doReturn pipeline
    }

    afterGroup(::resetMocks)

    on("receiving $message") {
      subject.handle(message)
    }

    it("updates the execution") {
      assertThat(pipeline.status).isEqualTo(TERMINAL)
      verify(repository).updateStatus(pipeline)
    }

    it("publishes an event") {
      verify(publisher).publishEvent(
        check<ExecutionComplete> {
          assertThat(it.executionType).isEqualTo(pipeline.type)
          assertThat(it.executionId).isEqualTo(pipeline.id)
          assertThat(it.status).isEqualTo(TERMINAL)
        }
      )
    }

    it("does not queue any other commands") {
      verifyNoMoreInteractions(queue)
    }
  }

  describe("when a stage status was STOPPED and should not fail the pipeline at the end") {
    val pipeline = pipeline {
      stage {
        refId = "1a"
        status = STOPPED
        context["completeOtherBranchesThenFail"] = false
      }
      stage {
        refId = "1b"
        requisiteStageRefIds = setOf("1a")
        status = NOT_STARTED
      }
      stage {
        refId = "2"
        status = SUCCEEDED
      }
    }
    val message = CompleteExecution(pipeline)

    beforeGroup {
      whenever(repository.retrieve(PIPELINE, message.executionId)) doReturn pipeline
    }

    afterGroup(::resetMocks)

    on("receiving $message") {
      subject.handle(message)
    }

    it("updates the execution") {
      assertThat(pipeline.status).isEqualTo(SUCCEEDED)
      verify(repository).updateStatus(pipeline)
    }

    it("publishes an event") {
      verify(publisher).publishEvent(
        check<ExecutionComplete> {
          assertThat(it.executionType).isEqualTo(pipeline.type)
          assertThat(it.executionId).isEqualTo(pipeline.id)
          assertThat(it.status).isEqualTo(SUCCEEDED)
        }
      )
    }

    it("does not queue any other commands") {
      verifyNoMoreInteractions(queue)
    }
  }

  describe("when a branch is stopped and nothing downstream has started yet") {
    val pipeline = pipeline {
      stage {
        refId = "1a"
        status = STOPPED
        context["completeOtherBranchesThenFail"] = false
      }
      stage {
        refId = "2a"
        status = SUCCEEDED
        context["completeOtherBranchesThenFail"] = false
      }
      stage {
        refId = "1b"
        requisiteStageRefIds = setOf("1a")
        status = NOT_STARTED
      }
      stage {
        refId = "2b"
        requisiteStageRefIds = setOf("2a")
        status = NOT_STARTED
      }
    }
    val message = CompleteExecution(pipeline)

    beforeGroup {
      whenever(repository.retrieve(PIPELINE, message.executionId)) doReturn pipeline
    }

    afterGroup(::resetMocks)

    on("receiving $message") {
      subject.handle(message)
    }

    it("does not complete the execution") {
      verify(repository, never()).updateStatus(eq(PIPELINE), any(), any())
      verify(repository, never()).updateStatus(any())
    }

    it("publishes no events") {
      verifyNoMoreInteractions(publisher)
    }

    it("re-queues the message") {
      verify(queue).push(message, retryDelay)
    }
  }

  describe("when a pipeline has branches and running with waiting executions") {
    val configId = UUID.randomUUID().toString()
    val runningPipeline = pipeline {
      pipelineConfigId = configId
      isLimitConcurrent = true
      status = RUNNING
      stage {
        refId = "11"
        status = RUNNING
      }
      stage {
        refId = "12"
        status = RUNNING
      }
    }
    val waitingPipeline = pipeline {
      pipelineConfigId = configId
      isLimitConcurrent = true
      status = NOT_STARTED
      stage {
        refId = "21"
        status = NOT_STARTED
      }
      stage {
        refId = "22"
        status = NOT_STARTED
      }
    }

    val message1 = CompleteExecution(runningPipeline)
    val message2 = CompleteExecution(waitingPipeline)

    beforeGroup {
      whenever(repository.retrieve(PIPELINE, message1.executionId)) doReturn runningPipeline
      whenever(repository.retrieve(PIPELINE, message2.executionId)) doReturn waitingPipeline
    }

    afterGroup(::resetMocks)

    on("receiving message") {
      subject.handle(message1)
      subject.handle(message2)
    }

    it("triggers only waiting Pipeline, but not the running Pipeline") {
      verify(queue).push(message1, retryDelay)
      verify(queue).push(message2, retryDelay)
      verify(queue, times(1)).push(StartWaitingExecutions(configId, !waitingPipeline.isKeepWaitingPipelines))
    }
  }

  describe("when pipeline has branches and all executions are RUNNING ") {
    val configId = UUID.randomUUID().toString()
    val runningPipeline1 = pipeline {
      pipelineConfigId = configId
      isLimitConcurrent = true
      status = RUNNING
      stage {
        refId = "11"
        status = RUNNING
      }
      stage {
        refId = "12"
        status = RUNNING
      }
    }
    val runningPipeline2 = pipeline {
      pipelineConfigId = configId
      isLimitConcurrent = true
      status = RUNNING
      stage {
        refId = "21"
        status = RUNNING
      }
      stage {
        refId = "22"
        status = RUNNING
      }
    }

    val message1 = CompleteExecution(runningPipeline1)
    val message2 = CompleteExecution(runningPipeline2)

    beforeGroup {
      whenever(repository.retrieve(PIPELINE, message1.executionId)) doReturn runningPipeline1
      whenever(repository.retrieve(PIPELINE, message2.executionId)) doReturn runningPipeline2
    }

    afterGroup(::resetMocks)

    on("receiving message") {
      subject.handle(message1)
      subject.handle(message2)
    }

    it("never triggers RUNNING Pipeline") {
      verify(queue).push(message1, retryDelay)
      verify(queue).push(message2, retryDelay)
      verify(queue, never()).push(StartWaitingExecutions(configId, !runningPipeline1.isKeepWaitingPipelines))
      verify(queue, never()).push(StartWaitingExecutions(configId, !runningPipeline2.isKeepWaitingPipelines))
    }
  }

})
