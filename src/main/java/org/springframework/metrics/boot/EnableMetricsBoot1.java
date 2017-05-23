/**
 * Copyright 2017 Pivotal Software, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.metrics.boot;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.metrics.instrument.MeterRegistry;
import org.springframework.metrics.instrument.TagFormatter;
import org.springframework.metrics.instrument.binder.JvmMemoryMetrics;
import org.springframework.metrics.instrument.binder.LogbackMetrics;
import org.springframework.metrics.instrument.scheduling.MetricsSchedulingAspect;
import org.springframework.metrics.instrument.web.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.lang.annotation.*;
import java.util.ArrayList;

/**
 * Enable dimensional metrics collection.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Import(MetricsBoot1Configuration.class)
public @interface EnableMetricsBoot1 {
}

@Configuration
class MetricsBoot1Configuration {
    @Bean
    @ConditionalOnMissingBean(TagFormatter.class)
    public TagFormatter tagFormatter() {
        return new TagFormatter() {};
    }

    @Bean
    @ConditionalOnMissingBean(WebMetricsTagConfigurer.class)
    public WebMetricsTagConfigurer defaultMetricsTagProvider(TagFormatter tagFormatter) {
        return new DefaultWebMetricsTagConfigurer(tagFormatter);
    }

    @Configuration
    static class RecommendedMeterBinders {
        @Bean
        JvmMemoryMetrics jvmMemoryMetrics() {
            return new JvmMemoryMetrics();
        }

        @Bean
        @ConditionalOnClass(ch.qos.logback.classic.Logger.class)
        LogbackMetrics logbackMetrics() {
            return new LogbackMetrics();
        }
    }

    @Configuration
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @Import(WebMetricsTagProviderConfiguration.class)
    static class WebFluxConfiguration {
        @Autowired
        MeterRegistry registry;

        @Bean
        public WebfluxMetricsWebFilter webfluxMetrics(WebMetricsTagConfigurer provider) {
            return new WebfluxMetricsWebFilter(registry, provider, "http_server_requests");
        }
    }

    /**
     * We continue to use the deprecated WebMvcConfigurerAdapter for backwards compatibility
     * with Spring Framework 4.X.
     */
    @SuppressWarnings("deprecation")
    @Configuration
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @Import(WebMetricsTagProviderConfiguration.class)
    static class WebMvcConfiguration extends org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter {
        @Autowired
        MeterRegistry registry;

        @Autowired
        WebMetricsTagConfigurer tagProvider;

        @Autowired
        Environment environment;

        @Bean
        WebmvcMetricsHandlerInterceptor webMetricsInterceptor() {
            return new WebmvcMetricsHandlerInterceptor(registry, tagProvider,
                    environment.getProperty("spring.metrics.web.server_requests.name", "http_server_requests"));
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(webMetricsInterceptor());
        }
    }

    @Configuration
    @ConditionalOnWebApplication
    static class WebMetricsTagProviderConfiguration {
        @Bean
        @ConditionalOnMissingBean(WebMetricsTagConfigurer.class)
        @ConditionalOnClass(name = "javax.servlet.http.HttpServletRequest")
        public WebMetricsTagConfigurer defaultMetricsTagProvider(TagFormatter tagFormatter) {
            return new DefaultWebMetricsTagConfigurer(tagFormatter);
        }

        @Bean
        @ConditionalOnMissingBean(WebMetricsTagConfigurer.class)
        @ConditionalOnMissingClass("javax.servlet.http.HttpServletRequest")
        public WebMetricsTagConfigurer emptyMetricsTagProvider() {
            return new WebMetricsTagConfigurer() {};
        }
    }

    /**
     * If AOP is not enabled, scheduled interception will not work.
     */
    @Bean
    @ConditionalOnClass({RestTemplate.class, JoinPoint.class})
    @ConditionalOnProperty(value = "spring.aop.enabled", havingValue = "true", matchIfMissing = true)
    public MetricsSchedulingAspect metricsSchedulingAspect(MeterRegistry registry) {
        return new MetricsSchedulingAspect(registry);
    }

    /**
     * If AOP is not enabled, client request interception will still work, but the URI tag
     * will always be evaluated to "none".
     */
    @Configuration
    @ConditionalOnClass({RestTemplate.class, JoinPoint.class})
    @ConditionalOnProperty(value = "spring.aop.enabled", havingValue = "true", matchIfMissing = true)
    static class MetricsRestTemplateAspectConfiguration {
        @Bean
        RestTemplateUrlTemplateCapturingAspect restTemplateUrlTemplateCapturingAspect() {
            return new RestTemplateUrlTemplateCapturingAspect();
        }
    }

    @Configuration
    @ConditionalOnClass(RestTemplate.class)
    static class MetricsRestTemplateConfiguration {

        @Bean
        MetricsClientHttpRequestInterceptor spectatorLoggingClientHttpRequestInterceptor(MeterRegistry meterRegistry,
                                                                                         WebMetricsTagConfigurer tagProvider,
                                                                                         Environment environment) {
            return new MetricsClientHttpRequestInterceptor(meterRegistry, tagProvider,
                    environment.getProperty("spring.metrics.web.client_requests.name", "http_client_requests"));
        }

        @Bean
        BeanPostProcessor spectatorRestTemplateInterceptorPostProcessor() {
            return new MetricsInterceptorPostProcessor();
        }

        private static class MetricsInterceptorPostProcessor
                implements BeanPostProcessor, ApplicationContextAware {
            private ApplicationContext context;
            private MetricsClientHttpRequestInterceptor interceptor;

            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                return bean;
            }

            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof RestTemplate) {
                    if (this.interceptor == null) {
                        this.interceptor = this.context
                                .getBean(MetricsClientHttpRequestInterceptor.class);
                    }
                    RestTemplate restTemplate = (RestTemplate) bean;
                    // create a new list as the old one may be unmodifiable (ie Arrays.asList())
                    ArrayList<ClientHttpRequestInterceptor> interceptors = new ArrayList<>();
                    interceptors.add(interceptor);
                    interceptors.addAll(restTemplate.getInterceptors());
                    restTemplate.setInterceptors(interceptors);
                }
                return bean;
            }

            @Override
            public void setApplicationContext(ApplicationContext context)
                    throws BeansException {
                this.context = context;
            }
        }
    }
}

/**
 * Captures the still-templated URI because currently the ClientHttpRequestInterceptor
 * currently only gives us the means to retrieve the substituted URI.
 *
 * @author Jon Schneider
 */
@Aspect
class RestTemplateUrlTemplateCapturingAspect {
    @Around("execution(* org.springframework.web.client.RestOperations+.*(String, ..))")
    Object captureUrlTemplate(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            String urlTemplate = (String) joinPoint.getArgs()[0];
            RestTemplateUrlTemplateHolder.setRestTemplateUrlTemplate(urlTemplate);
            return joinPoint.proceed();
        } finally {
            RestTemplateUrlTemplateHolder.clear();
        }
    }
}
