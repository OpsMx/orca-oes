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
import com.netflix.spinnaker.orca.DefaultStageResolver
import com.netflix.spinnaker.orca.NoOpTaskImplementationResolver
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus.*
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionType.PIPELINE
import com.netflix.spinnaker.orca.api.pipeline.models.TaskExecution
import com.netflix.spinnaker.orca.api.test.pipeline
import com.netflix.spinnaker.orca.api.test.stage
import com.netflix.spinnaker.orca.events.TaskComplete
import com.netflix.spinnaker.orca.pipeline.DefaultStageDefinitionBuilderFactory
import com.netflix.spinnaker.orca.pipeline.model.TaskExecutionImpl
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionRepository
import com.netflix.spinnaker.orca.pipeline.util.ContextParameterProcessor
import com.netflix.spinnaker.orca.q.*
import com.netflix.spinnaker.q.Queue
import com.netflix.spinnaker.spek.and
import com.netflix.spinnaker.time.fixedClock
import com.nhaarman.mockito_kotlin.*
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.given
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.api.lifecycle.CachingMode.GROUP
import org.jetbrains.spek.subject.SubjectSpek
import org.springframework.context.ApplicationEventPublisher

object CompleteTaskHandlerTest : SubjectSpek<CompleteTaskHandler>({

  val queue: Queue = mock()
  val repository: ExecutionRepository = mock()
  val publisher: ApplicationEventPublisher = mock()
  val clock = fixedClock()
  val stageResolver = DefaultStageResolver(StageDefinitionBuildersProvider(emptyList()))

  subject(GROUP) {
    CompleteTaskHandler(queue, repository, DefaultStageDefinitionBuilderFactory(stageResolver), ContextParameterProcessor(), publisher, clock, NoopRegistry())
  }

  fun resetMocks() = reset(queue, repository, publisher)

  setOf(SUCCEEDED, SKIPPED).forEach { successfulStatus ->
    describe("when a task completes with $successfulStatus status") {
      given("the stage contains further tasks") {
        val pipeline = pipeline {
          stage {
            type = multiTaskStage.type
            multiTaskStage.buildTasks(this, NoOpTaskImplementationResolver())
          }
        }
        val message = CompleteTask(
          pipeline.type,
          pipeline.id,
          pipeline.application,
          pipeline.stages.first().id,
          "1",
          successfulStatus,
          successfulStatus
        )

        beforeGroup {
          whenever(repository.retrieve(PIPELINE, message.executionId)) doReturn pipeline
        }

        afterGroup(::resetMocks)

        action("the handler receives a message") {
          subject.handle(message)
        }

        it("updates the task state in the stage") {
          verify(repository).storeStage(
            check {
              it.tasks.first().apply {
                assertThat(status).isEqualTo(successfulStatus)
                assertThat(endTime).isEqualTo(clock.millis())
              }
            }
          )
        }

        it("runs the next task") {
          verify(queue)
            .push(
              StartTask(
                message.executionType,
                message.executionId,
                message.application,
                message.stageId,
                "2"
              )
            )
        }

        it("publishes an event") {
          verify(publisher).publishEvent(
            check<TaskComplete> {
              assertThat(it.executionType).isEqualTo(pipeline.type)
              assertThat(it.executionId).isEqualTo(pipeline.id)
              assertThat(it.stage.id).isEqualTo(message.stageId)
              assertThat(it.task.id).isEqualTo(message.taskId)
              assertThat(it.task.status).isEqualTo(successfulStatus)
            }
          )
        }
      }

      given("the stage is complete") {
        val pipeline = pipeline {
          stage {
            type = singleTaskStage.type
            singleTaskStage.buildTasks(this, NoOpTaskImplementationResolver())
          }
        }
        val message = CompleteTask(
          pipeline.type,
          pipeline.id,
          pipeline.application,
          pipeline.stages.first().id,
          "1",
          SUCCEEDED,
          SUCCEEDED
        )

        beforeGroup {
          whenever(repository.retrieve(PIPELINE, message.executionId)) doReturn pipeline
        }

        afterGroup(::resetMocks)

        action("the handler receives a message") {
          subject.handle(message)
        }

        it("updates the task state in the stage") {
          verify(repository).storeStage(
            check {
              it.tasks.last().apply {
                assertThat(status).isEqualTo(SUCCEEDED)
                assertThat(endTime).isEqualTo(clock.millis())
              }
            }
          )
        }

        it("emits an event to signal the stage is complete") {
          verify(queue)
            .push(
              CompleteStage(
                message.executionType,
                message.executionId,
                message.application,
                message.stageId
              )
            )
        }
      }

      given("the task is the end of a rolling push loop") {
        val pipeline = pipeline {
          stage {
            refId = "1"
            type = rollingPushStage.type
            rollingPushStage.buildTasks(this, NoOpTaskImplementationResolver())
          }
        }

        and("when the task returns REDIRECT") {
          val message = CompleteTask(
            pipeline.type,
            pipeline.id,
            pipeline.application,
            pipeline.stageByRef("1").id,
            "4",
            REDIRECT,
            REDIRECT
          )

          beforeGroup {
            pipeline.stageByRef("1").apply {
              tasks[0].status = SUCCEEDED
              tasks[1].status = SUCCEEDED
              tasks[2].status = SUCCEEDED
            }

            whenever(repository.retrieve(PIPELINE, pipeline.id)) doReturn pipeline
          }

          afterGroup(::resetMocks)

          action("the handler receives a message") {
            subject.handle(message)
          }

          it("repeats the loop") {
            verify(queue).push(
              check<StartTask> {
                assertThat(it.taskId).isEqualTo("2")
              }
            )
          }

          it("resets the status of the loop tasks") {
            verify(repository).storeStage(
              check {
                assertThat(it.tasks[1..3].map(TaskExecution::getStatus)).allMatch { it == NOT_STARTED }
              }
            )
          }

          it("does not publish an event") {
            verifyNoMoreInteractions(publisher)
          }
        }
      }
    }
  }

  setOf(TERMINAL, CANCELED, STOPPED).forEach { status ->
    describe("when a task completes with $status status") {
      val pipeline = pipeline {
        stage {
          type = multiTaskStage.type
          multiTaskStage.buildTasks(this, NoOpTaskImplementationResolver())
        }
      }
      val message = CompleteTask(
        pipeline.type,
        pipeline.id,
        pipeline.application,
        pipeline.stages.first().id,
        "1",
        status,
        status
      )

      beforeGroup {
        whenever(repository.retrieve(PIPELINE, message.executionId)) doReturn pipeline
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        subject.handle(message)
      }

      it("updates the task state in the stage") {
        verify(repository).storeStage(
          check {
            it.tasks.first().apply {
              assertThat(status).isEqualTo(status)
              assertThat(endTime).isEqualTo(clock.millis())
            }
          }
        )
      }

      it("fails the stage") {
        verify(queue).push(
          CompleteStage(
            message.executionType,
            message.executionId,
            message.application,
            message.stageId
          )
        )
      }

      it("does not run the next task") {
        verify(queue, never()).push(any<RunTask>())
      }

      it("publishes an event") {
        verify(publisher).publishEvent(
          check<TaskComplete> {
            assertThat(it.executionType).isEqualTo(pipeline.type)
            assertThat(it.executionId).isEqualTo(pipeline.id)
            assertThat(it.stage.id).isEqualTo(message.stageId)
            assertThat(it.task.id).isEqualTo(message.taskId)
            assertThat(it.task.status).isEqualTo(status)
          }
        )
      }
    }
  }

  describe("when a task should complete parent stage") {
    val task = fun(isStageEnd: Boolean): TaskExecution {
      val task = TaskExecutionImpl()
      task.isStageEnd = isStageEnd
      return task
    }

    it("is last task in stage") {
      subject.shouldCompleteStage(task(true), SUCCEEDED, SUCCEEDED) == true
      subject.shouldCompleteStage(task(false), SUCCEEDED, SUCCEEDED) == false
    }

    it("did not originally complete with FAILED_CONTINUE") {
      subject.shouldCompleteStage(task(false), TERMINAL, TERMINAL) == true
      subject.shouldCompleteStage(task(false), FAILED_CONTINUE, TERMINAL) == true
      subject.shouldCompleteStage(task(false), FAILED_CONTINUE, FAILED_CONTINUE) == false
    }
  }

  describe("manual skip behavior") {
    given("a stage with a manual skip flag") {
      val pipeline = pipeline {
        stage {
          refId = "1"
          type = singleTaskStage.type
          singleTaskStage.buildTasks(this, NoOpTaskImplementationResolver())
          context["manualSkip"] = true
        }
      }
      val message = CompleteTask(
        pipeline.type,
        pipeline.id,
        pipeline.application,
        pipeline.stageByRef("1").id,
        "1",
        SKIPPED,
        SKIPPED
      )

      beforeGroup {
        whenever(repository.retrieve(PIPELINE, message.executionId)) doReturn pipeline
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        subject.handle(message)
      }

      it("skips the stage") {
        verify(queue).push(SkipStage(pipeline.stageByRef("1")))
      }
    }

    given("a stage whose parent has a manual skip flag") {
      val pipeline = pipeline {
        stage {
          refId = "1"
          type = singleTaskStage.type
          singleTaskStage.buildTasks(this, NoOpTaskImplementationResolver())
          context["manualSkip"] = true

          stage {
            refId = "1<1"
            type = singleTaskStage.type
            singleTaskStage.buildTasks(this, NoOpTaskImplementationResolver())
          }
        }
      }
      val message = CompleteTask(
        pipeline.type,
        pipeline.id,
        pipeline.application,
        pipeline.stageByRef("1<1").id,
        "1",
        SKIPPED,
        SKIPPED
      )

      beforeGroup {
        whenever(repository.retrieve(PIPELINE, message.executionId)) doReturn pipeline
      }

      afterGroup(::resetMocks)

      action("the handler receives a message") {
        subject.handle(message)
      }

      it("skips the parent stage") {
        verify(queue).push(SkipStage(pipeline.stageByRef("1")))
      }
    }
  }
})
