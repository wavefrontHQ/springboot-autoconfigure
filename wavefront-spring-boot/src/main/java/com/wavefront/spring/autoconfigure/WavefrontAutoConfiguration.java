package com.wavefront.spring.autoconfigure;

import java.util.stream.Collectors;

import com.wavefront.sdk.common.application.ApplicationTags;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.metrics.export.wavefront.WavefrontMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.sleuth.autoconfig.wavefront.WavefrontSleuthAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * {@link EnableAutoConfiguration Auto-configuration} to integrate with Wavefront metrics.
 *
 * @author Stephane Nicoll
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ApplicationTags.class)
@EnableConfigurationProperties(WavefrontProperties.class)
@AutoConfigureAfter(WavefrontMetricsExportAutoConfiguration.class)
@AutoConfigureBefore(WavefrontSleuthAutoConfiguration.class)
@Import(WavefrontMetricsConfiguration.class)
public class WavefrontAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ApplicationTags wavefrontApplicationTags(Environment environment,
      WavefrontProperties properties,
      ObjectProvider<ApplicationTagsBuilderCustomizer> customizers) {
    return new ApplicationTagsFactory(customizers.orderedStream().collect(Collectors.toList()))
        .createFromProperties(environment, properties);
  }

}
