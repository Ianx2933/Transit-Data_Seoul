package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    // RestTemplate을 Spring Bean으로 등록
    // @Bean → Spring이 관리하는 객체로 등록해서 어디서든 주입받아 사용 가능
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}