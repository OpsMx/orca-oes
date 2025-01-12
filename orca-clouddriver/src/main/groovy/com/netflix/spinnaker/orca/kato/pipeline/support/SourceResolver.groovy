/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.orca.kato.pipeline.support

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerHttpException
import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution
import com.netflix.spinnaker.orca.clouddriver.CloudDriverService
import com.netflix.spinnaker.orca.clouddriver.model.ServerGroup
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.support.Location
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.support.TargetServerGroup
import com.netflix.spinnaker.orca.clouddriver.pipeline.servergroup.support.TargetServerGroupResolver
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

@Component
@CompileStatic
@Slf4j
class SourceResolver {

  @Autowired CloudDriverService cloudDriverService
  @Autowired ObjectMapper mapper

  @Autowired
  TargetServerGroupResolver resolver

  StageData.Source getSource(StageExecution stage) throws JsonParseException, JsonMappingException {
    def stageData = stage.mapTo(StageData)
    if (stageData.source) {
      // targeting a source in a different account and region
      if (stageData.source.clusterName && stage.context.target) {
        TargetServerGroup.Params params = new TargetServerGroup.Params(
          cloudProvider:  stageData.cloudProvider,
          credentials: stageData.source.account,
          cluster: stageData.source.clusterName,
          target: TargetServerGroup.Params.Target.valueOf(stage.context.target as String),
          locations: [Location.region(stageData.source.region)]
        )

        def targetServerGroups = resolver.resolveByParams(params)

        if (targetServerGroups) {
          return new StageData.Source(account: params.credentials as String,
            region: targetServerGroups[0].region as String,
            serverGroupName: targetServerGroups[0].name as String,
            asgName: targetServerGroups[0].name as String)
        } else {
          return null
        }
      } else {
        // has an existing source, return it
        return stageData.source
      }
    } else if (stage.context.target) {
      // If no source was specified, but targeting coordinates were, attempt to resolve the target server group.
      TargetServerGroup.Params params = TargetServerGroup.Params.fromStage(stage)

      if (!params.cluster && stage.context.targetCluster) {
        params.cluster = stage.context.targetCluster
      }

      def targetServerGroups = resolver.resolveByParams(params)

      if (targetServerGroups) {
        return new StageData.Source(account: params.credentials as String,
                                    region: targetServerGroups[0].region as String,
                                    serverGroupName: targetServerGroups[0].name as String,
                                    asgName: targetServerGroups[0].name as String)
      }
    }

    def existingAsgs = getExistingAsgs(
      stageData.application, stageData.account, stageData.cluster, stageData.cloudProvider
    )

    if (!existingAsgs) {
      return null
    }

    if (!stageData.region && !stageData.availabilityZones) {
      throw new IllegalStateException("No 'region' or 'availabilityZones' in stage context")
    }

    def targetRegion = stageData.region
    List<ServerGroup> regionalAsgs = existingAsgs.findAll { it.region == targetRegion }
    if (!regionalAsgs) {
      return null
    }

    //prefer enabled ASGs but allow disabled in favour of nothing
    def onlyEnabled = regionalAsgs.findAll { it.disabled == false }
    if (onlyEnabled) {
      regionalAsgs = onlyEnabled
    }

    if (stageData.useSourceCapacity) {
      regionalAsgs = regionalAsgs.sort(false, new CompositeComparator([new InstanceCount(), new CreatedTime()]))
    }
    //with useSourceCapacity prefer the largest ASG over the newest ASG
    def latestAsg = regionalAsgs.last()
    return new StageData.Source(
      account: stageData.account, region: latestAsg["region"] as String, asgName: latestAsg["name"] as String, serverGroupName: latestAsg["name"] as String
    )
  }

  List<ServerGroup> getExistingAsgs(String app, String account, String cluster, String cloudProvider) throws JsonParseException, JsonMappingException {
    try {
      def map = cloudDriverService.getCluster(app, account, cluster, cloudProvider)
      map.serverGroups.sort { it.createdTime }
    } catch (SpinnakerHttpException e) {
      if (e.getResponseCode() == 404) {
        return []
      }
      throw e
    }
  }

  static class InstanceCount implements Comparator<ServerGroup> {
    @Override
    int compare(ServerGroup o1, ServerGroup o2) {
      def size1 = o1.instances?.size() ?: 0
      def size2 = o2.instances?.size() ?: 0
      size1 <=> size2
    }
  }

  static class CreatedTime implements Comparator<ServerGroup> {
    @Override
    int compare(ServerGroup o1, ServerGroup o2) {
      def createdTime1 = o1.createdTime ?: 0
      def createdTime2 = o2.createdTime ?: 0
      createdTime2 <=> createdTime1
    }
  }

  static class CompositeComparator implements Comparator<ServerGroup> {
    private final List<Comparator<ServerGroup>> comparators

    CompositeComparator(List<Comparator<ServerGroup>> comparators) {
      this.comparators = comparators
    }

    @Override
    int compare(ServerGroup o1, ServerGroup o2) {
      comparators.findResult { it.compare(o1, o2) ?: null } ?: 0
    }
  }
}


