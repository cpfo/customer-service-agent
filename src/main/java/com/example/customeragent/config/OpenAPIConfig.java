package com.example.customeragent.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenAPIConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("智能客服 AI Agent API")
                        .version("1.0.0")
                        .description("基于 Spring AI Alibaba + DashScope (通义千问) 的智能客服系统。" +
                                "支持 RAG 检索增强生成、多轮对话、知识库管理、SSE 流式响应、Function Calling。")
                        .contact(new Contact()
                                .name("开发者")
                                .email("support@example.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")));
    }
}
