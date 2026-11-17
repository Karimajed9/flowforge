package com.karimmajed.flowforge.security;

import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;

import java.util.List;

public class SecurityWebMvcContextCustomizerFactory implements ContextCustomizerFactory {

    @Override
    public ContextCustomizer createContextCustomizer(
            Class<?> testClass,
            List<ContextConfigurationAttributes> configAttributes) {

        if (!AnnotatedElementUtils.hasAnnotation(testClass, WebMvcTest.class)) {
            return null;
        }
        boolean defaultUser = testClass != ApiSecurityTest.class
                && testClass != IssuerUnavailableSecurityTest.class;
        return (context, mergedConfig) -> {
            if (context instanceof GenericApplicationContext generic) {
                AnnotatedBeanDefinitionReader reader = new AnnotatedBeanDefinitionReader(generic);
                if (defaultUser) {
                    reader.register(
                            SecurityConfig.class,
                            SecurityWebTestConfiguration.class,
                            CurrentCaller.class);
                } else {
                    reader.register(SecurityConfig.class, CurrentCaller.class);
                }
            }
        };
    }
}
