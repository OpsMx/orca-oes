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

import com.netflix.spinnaker.orca.api.pipeline.SyntheticStageOwner.STAGE_BEFORE
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus.RUNNING
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus.SUCCEEDED
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus.TERMINAL
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionType.PIPELINE
import com.netflix.spinnaker.orca.api.test.pipeline
import com.netflix.spinnaker.orca.api.test.stage
import com.netflix.spinnaker.orca.events.StageComplete
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionRepository
import com.netflix.spinnaker.orca.q.AbortStage
import com.netflix.spinnaker.orca.q.CancelStage
import com.netflix.spinnaker.orca.q.CompleteExecution
import com.netflix.spinnaker.orca.q.CompleteStage
import com.netflix.spinnaker.q.Queue
import com.netflix.spinnaker.time.fixedClock
import com.nhaarman.mockito_kotlin.*
import com.nhaarman.mockito_kotlin.verifyZeroInteractions
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.given
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.api.dsl.on
import org.jetbrains.spek.api.lifecycle.CachingMode.GROUP
import org.jetbrains.spek.subject.SubjectSpek
import org.springframework.context.ApplicationEventPublisher

object AbortStageHandlerTest : SubjectSpek<AbortStageHandler>({

  val queue: Queue = mock()
  val repository: ExecutionRepository = mock()
  val publisher: ApplicationEventPublisher = mock()
  val clock = fixedClock()

  subject(GROUP) {
    AbortStageHandler(queue, repository, publisher, clock)
  }

  fun resetMocks() {
    reset(queue, repository, publisher)
  }

  describe("aborting a stage") {
    given("a stage that already completed") {
      val pipeline = pipeline {
        application = "whatever"
        stage {
          refId = "1"
          type = "whatever"
          status = SUCCEEDED
        }
      }

      val message = AbortStage(pipeline.stageByRef("1"))

      beforeGroup {
        whenever(repository.retrieve(PIPELINE, pipeline.id)) doReturn pipeline
      }

      afterGroup(::resetMocks)

      on("receiving a message") {
        subject.handle(message)
      }

      it("does nothing at all") {
        verifyNoMoreInteractions(queue)
        verifyNoMoreInteractions(publisher)
        verify(repository, never()).storeStage(any())
      }
    }

    given("a top level stage") {
      val pipeline = pipeline {
        application = "whatever"
        stage {
          refId = "1"
          type = "whatever"
          status = RUNNING
        }
      }

      val message = AbortStage(pipeline.stageByRef("1"))

      beforeGroup {
        whenever(repository.retrieve(PIPELINE, pipeline.id)) doReturn pipeline
      }

      afterGroup(::resetMocks)

      on("receiving a message") {
        subject.handle(message)
      }

      it("marks the stage as TERMINAL") {
        verify(repository).storeStage(
          check {
            assertThat(it.status).isEqualTo(TERMINAL)
            assertThat(it.endTime).isEqualTo(clock.instant().toEpochMilli())
          }
        )
      }

      it("cancels the stage") {
        verify(queue).push(CancelStage(message))
      }

      it("completes the execution") {
        verify(queue).push(CompleteExecution(message))
      }

      it("emits an event") {
        verify(publisher).publishEvent(
          check<StageComplete> {
            assertThat(it.stage.status).isEqualTo(TERMINAL)
          }
        )
      }
    }

    given("a synthetic level stage") {
      val pipeline = pipeline {
        application = "whatever"
        stage {
          refId = "1"
          type = "whatever"
          status = RUNNING
          stage {
            refId = "1<1"
            type = "whatever"
            status = RUNNING
            syntheticStageOwner = STAGE_BEFORE
          }
        }
      }

      val message = AbortStage(pipeline.stageByRef("1<1"))

      beforeGroup {
        whenever(repository.retrieve(PIPELINE, pipeline.id)) doReturn pipeline
      }

      afterGroup(::resetMocks)

      on("receiving a message") {
        subject.handle(message)
      }

      it("marks the stage as TERMINAL") {
        verify(repository).storeStage(
          check {
            assertThat(it.status).isEqualTo(TERMINAL)
            assertThat(it.endTime).isEqualTo(clock.instant().toEpochMilli())
          }
        )
      }

      it("cancels the stage") {
        verify(queue).push(CancelStage(message))
      }

      it("completes the parent stage") {
        verify(queue).push(CompleteStage(pipeline.stageByRef("1")))
      }

      it("emits an event") {
        verify(publisher).publishEvent(
          check<StageComplete> {
            assertThat(it.stage.status).isEqualTo(TERMINAL)
          }
        )
      }
    }
  }
})
