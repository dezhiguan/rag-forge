package com.ragforge.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RestController;

@Component
public class RoleBasedWebBeanPruner
    implements BeanDefinitionRegistryPostProcessor, EnvironmentAware, PriorityOrdered {

  private Environment environment;

  @Override
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
    String role = environment == null ? "all" : environment.getProperty("ragforge.role", "all");
    if (!"worker".equalsIgnoreCase(role) && !"judge".equalsIgnoreCase(role)) {
      return;
    }
    for (String beanName : registry.getBeanDefinitionNames()) {
      BeanDefinition definition = registry.getBeanDefinition(beanName);
      if (isWebController(definition)) {
        registry.removeBeanDefinition(beanName);
      }
    }
  }

  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
    // no-op
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  private boolean isWebController(BeanDefinition definition) {
    String className = definition.getBeanClassName();
    if (className == null) {
      return false;
    }
    try {
      Class<?> type = ClassUtils.forName(className, getClass().getClassLoader());
      return AnnotationUtils.findAnnotation(type, RestController.class) != null
          || AnnotationUtils.findAnnotation(type, Controller.class) != null;
    } catch (Throwable ignored) {
      return false;
    }
  }
}
