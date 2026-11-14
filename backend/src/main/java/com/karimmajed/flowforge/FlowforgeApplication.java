package com.karimmajed.flowforge;

import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableRabbit
public class FlowforgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(
            FlowforgeApplication.class,
            args
        );
    }
}