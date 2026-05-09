package com.noelnp.agenticcrawl

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class AgenticCrawlApplication

fun main(args: Array<String>) {
    runApplication<AgenticCrawlApplication>(*args)
}
