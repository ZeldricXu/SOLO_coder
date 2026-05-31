package com.web3platform.catalog.config;

import com.web3platform.catalog.application.usecase.*;
import com.web3platform.catalog.domain.repository.DependencyRepository;
import com.web3platform.catalog.domain.repository.ServiceRepository;
import com.web3platform.catalog.infrastructure.persistence.mybatis.MyBatisDependencyRepository;
import com.web3platform.catalog.infrastructure.persistence.mybatis.MyBatisServiceRepository;
import com.web3platform.catalog.infrastructure.persistence.mybatis.mapper.DependencyRelationMapper;
import com.web3platform.catalog.infrastructure.persistence.mybatis.mapper.ServiceEntryMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.web3platform.catalog.infrastructure.persistence.mybatis.mapper")
public class CatalogModuleConfig {
    @Bean
    public ServiceRepository serviceRepository(ServiceEntryMapper mapper) {
        return new MyBatisServiceRepository(mapper);
    }

    @Bean
    public DependencyRepository dependencyRepository(DependencyRelationMapper mapper) {
        return new MyBatisDependencyRepository(mapper);
    }

    @Bean
    public CreateServiceUseCase createServiceUseCase(ServiceRepository serviceRepository) {
        return new CreateServiceUseCase(serviceRepository);
    }

    @Bean
    public GetServiceUseCase getServiceUseCase(ServiceRepository serviceRepository) {
        return new GetServiceUseCase(serviceRepository);
    }

    @Bean
    public UpdateServiceUseCase updateServiceUseCase(ServiceRepository serviceRepository) {
        return new UpdateServiceUseCase(serviceRepository);
    }

    @Bean
    public DeleteServiceUseCase deleteServiceUseCase(
        ServiceRepository serviceRepository,
        DependencyRepository dependencyRepository
    ) {
        return new DeleteServiceUseCase(serviceRepository, dependencyRepository);
    }

    @Bean
    public SearchServicesUseCase searchServicesUseCase(ServiceRepository serviceRepository) {
        return new SearchServicesUseCase(serviceRepository);
    }

    @Bean
    public AddDependencyUseCase addDependencyUseCase(
        DependencyRepository dependencyRepository,
        ServiceRepository serviceRepository
    ) {
        return new AddDependencyUseCase(dependencyRepository, serviceRepository);
    }

    @Bean
    public GetDependenciesUseCase getDependenciesUseCase(DependencyRepository dependencyRepository) {
        return new GetDependenciesUseCase(dependencyRepository);
    }
}
