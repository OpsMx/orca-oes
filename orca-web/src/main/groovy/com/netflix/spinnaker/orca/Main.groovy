/*
 * Copyright 2014 Netflix, Inc.
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

package com.netflix.spinnaker.orca

import com.netflix.spinnaker.orca.applications.config.ApplicationConfig
import com.netflix.spinnaker.orca.bakery.config.BakeryConfiguration
import com.netflix.spinnaker.orca.clouddriver.config.CloudDriverConfiguration
import com.netflix.spinnaker.orca.clouddriver.config.ClouddriverJobConfiguration
import com.netflix.spinnaker.orca.clouddriver.config.ClouddriverLambdaConfiguration
import com.netflix.spinnaker.orca.config.CloudFoundryConfiguration
import com.netflix.spinnaker.orca.config.GremlinConfiguration
import com.netflix.spinnaker.orca.config.KeelConfiguration
import com.netflix.spinnaker.orca.config.OrcaConfiguration
import com.netflix.spinnaker.orca.config.PipelineTemplateConfiguration
import com.netflix.spinnaker.orca.config.RedisConfiguration
import com.netflix.spinnaker.orca.echo.config.EchoConfiguration
import com.netflix.spinnaker.orca.flex.config.FlexConfiguration
import com.netflix.spinnaker.orca.front50.config.Front50Configuration
import com.netflix.spinnaker.orca.igor.config.IgorConfiguration
import com.netflix.spinnaker.orca.kayenta.config.KayentaConfiguration
import com.netflix.spinnaker.orca.mine.config.MineConfiguration
import com.netflix.spinnaker.orca.web.config.WebConfiguration
import com.netflix.spinnaker.orca.webhook.config.WebhookConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration
import org.springframework.boot.autoconfigure.groovy.template.GroovyTemplateAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer
import org.springframework.context.annotation.Import
import org.springframework.scheduling.annotation.EnableAsync
import com.netflix.spinnaker.kork.boot.DefaultPropertiesBuilder

@EnableAsync
@Import([
  WebConfiguration,
  OrcaConfiguration,
  RedisConfiguration,
  BakeryConfiguration,
  EchoConfiguration,
  Front50Configuration,
  FlexConfiguration,
  CloudDriverConfiguration,
  ClouddriverJobConfiguration,
  ClouddriverLambdaConfiguration,
  IgorConfiguration,
  MineConfiguration,
  ApplicationConfig,
  PipelineTemplateConfiguration,
  KayentaConfiguration,
  WebhookConfiguration,
  KeelConfiguration,
  CloudFoundryConfiguration,
  GremlinConfiguration
])
@SpringBootApplication(
    scanBasePackages = [
        "com.netflix.spinnaker.config",
    "com.netflix.spinnaker.kork"
    ],
    exclude = [BatchAutoConfiguration, GroovyTemplateAutoConfiguration, DataSourceAutoConfiguration]
)
class Main extends SpringBootServletInitializer {
  static final Map<String, String> DEFAULT_PROPS = new DefaultPropertiesBuilder().property("spring.application.name", "orca").build()

  static void main(String... args) {
    new SpringApplicationBuilder().properties(DEFAULT_PROPS).sources(Main).run(args)
  }

  @Override
  SpringApplicationBuilder configure(SpringApplicationBuilder application) {
    application.properties(DEFAULT_PROPS).sources(Main)
  }
}
