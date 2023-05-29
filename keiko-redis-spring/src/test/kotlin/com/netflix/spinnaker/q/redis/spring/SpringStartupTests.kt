package com.netflix.spinnaker.q.redis.spring

import com.netflix.spinnaker.config.QueueConfiguration
import com.netflix.spinnaker.config.RedisQueueConfiguration
import com.netflix.spinnaker.q.Queue
import com.netflix.spinnaker.q.redis.RedisQueue
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE
import org.springframework.test.context.junit.jupiter.SpringExtension
/* import org.springframework.boot.SpringBootConfiguration */
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.ComponentScan


@ExtendWith(SpringExtension::class)
@SpringBootTest(
  classes = [QueueConfiguration::class, RedisQueueConfiguration::class],
  webEnvironment = NONE
)
/* @SpringBootConfiguration */
@EnableAutoConfiguration
@ComponentScan(basePackages = [ "com.netflix.spinnaker.config", "com.netflix.spinnaker.kork"] )
internal class SpringStartupTests {

  @Autowired
  lateinit var queue: Queue

  @Test
  fun `starts up successfully`() {
    Assertions.assertThat(queue).isNotNull.isInstanceOf(RedisQueue::class.java)
  }
}
