/*
 *
 *  Copyright 2016 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */
package springfox.documentation.spring.data.rest;

import com.fasterxml.classmate.TypeResolver;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.repository.support.Repositories;
import org.springframework.data.rest.core.config.RepositoryRestConfiguration;
import org.springframework.data.rest.core.mapping.ResourceMappings;
import org.springframework.data.rest.webmvc.BasePathAwareHandlerMapping;
import org.springframework.data.rest.webmvc.RepositoryRestHandlerMapping;
import org.springframework.data.rest.webmvc.support.JpaHelper;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import springfox.documentation.RequestHandler;
import springfox.documentation.schema.ClassSupport;
import springfox.documentation.spi.service.RequestHandlerProvider;
import springfox.documentation.spring.web.WebMvcRequestHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.base.Predicates.*;
import static com.google.common.collect.Lists.*;
import static springfox.documentation.spring.data.rest.EntityServices.*;

class LegacyEntityServicesProvider implements RequestHandlerProvider {
  private final ResourceMappings mappings;
  private final Repositories repositories;
  private final RepositoryRestHandlerMapping restMappings;
  private final BasePathAwareHandlerMapping basePathAwareMappings;
  private final TypeResolver typeResolver;

  @Autowired
  LegacyEntityServicesProvider(
      ResourceMappings mappings,
      JpaHelper jpaHelper,
      RepositoryRestConfiguration repositoryConfiguration,
      ApplicationContext applicationContext,
      Repositories repositories,
      TypeResolver typeResolver) {
    this.mappings = mappings;
    this.repositories = repositories;
    this.typeResolver = typeResolver;
    this.restMappings = constructRepositoryHandlerMapping(mappings, repositoryConfiguration, repositories);
    restMappings.setJpaHelper(jpaHelper);
    restMappings.setApplicationContext(applicationContext);
    restMappings.afterPropertiesSet();

    basePathAwareMappings = new BasePathAwareHandlerMapping(repositoryConfiguration);
    basePathAwareMappings.setApplicationContext(applicationContext);
    basePathAwareMappings.afterPropertiesSet();
  }

  private RepositoryRestHandlerMapping constructRepositoryHandlerMapping(
      ResourceMappings mappings,
      RepositoryRestConfiguration repositoryConfiguration,
      Repositories repositories) {

    String className = "org.springframework.data.rest.webmvc.RepositoryRestHandlerMapping$RepositoryCorsConfigurationAccessor";
    Optional<? extends Class> corsClass = ClassSupport.classByName(className);
    if (corsClass.isPresent()) {
      return new RepositoryRestHandlerMapping(mappings, repositoryConfiguration, repositories);
    }
    return new RepositoryRestHandlerMapping(mappings, repositoryConfiguration);
  }


  @Override
  public List<RequestHandler> requestHandlers() {
    ArrayList<RequestHandler> requestHandlers = newArrayList();
    FluentIterable<Map.Entry<RequestMappingInfo, HandlerMethod>> entries = FluentIterable.from(allEntries());
    for (Map.Entry<RequestMappingInfo, HandlerMethod> each : entries.filter(not(restDataServices()))) {
      requestHandlers.add(new WebMvcRequestHandler(each.getKey(), each.getValue()));
    }
    for (Map.Entry<RequestMappingInfo, HandlerMethod> each : entries.filter(repositories())) {
      requestHandlers.add(new WebMvcRequestHandler(each.getKey(), each.getValue()));
    }
    for (Map.Entry<RequestMappingInfo, HandlerMethod> each : entries.filter(entityServices())) {
      EntityRequestTemplate entityRequestTemplate
          = new EntityRequestTemplate(typeResolver, repositories, mappings, each.getKey(), each.getValue());
      requestHandlers.addAll(entityRequestTemplate.operations());
    }
    List<RequestHandler> searchHandlers = newArrayList();
    for (Map.Entry<RequestMappingInfo, HandlerMethod> each : entries.filter(entitySearchServices())) {
      EntitySearchRequestTemplate entityRequestHandlers
          = new EntitySearchRequestTemplate(typeResolver, mappings, each.getKey(), each.getValue());
      searchHandlers.addAll(entityRequestHandlers.operations());
    }

    List<RequestHandler> metadataHandlers = newArrayList();
    for (Map.Entry<RequestMappingInfo, HandlerMethod> each : basePathAwareMappings.getHandlerMethods().entrySet()) {
      if (entitySchemaService().apply(each)
          || alpsProfileServices().apply(each)) {
        metadataHandlers.addAll(
            new EntitySchemaTemplate(
                typeResolver,
                mappings,
                each.getKey(),
                each.getValue()).operations());
      } else {
        requestHandlers.add(new WebMvcRequestHandler(each.getKey(), each.getValue()));
      }
    }
    requestHandlers.addAll(FluentIterable.from(metadataHandlers).filter(optionMethods()).toList());
    return requestHandlers;
  }

  private Predicate<RequestHandler> optionMethods() {
    return new Predicate<RequestHandler>() {
      @Override
      public boolean apply(RequestHandler input) {
        return input.supportedMethods().contains(RequestMethod.OPTIONS);
      }
    };
  }

  private Set<Map.Entry<RequestMappingInfo, HandlerMethod>> allEntries() {
    return restMappings.getHandlerMethods().entrySet();
  }
}
