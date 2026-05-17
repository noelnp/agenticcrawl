package com.noelnp.agenticcrawl.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Configuration
class AsyncConfig {

    @Bean(destroyMethod = "shutdown")
    fun jobExecutor(): ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
}
