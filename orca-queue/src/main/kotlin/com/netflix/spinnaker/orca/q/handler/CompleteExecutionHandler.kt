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

import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus.CANCELED
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus.FAILED_CONTINUE
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus.NOT_STARTED
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus.RUNNING
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus.SKIPPED
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus.STOPPED
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus.SUCCEEDED
import com.netflix.spinnaker.orca.api.pipeline.models.ExecutionStatus.TERMINAL
import com.netflix.spinnaker.orca.api.pipeline.models.PipelineExecution
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution
import com.netflix.spinnaker.orca.events.ExecutionComplete
import com.netflix.spinnaker.orca.ext.allUpstreamStagesComplete
import com.netflix.spinnaker.orca.pipeline.persistence.ExecutionRepository
import com.netflix.spinnaker.orca.q.CancelStage
import com.netflix.spinnaker.orca.q.CompleteExecution
import com.netflix.spinnaker.orca.q.StartWaitingExecutions
import com.netflix.spinnaker.q.AttemptsAttribute
import com.netflix.spinnaker.q.Queue
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
class CompleteExecutionHandler(
  override val queue: Queue,
  override val repository: ExecutionRepository,
  @Qualifier("queueEventPublisher") private val publisher: ApplicationEventPublisher,
  private val registry: Registry,
  @Value("\${queue.retry.delay.ms:30000}") retryDelayMs: Long
) : OrcaMessageHandler<CompleteExecution> {

  private val log = LoggerFactory.getLogger(javaClass)
  private val retryDelay = Duration.ofMillis(retryDelayMs)
  private val completedId = registry.createId("executions.completed")

  override fun handle(message: CompleteExecution) {
    message.withExecution { execution ->
      if (execution.status.isComplete) {
        log.info("Execution ${execution.id} already completed with ${execution.status} status")
      } else {
        message.determineFinalStatus(execution) { status ->
          execution.updateStatus(status)
          repository.updateStatus(execution)
          publisher.publishEvent(ExecutionComplete(this, execution))

          registry.counter(
            completedId.withTags(
              "status", status.name,
              "executionType", execution.type.name,
              "application", execution.application,
              "origin", execution.origin ?: "unknown"
            )
          ).increment()
          if (status != SUCCEEDED) {
            execution
              .topLevelStages
              .filter { it.status == RUNNING }
              .forEach {
                queue.push(CancelStage(it))
              }
          }
        }
      }
      log.debug("Execution ${execution.id} is with ${execution.status} status and  Disabled concurrent executions is ${execution.isLimitConcurrent}")
      if (execution.status != RUNNING) {
        execution.pipelineConfigId?.let {
          queue.push(StartWaitingExecutions(it, purgeQueue = !execution.isKeepWaitingPipelines))
        }
      } else {
        log.debug("Not starting waiting executions as execution ${execution.id} is currently RUNNING.")
      }
    }
  }


  private fun CompleteExecution.determineFinalStatus(
    execution: PipelineExecution,
    block: (ExecutionStatus) -> Unit
  ) {
    execution.topLevelStages.let { stages ->
      if (stages.map { it.status }.all { it in setOf(SUCCEEDED, SKIPPED, FAILED_CONTINUE) }) {
        block.invoke(SUCCEEDED)
      } else if (stages.any { it.status == TERMINAL }) {
        block.invoke(TERMINAL)
      } else if (stages.any { it.status == CANCELED }) {
        block.invoke(CANCELED)
      } else if (stages.any { it.status == STOPPED } && !stages.otherBranchesIncomplete()) {
        block.invoke(if (execution.shouldOverrideSuccess()) TERMINAL else SUCCEEDED)
      } else {
        val attempts = getAttribute<AttemptsAttribute>()?.attempts ?: 0
        log.info("Re-queuing $this as the execution is not yet complete (attempts: $attempts)")
        queue.push(this, retryDelay)
      }
    }
  }

  private val PipelineExecution.topLevelStages
    get(): List<StageExecution> = stages.filter { it.parentStageId == null }

  private fun PipelineExecution.shouldOverrideSuccess(): Boolean =
    stages
      .filter { it.status == STOPPED }
      .any { it.context["completeOtherBranchesThenFail"] == true }

  private fun List<StageExecution>.otherBranchesIncomplete() =
    any { it.status == RUNNING } ||
      any { it.status == NOT_STARTED && it.allUpstreamStagesComplete() }

  override val messageType = CompleteExecution::class.java
}
